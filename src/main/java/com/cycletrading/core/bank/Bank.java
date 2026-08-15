package com.cycletrading.core.bank;

import com.cycletrading.CycleTradingPlugin;
import com.cycletrading.core.Items;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 银行：虚拟绿宝石数据库。余额为 long，突破实物 64 堆叠上限。
 *
 * - 账户按 UUID 存储于 ConcurrentHashMap，所有余额变更在 accounts.compute 内原子完成，
 *   并同步追加审计流水（ledger，容量受限，先进先出）；
 * - 存款/取款触碰背包，必须在玩家 entity 线程调用（命令与 GUI 路径已保证）；
 * - 市场集成的 credit/tryDebit 为纯数据操作，任意线程安全；
 * - 冻结账户禁止存款/取款/转账/市场支付，但允许收益入账（不阻断他人交易）。
 */
public final class Bank {

    public enum DepositResult { SUCCESS, FROZEN, INSUFFICIENT_PHYSICAL, OVER_CAP }

    public enum WithdrawResult { SUCCESS, FROZEN, INSUFFICIENT_BALANCE, INVENTORY_FULL }

    public enum TransferResult { SUCCESS, FROZEN, SELF, INSUFFICIENT_BALANCE, INVALID }

    public enum AdminResult { SUCCESS, INVALID, NOT_ENOUGH, OVER_CAP }

    private static final long MAX_STACK = 64;

    /** 系统国库账户标识（奢侈品成交款回收池），不计入玩家总存量。 */
    public static final String SYSTEM = "SYSTEM";

    private final CycleTradingPlugin plugin;
    private final ConcurrentHashMap<String, BankAccount> accounts = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<TxEntry> ledger = new CopyOnWriteArrayList<>();
    private final AtomicLong nextTxId = new AtomicLong(1);
    /** 全服玩家总存量（不含国库），O(1) 查询，供奢侈品动态定价。 */
    private final AtomicLong totalSupply = new AtomicLong(0);

