package com.cycletrading.command;

import com.cycletrading.CycleTradingPlugin;
import com.cycletrading.core.Items;
import com.cycletrading.core.Market;
import com.cycletrading.core.bank.Bank;
import com.cycletrading.core.bank.BankAccount;
import com.cycletrading.core.bank.TxEntry;
import com.cycletrading.core.bond.Bond;
import com.cycletrading.core.bond.BondService;
import com.cycletrading.core.futures.Commodity;
import com.cycletrading.core.futures.FuturesContract;
import com.cycletrading.core.futures.FuturesService;
import com.cycletrading.core.insurance.InsurancePolicy;
import com.cycletrading.core.insurance.InsuranceService;
import com.cycletrading.core.luxury.LuxuryMarket;
import com.cycletrading.gui.GuiManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** /ct 命令树：market / sell / my / collect / bank / admin / help */
public final class CycleTradingCommand implements CommandExecutor, TabCompleter {

    private final CycleTradingPlugin plugin;
    private final Market market;
    private final Bank bank;
    private final LuxuryMarket luxury;
    private final InsuranceService insurance;
    private final BondService bonds;
    private final FuturesService futures;
    private final GuiManager guis;

    public CycleTradingCommand(CycleTradingPlugin plugin, Market market, Bank bank, LuxuryMarket luxury,
            InsuranceService insurance, BondService bonds, FuturesService futures, GuiManager guis) {
        this.plugin = plugin;
        this.market = market;
        this.bank = bank;
        this.luxury = luxury;
        this.insurance = insurance;
        this.bonds = bonds;
        this.futures = futures;
        this.guis = guis;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "market" -> marketCmd(sender, args);
            case "sell" -> sellCmd(sender, args);
            case "my" -> myCmd(sender);
            case "collect" -> collectCmd(sender);
            case "mail" -> mailCmd(sender);
            case "bank" -> bankCmd(sender, args);
            case "lux" -> luxCmd(sender, args);
            case "ins" -> insCmd(sender, args);
            case "bond" -> bondCmd(sender, args);
            case "fut" -> futCmd(sender, args);
            case "admin" -> adminCmd(sender, args);
            default -> sender.sendMessage("§c未知子命令，输入 /ct help 查看帮助");
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage("§e===== CycleTrading 绿宝石市场 + 银行 =====");
        s.sendMessage("§6/ct market [页]   §7浏览市场并购买");
        s.sendMessage("§6/ct sell <价格>  §7手持物品上架（价格单位：绿宝石）");
        s.sendMessage("§6/ct my           §7查看并下架自己的挂单");
        s.sendMessage("§6/ct mail         §7邮箱（只收不存，上限 " + plugin.mailbox().capacity() + "，点击领取）");
        s.sendMessage("§6/ct collect      §7一键领取邮箱 + 保险托管");
        s.sendMessage("§6/ct bank         §7查看银行余额");
        s.sendMessage("§6/ct bank deposit [数量|all]  §7实物绿宝石存入银行");
        s.sendMessage("§6/ct bank withdraw <数量|all> §7从银行提取实物绿宝石");
        s.sendMessage("§6/ct bank send <玩家> <数量>  §7虚拟绿宝石转账");
        s.sendMessage("§6/ct lux [页]    §7奢侈品商店（动态定价，仅管理员挂售）");
        s.sendMessage("§6/ct lux status  §7查看经济总存量与当前倍率");
        s.sendMessage("§6/ct ins         §7查看死亡保险档位与当前保单");
        s.sendMessage("§6/ct ins buy <1-4> §7购买死亡保险（死亡时按档位回滚物品）");
        s.sendMessage("§6/ct bond        §7我的定期债券（到期自动结算）");
        s.sendMessage("§6/ct bond info   §7五档利率/期限/最低购买量");
        s.sendMessage("§6/ct bond buy <档位> <金额> §7购买定期债券（仅虚拟余额）");
        s.sendMessage("§6/ct fut [页]    §7期货市场（标准大宗合约）");
        s.sendMessage("§6/ct fut info    §7标准合约品种与交割期限");
        s.sendMessage("§6/ct fut open <价格> <期限> §7手持标准数量商品开仓");
        s.sendMessage("§6/ct fut my      §7我的期货合约（撤单/交割状态）");
        if (s.hasPermission("cycletrading.admin")) {
            s.sendMessage("§c/ct lux list <基础价>  §7手持珍稀物品挂售（仅管理员）");
            s.sendMessage("§c/ct lux remove <编号> §7下架奢侈品");
            s.sendMessage("§c/ct ins admin view|set <玩家> [1-4|clear]");
            s.sendMessage("§c/ct bond admin stats|view <玩家>");
            s.sendMessage("§c/ct fut admin stats|deliver <编号>|cancel <编号>");
            s.sendMessage("§c/ct bank admin view|set|add|remove|freeze|unfreeze|ledger");
            s.sendMessage("§c/ct admin reload");
        }
    }