    public Bank(CycleTradingPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- 查询 ----------

    /** 只读查找，不存在返回 null（不创建账户）。 */
    public BankAccount find(String uuid) {
        return accounts.get(uuid);
    }

    public long balance(String uuid) {
        BankAccount a = accounts.get(uuid);
        return a == null ? 0 : a.balance;
    }

    public boolean isFrozen(String uuid) {
        BankAccount a = accounts.get(uuid);
        return a != null && a.frozen;
    }

    /** 全服玩家总存量（不含系统国库）。 */
    public long playerSupply() {
        return totalSupply.get();
    }

    public List<TxEntry> recent(String uuid, int n) {
        return ledger.stream()
                .filter(t -> uuid.equals(t.owner))
                .sorted(Comparator.comparingLong((TxEntry t) -> t.id).reversed())
                .limit(Math.max(0, n))
                .toList();
    }

    // ---------- 存取 ----------

    /** 存款：实物绿宝石 → 虚拟余额。必须在玩家线程调用。 */
    public DepositResult deposit(Player p, int amount) {
        String uuid = p.getUniqueId().toString();
        if (isFrozen(uuid)) {
            return DepositResult.FROZEN;
        }
        if (amount <= 0 || Items.currencyCount(p) < amount) {
            return DepositResult.INSUFFICIENT_PHYSICAL;
        }
        Map<Integer, ItemStack> left = p.getInventory().removeItem(Items.emeralds(amount));
        int removed = amount - left.values().stream().mapToInt(ItemStack::getAmount).sum();
        if (removed <= 0) {
            return DepositResult.INSUFFICIENT_PHYSICAL;
        }
        AtomicReference<Boolean> ok = new AtomicReference<>(false);
        accounts.compute(uuid, (k, a) -> {
            BankAccount acc = a == null ? newAccount(k, p.getName()) : a;
            if (acc.balance + removed > plugin.bankMaxBalance()) {
                return acc;
            }
            acc.balance += removed;
            acc.updatedAt = now();
            record(acc, TxEntry.DEPOSIT, null, removed);
            addSupply(k, removed);
            ok.set(true);
            return acc;
        });
        if (!ok.get()) {
            p.getInventory().addItem(Items.emeralds(removed)); // 超上限退还实物
            return DepositResult.OVER_CAP;
        }
        plugin.storage().requestSave();
        return DepositResult.SUCCESS;
    }

    /** 取款：虚拟余额 → 实物绿宝石（按背包空间自动部分提取）。必须在玩家线程调用。 */
    public WithdrawResult withdraw(Player p, long amount, boolean all) {
        String uuid = p.getUniqueId().toString();
        if (isFrozen(uuid)) {
            return WithdrawResult.FROZEN;
        }
        long bal = balance(uuid);
        if (all) {
            amount = bal;
        }
        if (amount <= 0 || bal < amount) {
            return WithdrawResult.INSUFFICIENT_BALANCE;
        }
        long delivered = deliverEmeralds(p, amount);
        if (delivered <= 0) {
            return WithdrawResult.INVENTORY_FULL;
        }
        accounts.computeIfPresent(uuid, (k, acc) -> {
            acc.balance -= delivered;
            acc.updatedAt = now();
            record(acc, TxEntry.WITHDRAW, null, delivered);
            addSupply(k, -delivered);
            return acc;
        });
        plugin.storage().requestSave();
        return WithdrawResult.SUCCESS;
    }

    // ---------- 转账 ----------

    /** 虚拟转账（玩家间）。国库账户（SYSTEM）不参与转账。 */
    public synchronized TransferResult send(String fromUuid, String toUuid, String toName, long amount) {
        if (amount <= 0) {
            return TransferResult.INVALID;
        }
        if (SYSTEM.equals(fromUuid) || SYSTEM.equals(toUuid)) {
            return TransferResult.INVALID;
        }
        if (fromUuid.equals(toUuid)) {
            return TransferResult.SELF;
        }
        if (isFrozen(fromUuid)) {
            return TransferResult.FROZEN;
        }
        BankAccount from = accounts.get(fromUuid);
        if (from == null || from.balance < amount) {
            return TransferResult.INSUFFICIENT_BALANCE;
        }
        from.balance -= amount;
        from.updatedAt = now();
        record(from, TxEntry.SEND_OUT, toUuid, amount);
        accounts.compute(toUuid, (k, a) -> {
            BankAccount acc = a == null ? newAccount(k, toName) : a;
            acc.balance += amount;
            acc.updatedAt = now();
            record(acc, TxEntry.SEND_IN, fromUuid, amount);
            return acc;
        });
        plugin.storage().requestSave();
        return TransferResult.SUCCESS;
    }

    // ---------- 市场集成 ----------

    /** 市场支付：虚拟余额扣款。原子，余额不足或冻结返回 false（调用方走实物兜底）。 */
    public boolean tryDebit(String uuid, long amount) {
        return debit(uuid, amount, TxEntry.BUY);
    }

    /** 通用虚拟扣款（指定流水类型，如投保缴费）。原子。 */
    public boolean debit(String uuid, long amount, String type) {
        if (amount <= 0) {
            return false;
        }
        AtomicReference<Boolean> ok = new AtomicReference<>(false);
        accounts.computeIfPresent(uuid, (k, acc) -> {
            if (!acc.frozen && acc.balance >= amount) {
                acc.balance -= amount;
                acc.updatedAt = now();
                record(acc, type, null, amount);
                addSupply(k, -amount);
                ok.set(true);
            }
            return acc;
        });
        return ok.get();
    }

    /** 入账：市场结算/退款/后台加款/国库（SYSTEM）。纯数据操作，任意线程安全，不受上限约束。 */
    public void credit(String uuid, String name, long amount, String type) {
        if (amount <= 0) {
            return;
        }
        accounts.compute(uuid, (k, a) -> {
            BankAccount acc = a == null ? newAccount(k, name) : a;
            acc.balance += amount;
            acc.updatedAt = now();
            record(acc, type, null, amount);
            addSupply(k, amount);
            return acc;
        });
    }

    // ---------- 后台管理 ----------

    public AdminResult adminSet(String uuid, String name, long value) {
        if (SYSTEM.equals(uuid) || value < 0) {
            return AdminResult.INVALID;
        }
        if (value > plugin.bankMaxBalance()) {
            return AdminResult.OVER_CAP;
        }
        accounts.compute(uuid, (k, a) -> {
            BankAccount acc = a == null ? newAccount(k, name) : a;
            long delta = value - acc.balance;
            acc.balance = value;
            acc.updatedAt = now();
            record(acc, TxEntry.ADMIN_SET, null, Math.abs(delta));
            addSupply(k, delta);
            return acc;
        });
        plugin.storage().requestSave();
        return AdminResult.SUCCESS;
    }

    public AdminResult adminAdd(String uuid, String name, long delta) {
        if (SYSTEM.equals(uuid) || delta <= 0) {
            return AdminResult.INVALID;
        }
        accounts.compute(uuid, (k, a) -> {
            BankAccount acc = a == null ? newAccount(k, name) : a;
            acc.balance += delta;
            acc.updatedAt = now();
            record(acc, TxEntry.ADMIN_ADD, null, delta);
            addSupply(k, delta);
            return acc;
        });
        plugin.storage().requestSave();
        return AdminResult.SUCCESS;
    }

    public AdminResult adminRemove(String uuid, String name, long delta) {
        if (SYSTEM.equals(uuid) || delta <= 0) {
            return AdminResult.INVALID;
        }
        AtomicReference<Boolean> ok = new AtomicReference<>(true);
        accounts.compute(uuid, (k, a) -> {
            BankAccount acc = a == null ? newAccount(k, name) : a;
            if (acc.balance < delta) {
                ok.set(false);
                return acc;
            }
            acc.balance -= delta;
            acc.updatedAt = now();
            record(acc, TxEntry.ADMIN_REMOVE, null, delta);
            addSupply(k, -delta);
            return acc;
        });
        if (!ok.get()) {
            return AdminResult.NOT_ENOUGH;
        }
        plugin.storage().requestSave();
        return AdminResult.SUCCESS;
    }

    public AdminResult adminFreeze(String uuid, String name, boolean freeze) {
        if (SYSTEM.equals(uuid)) {
            return AdminResult.INVALID;
        }
        accounts.compute(uuid, (k, a) -> {
            BankAccount acc = a == null ? newAccount(k, name) : a;
            if (acc.frozen != freeze) {
                acc.frozen = freeze;
                acc.updatedAt = now();
                record(acc, freeze ? TxEntry.FREEZE : TxEntry.UNFREEZE, null, 0);
            }
            return acc;
        });
        plugin.storage().requestSave();
        return AdminResult.SUCCESS;
    }

    // ---------- 存档 ----------

    public List<BankAccount> accountsSnapshot() {
        return new ArrayList<>(accounts.values());
    }

    public List<TxEntry> ledgerSnapshot() {
        return new ArrayList<>(ledger);
    }

    public void restoreAccount(BankAccount a) {
        if (a != null) {
            accounts.put(a.owner, a);
        }
    }

    public void restoreTx(TxEntry t) {
        if (t != null) {
            ledger.add(t);
        }
    }

    public void rebuildTxId() {
        long max = ledger.stream().mapToLong(t -> t.id).max().orElse(0);
        nextTxId.set(max + 1);
    }

    /** 加载存档后重建玩家总存量（不含国库）。 */
    public void rebuildSupply() {
        long sum = 0;
        for (BankAccount a : accounts.values()) {
            if (!SYSTEM.equals(a.owner)) {
                sum += a.balance;
            }
        }
        totalSupply.set(sum);
    }

    // ---------- 内部 ----------

    /** 按 64 一组填充背包，返回实际放下的数量。 */
    private long deliverEmeralds(Player p, long amount) {
        long remaining = amount;
        long delivered = 0;
        while (remaining > 0) {
            int chunk = (int) Math.min(MAX_STACK, remaining);
            Map<Integer, ItemStack> left = p.getInventory().addItem(Items.emeralds(chunk));
            int leftAmt = left.values().stream().mapToInt(ItemStack::getAmount).sum();
            if (leftAmt == chunk) {
                break; // 一格都放不下 → 背包已满
            }
            delivered += chunk - leftAmt;
            remaining -= chunk;
        }
        return delivered;
    }

    private BankAccount newAccount(String uuid, String name) {
        long t = now();
        return new BankAccount(uuid, name == null ? "" : name, 0, false, t, t);
    }

    private void addSupply(String uuid, long delta) {
        if (!SYSTEM.equals(uuid)) {
            totalSupply.addAndGet(delta);
        }
    }

    private void record(BankAccount acc, String type, String counterpart, long amount) {
        ledger.add(new TxEntry(nextTxId.getAndIncrement(), now(), type, acc.owner, counterpart, amount, acc.balance));
        trimLedger();
    }

    /**
     * 记录一笔无余额变动的审计痕迹（如实物支付渠道的支出标记）。
     * 供个人流水查询，不创建账户、不影响存量。
     */
    public void recordTrace(String uuid, String name, String type, long amount) {
        if (uuid == null || amount <= 0) {
            return;
        }
        BankAccount acc = accounts.get(uuid);
        long balanceAfter = acc == null ? 0 : acc.balance;
        ledger.add(new TxEntry(nextTxId.getAndIncrement(), now(), type, uuid, null, amount, balanceAfter));
        trimLedger();
    }

    private void trimLedger() {
        int keep = plugin.bankLedgerKeep();
        while (ledger.size() > keep) {
            ledger.remove(0);
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