    // ---------- 市场 ----------

    private void marketCmd(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        int page = 0;
        if (args.length > 1) {
            try {
                page = Math.max(0, Integer.parseInt(args[1]) - 1);
            } catch (NumberFormatException ignored) {
                // 默认第一页
            }
        }
        guis.openMarket(p, page);
    }

    private void sellCmd(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 2) {
            p.sendMessage("§c用法: /ct sell <价格>  （手持要出售的物品）");
            return;
        }
        long price;
        try {
            price = Long.parseLong(args[1]);
        } catch (NumberFormatException ex) {
            p.sendMessage("§c价格必须是整数（单位：绿宝石）");
            return;
        }
        if (price < plugin.minPrice() || price > plugin.maxPrice()) {
            p.sendMessage("§c价格超出允许范围 [" + fmt(plugin.minPrice()) + ", " + fmt(plugin.maxPrice()) + "]");
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR || hand.getAmount() <= 0) {
            p.sendMessage("§c请手持要出售的物品再执行 /ct sell <价格>");
            return;
        }
        // 托管：物品从手中移除，进入系统存档
        p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        market.create(p.getUniqueId().toString(), p.getName(), hand, price);
        p.sendMessage("§a上架成功！§e" + hand.getType().name() + " ×" + hand.getAmount()
                + " §a售价 §e" + price + " 绿宝石§a。用 /ct market 查看市场");
    }

    private void myCmd(CommandSender sender) {
        if (requirePlayer(sender)) {
            guis.openMy((Player) sender);
        }
    }

    private void collectCmd(CommandSender sender) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        com.cycletrading.core.mailbox.Mailbox.CollectResult r = plugin.mailbox().collect(p);
        InsuranceService.DeliverResult dr = plugin.insurance().deliverPending(p);
        StringBuilder msg = new StringBuilder();
        if (r.items() > 0 || r.emeralds() > 0) {
            msg.append("§a已领取邮箱: §e").append(r.items()).append(" §a件物品、§e").append(r.emeralds()).append(" §a绿宝石");
        }
        if (dr.tier() > 0) {
            if (msg.length() > 0) {
                msg.append("  ·  ");
            }
            msg.append("§a保险托管回滚 §e").append(dr.restored()).append(" §a件");
            if (dr.pending() > 0) {
                msg.append("§c（仍有 ").append(dr.pending()).append(" 件因邮箱满暂存）");
            }
        }
        if (msg.length() == 0) {
            p.sendMessage("§7邮箱是空的，也没有待领取的保险托管");
        } else {
            p.sendMessage(msg.append("§7（放不下的部分仍保留）").toString());
        }
    }

    private void mailCmd(CommandSender sender) {
        if (requirePlayer(sender)) {
            guis.openMail((Player) sender);
        }
    }

    // ---------- 银行 ----------

    private void bankCmd(CommandSender sender, String[] args) {
        if (args.length < 2) {
            bankStatus(sender);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "deposit" -> bankDeposit(sender, args);
            case "withdraw" -> bankWithdraw(sender, args);
            case "send" -> bankSend(sender, args);
            case "admin" -> bankAdmin(sender, args);
            default -> bankStatus(sender);
        }
    }

    private void bankStatus(CommandSender sender) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        String uuid = p.getUniqueId().toString();
        BankAccount a = bank.find(uuid);
        p.sendMessage("§e===== 银行账户 =====");
        p.sendMessage("§6余额: §a" + fmt(a == null ? 0 : a.balance) + " 绿宝石"
                + "  §7(背包实物: " + fmt(Items.currencyCount(p)) + ")");
        if (a != null && a.frozen) {
            p.sendMessage("§c⚠ 账户已被冻结，禁止存取/转账/购买");
        }
        p.sendMessage("§7存款: /ct bank deposit [数量|all]  ·  取款: /ct bank withdraw <数量|all>");
    }

    private void bankDeposit(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        int amount = -1;
        if (args.length < 3 || args[2].equalsIgnoreCase("all")) {
            amount = Items.currencyCount(p);
        } else {
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                p.sendMessage("§c数量必须是整数");
                return;
            }
        }
        Bank.DepositResult r = bank.deposit(p, amount);
        switch (r) {
            case SUCCESS -> p.sendMessage("§a已存入 §e" + fmt(amount) + " 绿宝石§a，当前余额 §e"
                    + fmt(bank.balance(p.getUniqueId().toString())) + " 绿宝石");
            case FROZEN -> p.sendMessage("§c账户已被冻结，无法存款");
            case INSUFFICIENT_PHYSICAL -> p.sendMessage("§c背包里的绿宝石不足");
            case OVER_CAP -> p.sendMessage("§c超出账户余额上限（" + fmt(plugin.bankMaxBalance()) + "）");
        }
    }

    private void bankWithdraw(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 3) {
            p.sendMessage("§c用法: /ct bank withdraw <数量|all>");
            return;
        }
        long amount = -1;
        boolean all = args[2].equalsIgnoreCase("all");
        if (!all) {
            try {
                amount = Long.parseLong(args[2]);
            } catch (NumberFormatException ex) {
                p.sendMessage("§c数量必须是整数");
                return;
            }
        }
        long before = bank.balance(p.getUniqueId().toString());
        Bank.WithdrawResult r = bank.withdraw(p, amount, all);
        switch (r) {
            case SUCCESS -> {
                long got = before - bank.balance(p.getUniqueId().toString());
                p.sendMessage("§a已提取 §e" + fmt(got) + " §a个绿宝石到背包"
                        + (got < (all ? before : amount) ? "§7（背包已满，剩余保留在银行）" : "")
                        + "，余额 §e" + fmt(bank.balance(p.getUniqueId().toString())) + " 绿宝石");
            }
            case FROZEN -> p.sendMessage("§c账户已被冻结，无法取款");
            case INSUFFICIENT_BALANCE -> p.sendMessage("§c余额不足");
            case INVENTORY_FULL -> p.sendMessage("§c背包已满，无法提取");
        }
    }

    private void bankSend(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 4) {
            p.sendMessage("§c用法: /ct bank send <玩家> <数量>");
            return;
        }
        long amount;
        try {
            amount = Long.parseLong(args[3]);
        } catch (NumberFormatException ex) {
            p.sendMessage("§c数量必须是整数");
            return;
        }
        UUID target = resolveUuid(args[2]);
        if (target == null) {
            p.sendMessage("§c找不到玩家 " + args[2]);
            return;
        }
        Bank.TransferResult r = bank.send(p.getUniqueId().toString(), target.toString(), args[2], amount);
        switch (r) {
            case SUCCESS -> {
                p.sendMessage("§a已转账 §e" + fmt(amount) + " 绿宝石§a给 §e" + args[2]);
                Player to = plugin.getServer().getPlayer(target);
                if (to != null) {
                    to.sendMessage("§a收到 §e" + p.getName() + " §a的转账 §e" + fmt(amount) + " 绿宝石");
                }
            }
            case FROZEN -> p.sendMessage("§c账户已被冻结，无法转账");
            case SELF -> p.sendMessage("§c不能转账给自己");
            case INSUFFICIENT_BALANCE -> p.sendMessage("§c余额不足");
            case INVALID -> p.sendMessage("§c数量必须大于 0");
        }
    }

    private void bankAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cycletrading.admin")) {
            sender.sendMessage("§c权限不足");
            return;
        }
        if (args.length < 4) {
            sender.sendMessage("§c用法: /ct bank admin <view|set|add|remove|freeze|unfreeze|ledger> <玩家> [数量]");
            return;
        }
        String op = args[2].toLowerCase();
        String name = args[3];
        String uuid;
        if (name.equalsIgnoreCase("SYSTEM")) {
            uuid = Bank.SYSTEM;
        } else {
            UUID target = resolveUuid(name);
            if (target == null) {
                sender.sendMessage("§c找不到玩家 " + name);
                return;
            }
            uuid = target.toString();
        }
        if (Bank.SYSTEM.equals(uuid)
                && (op.equals("set") || op.equals("add") || op.equals("remove") || op.equals("freeze") || op.equals("unfreeze"))) {
            sender.sendMessage("§c系统国库账户只读（view/ledger），不可修改");
            return;
        }
        switch (op) {
            case "view" -> {
                BankAccount a = bank.find(uuid);
                if (a == null) {
                    sender.sendMessage("§7" + name + " 尚无银行账户");
                } else {
                    sender.sendMessage("§e" + name + " §7余额: §a" + fmt(a.balance) + " 绿宝石"
                            + (a.frozen ? " §c[已冻结]" : ""));
                }
            }
            case "set" -> {
                long v = parseAmount(args, 4);
                if (v < 0) {
                    sender.sendMessage("§c金额无效");
                    return;
                }
                Bank.AdminResult r = bank.adminSet(uuid, name, v);
                sender.sendMessage(r == Bank.AdminResult.SUCCESS
                        ? "§a已将 " + name + " 的余额设为 §e" + fmt(v) + " 绿宝石"
                        : r == Bank.AdminResult.OVER_CAP ? "§c超出余额上限" : "§c操作失败");
            }
            case "add", "remove" -> {
                long v = parseAmount(args, 4);
                if (v <= 0) {
                    sender.sendMessage("§c金额必须大于 0");
                    return;
                }
                Bank.AdminResult r = op.equals("add") ? bank.adminAdd(uuid, name, v) : bank.adminRemove(uuid, name, v);
                sender.sendMessage(r == Bank.AdminResult.SUCCESS
                        ? "§a操作成功，" + name + " 当前余额 §e" + fmt(bank.balance(uuid)) + " 绿宝石"
                        : r == Bank.AdminResult.NOT_ENOUGH ? "§c账户余额不足" : "§c操作失败");
            }
            case "freeze", "unfreeze" -> {
                boolean freeze = op.equals("freeze");
                bank.adminFreeze(uuid, name, freeze);
                sender.sendMessage("§a已将 " + name + (freeze ? " §c冻结" : " §a解冻"));
            }
            case "ledger" -> {
                int n = 10;
                if (args.length > 4) {
                    try {
                        n = Integer.parseInt(args[4]);
                    } catch (NumberFormatException ignored) {
                        // 默认 10
                    }
                }
                List<TxEntry> txs = bank.recent(uuid, n);
                if (txs.isEmpty()) {
                    sender.sendMessage("§7" + name + " 暂无流水记录");
                } else {
                    sender.sendMessage("§e" + name + " §7最近流水:");
                    SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm:ss");
                    for (TxEntry t : txs) {
                        sender.sendMessage("§7#" + t.id + " [" + f.format(new Date(t.ts)) + "] §6" + t.type
                                + " §e" + fmt(t.amount) + " §7→ 余额 " + fmt(t.balanceAfter));
                    }
                }
            }
            default -> sender.sendMessage("§c未知管理操作: " + op);
        }
    }

    // ---------- 奢侈品商店 ----------

    private void luxCmd(CommandSender sender, String[] args) {
        if (args.length < 2) {
            if (requirePlayer(sender)) {
                guis.openLux((Player) sender, 0);
            }
            return;
        }
        String a = args[1];
        if (a.matches("\\d+")) {
            if (requirePlayer(sender)) {
                guis.openLux((Player) sender, Math.max(0, Integer.parseInt(a) - 1));
            }
            return;
        }
        switch (a.toLowerCase()) {
            case "status" -> luxStatus(sender);
            case "list" -> luxList(sender, args);
            case "remove" -> luxRemove(sender, args);
            default -> sender.sendMessage("§c用法: /ct lux [页] | status | list <基础价> | remove <编号>");
        }
    }

    private void luxStatus(CommandSender s) {
        long supply = bank.playerSupply();
        double mult = luxury.multiplier();
        s.sendMessage("§e===== 奢侈品商店 =====");
        s.sendMessage("§7经济总存量(全体玩家银行余额): §a" + fmt(supply) + " 绿宝石");
        s.sendMessage("§7定价锚点: §a" + fmt(plugin.luxurySupplyAnchor())
                + " §7· 当前倍率: §a" + String.format("%.3f", mult) + "×"
                + " §7(上限 " + plugin.luxuryMaxMultiplier() + "×)");
        s.sendMessage("§7成交价 = 基础价 × 倍率（保底基础价），在售 " + luxury.activeNewestFirst().size() + " 件");
        if (s.hasPermission("cycletrading.admin")) {
            s.sendMessage("§7系统国库: §6" + fmt(bank.balance(Bank.SYSTEM)) + " 绿宝石§7（货币回收池）");
        }
    }

    private void luxList(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cycletrading.admin")) {
            sender.sendMessage("§c权限不足：仅管理员可挂售奢侈品");
            return;
        }
        if (!requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 3) {
            p.sendMessage("§c用法: /ct lux list <基础价>  （手持要挂售的珍稀物品）");
            return;
        }
        long base;
        try {
            base = Long.parseLong(args[2]);
        } catch (NumberFormatException ex) {
            p.sendMessage("§c基础价必须是整数（单位：绿宝石）");
            return;
        }
        if (base < 1 || base > plugin.luxuryMaxBasePrice()) {
            p.sendMessage("§c基础价超出允许范围 [1, " + fmt(plugin.luxuryMaxBasePrice()) + "]");
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR || hand.getAmount() <= 0) {
            p.sendMessage("§c请手持要挂售的珍稀物品再执行 /ct lux list <基础价>");
            return;
        }
        p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        long id = luxury.create(p.getName(), hand, base).id;
        p.sendMessage("§6已挂售！编号 #" + id + " · 基础价 " + fmt(base) + " 绿宝石"
                + " · 当前成交价 " + fmt(luxury.effectivePrice(base)) + " 绿宝石"
                + "§7（用 /ct lux 查看商店）");
    }

    private void luxRemove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cycletrading.admin")) {
            sender.sendMessage("§c权限不足");
            return;
        }
        if (!requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 3) {
            p.sendMessage("§c用法: /ct lux remove <编号>");
            return;
        }
        long id;
        try {
            id = Long.parseLong(args[2]);
        } catch (NumberFormatException ex) {
            p.sendMessage("§c编号无效");
            return;
        }
        guis.doLuxRemove(p, id);
    }

    // ---------- 死亡保险 ----------

    private void insCmd(CommandSender sender, String[] args) {
        if (args.length < 2) {
            insStatus(sender);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "buy" -> insBuy(sender, args);
            case "admin" -> insAdmin(sender, args);
            default -> insStatus(sender);
        }
    }

    private void insStatus(CommandSender sender) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (!plugin.insuranceEnabled()) {
            p.sendMessage("§c死亡保险暂未启用");
            return;
        }
        InsurancePolicy pol = insurance.policy(p.getUniqueId().toString());
        int pending = insurance.pendingCount(p.getUniqueId().toString());
        p.sendMessage("§e===== 死亡保险 =====");
        p.sendMessage("§7当前保单: " + (pol == null
                ? "§c无"
                : "§a档位" + pol.tier + " §7(死亡时生效，单次有效)"));
        if (pending > 0) {
            p.sendMessage("§c待领取托管: " + pending + " 件§7（清理背包/邮箱后 /ct collect）");
        }
        p.sendMessage("§6档位1: §a" + fmt(plugin.insT1Price()) + " 绿宝石§7 - 回滚快捷栏(9格)");
        p.sendMessage("§6档位2: §a" + fmt(plugin.insT2Price()) + " 绿宝石§7 - 回滚快捷栏+第一排(18格)");
        p.sendMessage("§6档位3: §a" + fmt(plugin.insT3Price()) + " 绿宝石§7 - 回滚全部物品栏+快捷栏(36格)");
        p.sendMessage("§6档位4: §a" + fmt(plugin.insT4Price()) + " 绿宝石§7 - 完全回滚+补偿 " + fmt(plugin.insT4Compensation()) + " 绿宝石入银行");
        p.sendMessage("§7购买: /ct ins buy <1-4>  ·  保费从银行余额/背包绿宝石扣除");
    }

    private void insBuy(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 3) {
            p.sendMessage("§c用法: /ct ins buy <1-4>");
            return;
        }
        int tier;
        try {
            tier = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            p.sendMessage("§c档位必须是 1-4");
            return;
        }
        InsuranceService.BuyResult r = insurance.buy(p, tier);
        switch (r) {
            case SUCCESS -> p.sendMessage("§a投保成功！档位" + tier + " 保单已生效"
                    + "（保费 " + fmt(insurance.premiumOf(tier)) + " 绿宝石），死亡时自动回滚");
            case FROZEN -> p.sendMessage("§c账户已被冻结，无法投保");
            case INSUFFICIENT_FUNDS -> p.sendMessage("§c绿宝石不足（银行余额 + 背包实物）");
            case INVALID_TIER -> p.sendMessage("§c档位无效，请输入 1-4");
            case DISABLED -> p.sendMessage("§c死亡保险暂未启用");
        }
    }

    private void insAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cycletrading.admin")) {
            sender.sendMessage("§c权限不足");
            return;
        }
        if (args.length < 4) {
            sender.sendMessage("§c用法: /ct ins admin <view|set> <玩家> [1-4|clear]");
            return;
        }
        String name = args[3];
        UUID target = resolveUuid(name);
        if (target == null) {
            sender.sendMessage("§c找不到玩家 " + name);
            return;
        }
        String uuid = target.toString();
        if (args[2].equalsIgnoreCase("view")) {
            InsurancePolicy pol = insurance.policy(uuid);
            sender.sendMessage(pol == null
                    ? "§7" + name + " 无保单"
                    : "§e" + name + " §7保单: §a档位" + pol.tier + " §7(保费 " + fmt(pol.premium) + ")");
        } else if (args[2].equalsIgnoreCase("set")) {
            if (args.length < 5) {
                sender.sendMessage("§c用法: /ct ins admin set <玩家> <1-4|clear>");
                return;
            }
            int tier;
            try {
                tier = Integer.parseInt(args[4]);
            } catch (NumberFormatException ex) {
                sender.sendMessage("§c档位必须是 1-4（clear 清空保单）");
                return;
            }
            insurance.adminSet(uuid, name, tier);
            sender.sendMessage(tier <= 0
                    ? "§a已清除 " + name + " 的保单"
                    : "§a已为 " + name + " 设置档位" + tier + " 保单");
        } else {
            sender.sendMessage("§c用法: /ct ins admin <view|set> <玩家> [1-4|clear]");
        }
    }

    // ---------- 定期债券 ----------

    private void bondCmd(CommandSender sender, String[] args) {
        if (args.length < 2) {
            if (requirePlayer(sender)) {
                guis.openBonds((Player) sender);
            }
            return;
        }
        switch (args[1].toLowerCase()) {
            case "info" -> bondInfo(sender);
            case "buy" -> bondBuy(sender, args);
            case "admin" -> bondAdmin(sender, args);
            default -> {
                if (requirePlayer(sender)) {
                    guis.openBonds((Player) sender);
                }
            }
        }
    }

    private void bondInfo(CommandSender s) {
        if (!plugin.bondEnabled()) {
            s.sendMessage("§c定期债券暂未启用");
            return;
        }
        s.sendMessage("§e===== 定期债券（仅虚拟绿宝石，按游戏日计息，到期自动结算）=====");
        s.sendMessage("§7总存量: §a" + fmt(bank.playerSupply()) + " §7· 利率倍率: §a"
                + String.format("%.3f", bonds.rateMultiplier()) + "×§7（上限 " + plugin.bondMaxMultiplier() + "×）");
        for (int t = 1; t <= BondService.TIERS; t++) {
            int bp = bonds.currentRateBp(t);
            s.sendMessage("§6档位" + t + ": " + plugin.bondDays(t) + "游戏日 · 基础利率 " + plugin.bondBaseRate(t)
                    + "% · 实际利率 §a" + BondService.fmtRate(bp)
                    + "§7 · 最低购买 " + fmt(plugin.bondMin(t)) + " 绿宝石");
        }
        s.sendMessage("§7购买: /ct bond buy <档位> <金额>  ·  利率购买后锁定 · 利息退一法取整");
    }

    private void bondBuy(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 4) {
            p.sendMessage("§c用法: /ct bond buy <档位1-5> <金额>");
            return;
        }
        int tier;
        long amount;
        try {
            tier = Integer.parseInt(args[2]);
            amount = Long.parseLong(args[3]);
        } catch (NumberFormatException ex) {
            p.sendMessage("§c档位/金额必须是整数");
            return;
        }
        BondService.BuyResult r = bonds.validate(p, tier, amount);
        switch (r) {
            case SUCCESS -> {
                Bond b = bonds.create(p, tier, amount);
                if (b == null) {
                    p.sendMessage("§c银行余额不足");
                } else {
                    p.sendMessage("§a购买成功！档位" + tier + " 定期债券 · 本金 §e" + fmt(amount)
                            + " §a绿宝石 · 锁定利率 §e" + BondService.fmtRate(b.rateBp)
                            + "§a · 期限 " + plugin.bondDays(tier) + " 游戏日，到期本息自动入账");
                }
            }
            case FROZEN -> p.sendMessage("§c账户已被冻结，无法购买");
            case INSUFFICIENT_FUNDS -> p.sendMessage("§c银行余额不足");
            case INVALID_TIER -> p.sendMessage("§c档位无效，请输入 1-" + BondService.TIERS);
            case INVALID_AMOUNT -> p.sendMessage("§c金额必须大于 0");
            case BELOW_MINIMUM -> p.sendMessage("§c低于该档最低购买量（" + fmt(plugin.bondMin(tier)) + " 绿宝石）");
            case DISABLED -> p.sendMessage("§c定期债券暂未启用");
        }
    }

    private void bondAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cycletrading.admin")) {
            sender.sendMessage("§c权限不足");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§c用法: /ct bond admin <stats|view> [玩家]");
            return;
        }
        if (args[2].equalsIgnoreCase("stats")) {
            sender.sendMessage("§e定期债券: §a" + bonds.activeCount() + " §7笔在持 · 总锁定 §a"
                    + fmt(bonds.totalLocked()) + " §7绿宝石 · 利率倍率 §a"
                    + String.format("%.3f", bonds.rateMultiplier()) + "×");
            return;
        }
        if (args[2].equalsIgnoreCase("view") && args.length >= 4) {
            UUID target = resolveUuid(args[3]);
            if (target == null) {
                sender.sendMessage("§c找不到玩家 " + args[3]);
                return;
            }
            List<Bond> act = bonds.activeBondsOf(target.toString());
            if (act.isEmpty()) {
                sender.sendMessage("§7" + args[3] + " 暂无在持债券");
            } else {
                sender.sendMessage("§e" + args[3] + " §7在持债券:");
                for (Bond b : act) {
                    sender.sendMessage("§7#" + b.id + " 档位" + b.tier + "(" + plugin.bondDays(b.tier) + "游戏日)"
                            + " · 本金 " + fmt(b.principal) + " · 利率 " + BondService.fmtRate(b.rateBp)
                            + " · 剩余约 " + bonds.daysLeft(b) + " 游戏日");
                }
            }
            return;
        }
        sender.sendMessage("§c用法: /ct bond admin <stats|view> [玩家]");
    }

    // ---------- 期货交割市场 ----------

    private void futCmd(CommandSender sender, String[] args) {
        if (args.length < 2) {
            if (requirePlayer(sender)) {
                guis.openFut((Player) sender, 0);
            }
            return;
        }
        String a = args[1];
        if (a.matches("\\d+")) {
            if (requirePlayer(sender)) {
                guis.openFut((Player) sender, Math.max(0, Integer.parseInt(a) - 1));
            }
            return;
        }
        switch (a.toLowerCase()) {
            case "info" -> futInfo(sender);
            case "open" -> futOpen(sender, args);
            case "my" -> futMy(sender);
            case "cancel" -> futCancel(sender, args);
            case "admin" -> futAdmin(sender, args);
            default -> sender.sendMessage("§c用法: /ct fut [页] | info | open <价格> <期限> | my | cancel <编号>");
        }
    }

    private void futInfo(CommandSender s) {
        if (!plugin.futuresEnabled()) {
            s.sendMessage("§c期货市场暂未启用");
            return;
        }
        s.sendMessage("§e===== 期货交易所 · 标准合约（全额保证金，到期实物交割）=====");
        for (Commodity c : plugin.futuresCommodities()) {
            s.sendMessage("§6" + c.key() + "§7: " + c.material().name() + " ×" + c.amount()
                    + "（" + c.stacks() + " 组，交割占用邮箱 " + c.stacks() + " 格）");
        }
        s.sendMessage("§7可选交割期限（游戏日）: " + plugin.futuresTerms());
        s.sendMessage("§7开仓: /ct fut open <价格> <期限>（手持正好标准数量） · 交割商品入买方邮箱，货款税后入银行");
    }

    private void futOpen(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (!plugin.futuresEnabled()) {
            p.sendMessage("§c期货市场暂未启用");
            return;
        }
        if (args.length < 4) {
            p.sendMessage("§c用法: /ct fut open <价格> <期限天数>");
            return;
        }
        long price;
        int term;
        try {
            price = Long.parseLong(args[2]);
            term = Integer.parseInt(args[3]);
        } catch (NumberFormatException ex) {
            p.sendMessage("§c价格/期限必须是整数");
            return;
        }
        if (price < plugin.minPrice() || price > plugin.maxPrice()) {
            p.sendMessage("§c价格超出允许范围 [" + fmt(plugin.minPrice()) + ", " + fmt(plugin.maxPrice()) + "]");
            return;
        }
        if (!plugin.futuresTerms().contains(term)) {
            p.sendMessage("§c期限必须为（游戏日）: " + plugin.futuresTerms());
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        Commodity match = null;
        for (Commodity c : plugin.futuresCommodities()) {
            if (hand.getType() == c.material() && hand.getAmount() == c.amount()) {
                match = c;
                break;
            }
        }
        if (match == null) {
            p.sendMessage("§c手持物品不符合标准合约（品种与数量必须完全匹配），见 /ct fut info");
            return;
        }
        p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        FuturesContract c = futures.open(p, hand, price, term);
        p.sendMessage("§a开仓成功！合约 #" + c.id + " · " + match.material().name() + " ×" + match.amount()
                + " · 价格 §e" + fmt(price) + " 绿宝石§a · 交割期限 " + term + " 游戏日"
                + "§7（商品已托管，未成交前可 /ct fut cancel " + c.id + " 撤单）");
    }

    private void futMy(CommandSender sender) {
        if (requirePlayer(sender)) {
            guis.openFutMy((Player) sender);
        }
    }

    private void futCancel(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 3) {
            p.sendMessage("§c用法: /ct fut cancel <编号>");
            return;
        }
        long id;
        try {
            id = Long.parseLong(args[2]);
        } catch (NumberFormatException ex) {
            p.sendMessage("§c编号无效");
            return;
        }
        FuturesService.CancelResult r = futures.cancel(p, id);
        switch (r) {
            case SUCCESS -> p.sendMessage("§a已撤单，商品已归还背包（放不下进邮箱）");
            case NOT_FOUND -> p.sendMessage("§c合约不存在");
            case NOT_ACTIVE -> p.sendMessage("§c该合约已成交或已撤销，无法撤单");
            case NOT_OWNER -> p.sendMessage("§c这不是你的合约");
            case NO_SPACE -> p.sendMessage("§c背包与邮箱均无空间，无法撤单（请先清理）");
            case ERROR -> p.sendMessage("§c撤单失败，请稍后再试");
        }
    }

    private void futAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cycletrading.admin")) {
            sender.sendMessage("§c权限不足");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§c用法: /ct fut admin <stats|deliver|cancel> [编号]");
            return;
        }
        switch (args[2].toLowerCase()) {
            case "stats" -> sender.sendMessage("§e期货交易所: §a" + futures.countByStatus(FuturesContract.OPEN)
                    + " §7挂单 · §a" + futures.countByStatus(FuturesContract.LOCKED)
                    + " §7锁定待交割 · §a" + futures.countByStatus(FuturesContract.DELIVERED) + " §7已交割");
            case "deliver", "cancel" -> {
                if (args.length < 4) {
                    sender.sendMessage("§c用法: /ct fut admin " + args[2] + " <编号>");
                    return;
                }
                long id;
                try {
                    id = Long.parseLong(args[3]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage("§c编号无效");
                    return;
                }
                boolean ok = args[2].equalsIgnoreCase("deliver") ? futures.adminDeliver(id) : futures.adminCancel(id);
                sender.sendMessage(ok ? "§a操作成功" : "§c合约不存在或状态不允许该操作");
            }
            default -> sender.sendMessage("§c用法: /ct fut admin <stats|deliver|cancel> [编号]");
        }
    }

    // ---------- 管理 ----------

    private void adminCmd(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cycletrading.admin")) {
            sender.sendMessage("§c权限不足");
            return;
        }
        if (args.length > 1 && args[1].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            sender.sendMessage("§a配置已重载");
        } else {
            sender.sendMessage("§c用法: /ct admin reload");
        }
    }

    // ---------- 工具 ----------

    private long parseAmount(String[] args, int idx) {
        if (args.length <= idx) {
            return -1;
        }
        try {
            return Long.parseLong(args[idx]);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private UUID resolveUuid(String name) {
        Player on = plugin.getServer().getPlayerExact(name);
        if (on != null) {
            return on.getUniqueId();
        }
        return Bukkit.getOfflinePlayer(name).getUniqueId();
    }

    private String fmt(long n) {
        return String.format("%,d", n);
    }

    private boolean requirePlayer(CommandSender s) {
        if (s instanceof Player) {
            return true;
        }
        s.sendMessage("§c该命令只能由玩家执行");
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("market", "sell", "my", "collect", "mail", "bank", "lux", "ins", "bond", "fut", "help");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("fut")) {
            return List.of("info", "open", "my", "cancel", "admin");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("fut") && args[1].equalsIgnoreCase("admin")) {
            return List.of("stats", "deliver", "cancel");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("bond")) {
            return List.of("info", "buy", "admin");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("bond") && args[1].equalsIgnoreCase("buy")) {
            return List.of("1", "2", "3", "4", "5");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("bond") && args[1].equalsIgnoreCase("admin")) {
            return List.of("stats", "view");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("bank")) {
            return List.of("deposit", "withdraw", "send", "admin");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("bank") && args[1].equalsIgnoreCase("admin")) {
            return List.of("view", "set", "add", "remove", "freeze", "unfreeze", "ledger");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("lux")) {
            return List.of("status", "list", "remove");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("ins")) {
            return List.of("buy", "admin");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("ins") && args[1].equalsIgnoreCase("buy")) {
            return List.of("1", "2", "3", "4");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("ins") && args[1].equalsIgnoreCase("admin")) {
            return List.of("view", "set");
        }
        return List.of();
    }
}
