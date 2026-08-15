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
import com.cycletrading.core.futures.FuturesPosition;
import com.cycletrading.core.futures.FuturesService;
import com.cycletrading.core.gold.GoldService;
import com.cycletrading.core.luxury.LuxuryMarket;
import com.cycletrading.core.options.OptionContract;
import com.cycletrading.core.options.OptionsService;
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
    private final BondService bonds;
    private final FuturesService futures;
    private final OptionsService options;
    private final GoldService gold;
    private final GuiManager guis;

    public CycleTradingCommand(CycleTradingPlugin plugin, Market market, Bank bank, LuxuryMarket luxury,
            BondService bonds, FuturesService futures, OptionsService options, GoldService gold, GuiManager guis) {
        this.plugin = plugin;
        this.market = market;
        this.bank = bank;
        this.luxury = luxury;
        this.bonds = bonds;
        this.futures = futures;
        this.options = options;
        this.gold = gold;
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
            case "bond" -> bondCmd(sender, args);
            case "fut" -> futCmd(sender, args);
            case "opt" -> optCmd(sender, args);
            case "gold" -> goldCmd(sender, args);
            case "cb" -> cbCmd(sender, args);
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
        s.sendMessage("§6/ct collect      §7一键领取邮箱");
        s.sendMessage("§6/ct bank         §7查看银行余额");
        s.sendMessage("§6/ct bank deposit [数量|all]  §7实物绿宝石存入银行");
        s.sendMessage("§6/ct bank withdraw <数量|all> §7从银行提取实物绿宝石");
        s.sendMessage("§6/ct bank send <玩家> <数量>  §7虚拟绿宝石转账");
        s.sendMessage("§6/ct lux [页]    §7奢侈品商店（动态定价，仅管理员挂售）");
        s.sendMessage("§6/ct lux status  §7查看经济总存量与当前倍率");
        s.sendMessage("§6/ct bond        §7我的定期债券（到期自动结算）");
        s.sendMessage("§6/ct bond info   §7五档利率/期限/最低购买量");
        s.sendMessage("§6/ct bond buy <档位> <金额> §7购买定期债券（仅虚拟余额）");
        s.sendMessage("§6/ct fut [页]    §7期货市场（标准大宗合约）");
        s.sendMessage("§6/ct fut info    §7标准合约品种与交割期限");
        s.sendMessage("§6/ct fut open <价格> <期限> §7手持标准数量商品开仓");
        s.sendMessage("§6/ct fut long|short <品种> <数量> <期限> §7开多/空单（保证金交易）");
        s.sendMessage("§6/ct fut pos / close <编号> §7我的头寸 / 提前平仓");
        s.sendMessage("§6/ct fut my      §7我的期货合约（撤单/交割状态）");
        s.sendMessage("§6/ct opt [页]    §7期权市场（看涨/看跌，现金结算）");
        s.sendMessage("§6/ct opt help    §7期权通俗指南");
        s.sendMessage("§6/ct opt open <call|put> <品种> <行权价> <权利金> <期限> §7开仓卖期权");
        s.sendMessage("§6/ct opt my      §7我的期权（撤单/到期状态）");
        s.sendMessage("§6/ct gold        §7金条行情（恒定发行，价格挂钩国库）");
        s.sendMessage("§6/ct gold buy/sell <数量> §7即买即卖金条（仅虚拟余额）");
        if (s.hasPermission("cycletrading.admin")) {
            s.sendMessage("§c/ct cb report  §7中央银行经济公报");
            s.sendMessage("§c/ct cb distribute <金额> | grant <玩家> <金额> | tax <玩家> <金额>");
            s.sendMessage("§c/ct cb anchor <lux|bond> <锚点>  §7利率决议（0=恢复配置）");
            s.sendMessage("§c/ct lux list <基础价>  §7手持珍稀物品挂售（仅管理员）");
            s.sendMessage("§c/ct lux remove <编号> §7下架奢侈品");
            s.sendMessage("§c/ct bond admin stats|view <玩家>");
            s.sendMessage("§c/ct fut admin stats|deliver <编号>|cancel <编号>");
            s.sendMessage("§c/ct opt admin stats|settle <编号>|cancel <编号>");
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
        // 通货禁挂：绿宝石本身不进市场（存取/兑换走银行）
        if (hand.getType() == Material.EMERALD || hand.getType() == Material.EMERALD_BLOCK) {
            p.sendMessage("§c绿宝石是通货，不接受上架（存取/兑换请用 /ct bank）");
            return;
        }
        // 价值锚点软区间（村民交易基础价 → 市场成交学习；0.5× ~ band×）
        long anchor = plugin.priceAnchor().anchorMilli(hand.getType());
        if (anchor > 0) {
            String src = plugin.priceAnchor().anchorSource(hand.getType());
            p.sendMessage("§7参考价: " + fmtPrice(anchor) + " 绿宝石/个（" + src + "）· 该组参考总价约 "
                    + fmtPrice(anchor * hand.getAmount()) + " 绿宝石");
            if (!plugin.priceAnchor().inBand(hand.getType(), hand.getAmount(), price)) {
                double band = plugin.anchorBand();
                p.sendMessage("§c价格超出参考区间（0.5× ~ " + band + "×），请按参考价合理定价"
                        + (band <= 0 ? "" : "") + "。参考总价区间约 [" + fmtPrice((long)(anchor * hand.getAmount() / band))
                        + ", " + fmtPrice((long)(anchor * hand.getAmount() * band)) + "] 绿宝石");
                return;
            }
        }
        // 托管：物品从手中移除，进入系统存档
        p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        market.create(p.getUniqueId().toString(), p.getName(), hand, price);
        p.sendMessage("§a上架成功！§e" + hand.getType().name() + " ×" + hand.getAmount()
                + " §a售价 §e" + price + " 绿宝石§a。用 /ct market 查看市场");
    }

    /** 毫绿宝石格式化：整数显示整数，否则 3 位小数。 */
    private String fmtPrice(long milli) {
        if (milli % 1000 == 0) {
            return fmt(milli / 1000);
        }
        return String.format("%.3f", milli / 1000.0);
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
        if (r.items() == 0 && r.emeralds() == 0) {
            p.sendMessage("§7邮箱是空的");
        } else {
            p.sendMessage("§a已领取邮箱: §e" + r.items() + " §a件物品、§e" + r.emeralds() + " §a绿宝石"
                    + "§7（放不下的部分仍保留）");
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
            case "ledger" -> bankLedger(sender, args);
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
        p.sendMessage("§7个人流水: /ct bank ledger [条数]");
    }

    /** 个人流水（任何玩家可查自己的最近交易）。 */
    private void bankLedger(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        int n = 5;
        if (args.length > 2) {
            try {
                n = Math.max(1, Math.min(20, Integer.parseInt(args[2])));
            } catch (NumberFormatException ignored) {
                // 默认 5
            }
        }
        List<TxEntry> txs = bank.recent(p.getUniqueId().toString(), n);
        if (txs.isEmpty()) {
            p.sendMessage("§7暂无个人流水记录");
            return;
        }
        p.sendMessage("§e===== 个人流水（最近 " + txs.size() + " 笔）=====");
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm:ss");
        for (TxEntry t : txs) {
            p.sendMessage("§7#" + t.id + " [" + f.format(new Date(t.ts)) + "] §6" + t.type
                    + " §e" + fmt(t.amount) + " §7→ 余额 " + fmt(t.balanceAfter));
        }
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
        long id = luxury.create(p.getName(), p.getUniqueId().toString(), hand, base).id;
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

    // ---------- 期权市场 ----------

    private void optCmd(CommandSender sender, String[] args) {
        if (args.length < 2) {
            if (requirePlayer(sender)) {
                guis.openOpt((Player) sender, 0);
            }
            return;
        }
        String a = args[1];
        if (a.matches("\\d+")) {
            if (requirePlayer(sender)) {
                guis.openOpt((Player) sender, Math.max(0, Integer.parseInt(a) - 1));
            }
            return;
        }
        switch (a.toLowerCase()) {
            case "help" -> optHelp(sender);
            case "info" -> optInfo(sender);
            case "open" -> optOpen(sender, args);
            case "my" -> optMy(sender);
            case "cancel" -> optCancel(sender, args);
            case "admin" -> optAdmin(sender, args);
            default -> sender.sendMessage("§c用法: /ct opt [页] | help | info | open <call|put> <品种> <行权价> <权利金> <期限> | my | cancel <编号>");
        }
    }

    /** 期权通俗指南。 */
    private void optHelp(CommandSender s) {
        s.sendMessage("§e===== 期权 · 通俗指南 =====");
        s.sendMessage("§7期权就是【花钱买一个\"将来按约定价交易的权利\"】：");
        s.sendMessage("§71. §f看涨call§7：赌到期时该品种会涨价 → 涨了就赚差价，跌了只亏权利金");
        s.sendMessage("§72. §f看跌put§7：赌到期时会跌价 → 跌了就赚差价，涨了只亏权利金");
        s.sendMessage("§73. 到期时只结算钱（现金结算），不搬货物");
        s.sendMessage("§74. 到期结算价 = 期货近期成交均价（无成交则用管理员参考价）");
        s.sendMessage("§7--- 卖方（开仓） ---");
        s.sendMessage("§7交全额保证金(行权价)托管 → §6/ct opt open <call|put> <品种> <行权价> <权利金> <期限>");
        s.sendMessage("§7--- 买方 ---");
        s.sendMessage("§6/ct opt§7 逛市场 → 点合约付权利金 → 到期自动结算赔付入银行");
        s.sendMessage("§7--- 规则 ---");
        s.sendMessage("§7· 买方最多亏权利金；卖方最多赔行权价（保证金兜底，不会赖账）");
        s.sendMessage("§7· 成交后不可反悔；未成交前卖方可 §6/ct opt cancel <编号>§7 撤单");
        s.sendMessage("§7· 品种与结算价来源见 §6/ct opt info");
    }

    private void optInfo(CommandSender s) {
        if (!plugin.optionsEnabled()) {
            s.sendMessage("§c期权市场暂未启用");
            return;
        }
        s.sendMessage("§e===== 期权市场 · 标的与结算价（方案A） =====");
        for (Commodity c : plugin.futuresCommodities()) {
            Long anchor = options.settlementPrice(c.key());
            s.sendMessage("§6" + c.key() + "§7: 结算价 §a" + (anchor == null ? "§c无锚（禁止挂卖）" : fmt(anchor))
                    + " §7（" + options.settlementSource(c.key()) + "）");
        }
        s.sendMessage("§7可选期限（游戏日）: " + plugin.futuresTerms());
        s.sendMessage("§7开仓: /ct opt open <call|put> <品种> <行权价> <权利金> <期限>"
                + "（开仓需托管保证金=行权价）");
        s.sendMessage("§7看不懂期权？输入 §6/ct opt help§7 查看通俗指南");
    }

    private void optOpen(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 7) {
            p.sendMessage("§c用法: /ct opt open <call|put> <品种> <行权价> <权利金> <期限>");
            return;
        }
        String type = args[2];
        String key = args[3];
        long strike;
        long premium;
        int term;
        try {
            strike = Long.parseLong(args[4]);
            premium = Long.parseLong(args[5]);
            term = Integer.parseInt(args[6]);
        } catch (NumberFormatException ex) {
            p.sendMessage("§c行权价/权利金/期限必须是整数");
            return;
        }
        OptionsService.OpenResult r = options.validateOpen(p, type, key, strike, premium, term);
        switch (r) {
            case SUCCESS -> {
                OptionContract c = options.open(p, type, key, strike, premium, term);
                if (c == null) {
                    p.sendMessage("§c开仓失败（余额变化），请重试");
                } else {
                    p.sendMessage("§a开仓成功！" + c.type + " #" + c.id + " · " + key + " · 行权价 §e" + fmt(strike)
                            + " §a· 权利金 §e" + fmt(premium) + " §a· 期限 " + term + " 游戏日"
                            + "§7（保证金 " + fmt(strike) + " 已托管，未成交前可撤单）");
                }
            }
            case FROZEN -> p.sendMessage("§c账户已被冻结");
            case INSUFFICIENT_FUNDS -> p.sendMessage("§c银行余额不足以托管保证金（需 " + fmt(strike) + " 绿宝石）");
            case INVALID_TYPE -> p.sendMessage("§c类型必须是 call（看涨）或 put（看跌）");
            case INVALID_COMMODITY -> p.sendMessage("§c未知品种，见 /ct opt info");
            case NO_ANCHOR -> p.sendMessage("§c该品种暂无结算价锚（无期货成交且无参考价），禁止挂卖");
            case INVALID_STRIKE -> p.sendMessage("§c行权价必须大于 0");
            case INVALID_PREMIUM -> p.sendMessage("§c权利金必须大于 0");
            case INVALID_TERM -> p.sendMessage("§c期限必须为（游戏日）: " + plugin.futuresTerms());
            case DISABLED -> p.sendMessage("§c期权市场暂未启用");
        }
    }

    private void optMy(CommandSender sender) {
        if (requirePlayer(sender)) {
            guis.openOptMy((Player) sender);
        }
    }

    private void optCancel(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 3) {
            p.sendMessage("§c用法: /ct opt cancel <编号>");
            return;
        }
        long id;
        try {
            id = Long.parseLong(args[2]);
        } catch (NumberFormatException ex) {
            p.sendMessage("§c编号无效");
            return;
        }
        OptionsService.CancelResult r = options.cancel(p, id);
        switch (r) {
            case SUCCESS -> p.sendMessage("§a已撤单，保证金已退还银行账户");
            case NOT_FOUND -> p.sendMessage("§c合约不存在");
            case NOT_ACTIVE -> p.sendMessage("§c该期权已成交或已撤销，无法撤单");
            case NOT_OWNER -> p.sendMessage("§c这不是你的合约");
        }
    }

    private void optAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cycletrading.admin")) {
            sender.sendMessage("§c权限不足");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§c用法: /ct opt admin <stats|settle|cancel> [编号]");
            return;
        }
        switch (args[2].toLowerCase()) {
            case "stats" -> sender.sendMessage("§e期权市场: §a" + options.countByStatus(OptionContract.OPEN)
                    + " §7挂单 · §a" + options.countByStatus(OptionContract.LOCKED)
                    + " §7锁定待结算 · §a" + options.countByStatus(OptionContract.SETTLED) + " §7已结算");
            case "settle", "cancel" -> {
                if (args.length < 4) {
                    sender.sendMessage("§c用法: /ct opt admin " + args[2] + " <编号>");
                    return;
                }
                long id;
                try {
                    id = Long.parseLong(args[3]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage("§c编号无效");
                    return;
                }
                boolean ok = args[2].equalsIgnoreCase("settle") ? options.adminSettle(id) : options.adminCancel(id);
                sender.sendMessage(ok ? "§a操作成功" : "§c合约不存在或状态不允许该操作");
            }
            default -> sender.sendMessage("§c用法: /ct opt admin <stats|settle|cancel> [编号]");
        }
    }

    // ---------- 投资金条（国库股） ----------

    private void goldCmd(CommandSender sender, String[] args) {
        if (args.length < 2) {
            goldInfo(sender);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "info" -> goldInfo(sender);
            case "buy" -> goldTrade(sender, args, true);
            case "sell" -> goldTrade(sender, args, false);
            case "my" -> goldMy(sender);
            default -> goldInfo(sender);
        }
    }

    private void goldInfo(CommandSender s) {
        if (!plugin.goldEnabled()) {
            s.sendMessage("§c金条市场暂未启用");
            return;
        }
        s.sendMessage("§e===== 投资金条（国库股） =====");
        s.sendMessage("§6当前价: §a" + fmt(gold.price()) + " 绿宝石/根");
        s.sendMessage("§7恒定发行: " + fmt(gold.total()) + " 根 · 在外: " + fmt(gold.outstanding())
                + " · 国库余额: " + fmt(gold.treasury()) + " 绿宝石");
        s.sendMessage("§7准备金占用: " + fmt(gold.reserved()) + " · 可自由支配: " + fmt(gold.freeTreasury()));
        s.sendMessage("§7价格 = 国库余额 ÷ 发行量 · 买=资金入国库（价涨）· 卖=国库付款（价跌）");
        s.sendMessage("§7买入: /ct gold buy <数量> · 卖出: /ct gold sell <数量>（仅虚拟余额）");
    }

    private void goldTrade(CommandSender sender, String[] args, boolean buy) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 3) {
            p.sendMessage("§c用法: /ct gold " + (buy ? "buy" : "sell") + " <数量>");
            return;
        }
        long qty;
        try {
            qty = Long.parseLong(args[2]);
        } catch (NumberFormatException ex) {
            p.sendMessage("§c数量必须是整数");
            return;
        }
        GoldService.TradeResult r = buy ? gold.buy(p, qty) : gold.sell(p, qty);
        switch (r) {
            case SUCCESS -> p.sendMessage("§a" + (buy ? "买入" : "卖出") + " §e" + fmt(qty)
                    + " §a根金条 · 成交价 §e" + fmt(gold.price()) + " 绿宝石/根"
                    + " · 当前持仓 §e" + fmt(gold.held(p.getUniqueId().toString())) + " 根");
            case FROZEN -> p.sendMessage("§c账户已被冻结");
            case INSUFFICIENT_FUNDS -> p.sendMessage("§c银行余额不足（需要 " + fmt(gold.price() * qty) + " 绿宝石）");
            case INSUFFICIENT_BARS -> p.sendMessage("§c持仓不足");
            case INVALID_AMOUNT -> p.sendMessage("§c数量必须大于 0");
            case DISABLED -> p.sendMessage("§c金条市场暂未启用");
        }
    }

    private void goldMy(CommandSender sender) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        long held = gold.held(p.getUniqueId().toString());
        p.sendMessage("§e===== 我的金条 =====");
        p.sendMessage("§6持仓: §a" + fmt(held) + " §7根 · 市值约 §a" + fmt(held * gold.price()) + " §7绿宝石");
        p.sendMessage("§7卖出: /ct gold sell <数量>（按当前价即时成交）");
    }

    // ---------- 中央银行（控制台/管理员） ----------

    private void cbCmd(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cycletrading.admin")) {
            sender.sendMessage("§c权限不足");
            return;
        }
        if (args.length < 2) {
            cbReport(sender);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "report" -> cbReport(sender);
            case "distribute" -> cbDistribute(sender, args);
            case "grant" -> cbGrant(sender, args, false);
            case "tax" -> cbGrant(sender, args, true);
            case "anchor" -> cbAnchor(sender, args);
            default -> cbReport(sender);
        }
    }

    /** 经济公报。 */
    private void cbReport(CommandSender s) {
        long locked = bonds.totalLocked() + gold.reserved() + futures.lockedValue() + options.lockedValue();
        s.sendMessage("§e===== 中央银行经济公报 =====");
        s.sendMessage("§6总存量 M: §a" + fmt(bank.playerSupply()) + " 绿宝石");
        s.sendMessage("§6锁定资金: §a" + fmt(locked) + " §7(债券 " + fmt(bonds.totalLocked())
                + " + 金条准备金 " + fmt(gold.reserved()) + " + 期货 " + fmt(futures.lockedValue())
                + " + 期权 " + fmt(options.lockedValue()) + ")");
        s.sendMessage("§6国库: §a" + fmt(gold.treasury()) + " §7(准备金占用 " + fmt(gold.reserved())
                + " · 可支配 " + fmt(gold.freeTreasury()) + ")");
        s.sendMessage("§6金条: §a" + fmt(gold.price()) + " 绿宝石/根 §7(发行 " + fmt(gold.total())
                + " · 在外 " + fmt(gold.outstanding()) + ")");
        s.sendMessage("§6期货清算所: §a" + fmt(bank.balance(Bank.CLEARING)) + " §7(未平头寸敞口 "
                + fmt(futures.openExposure()) + ")");
        s.sendMessage("§6Lux 倍率: §a" + String.format("%.3f", luxury.multiplier()) + "×§7(锚点 "
                + fmt(plugin.luxurySupplyAnchor()) + (plugin.getLuxAnchorOverride() > 0 ? "，央行覆盖" : "") + ")");
        s.sendMessage("§6债券倍率: §a" + String.format("%.3f", bonds.rateMultiplier()) + "×§7(锚点 "
                + fmt(plugin.bondRateAnchor()) + (plugin.getBondAnchorOverride() > 0 ? "，央行覆盖" : "") + ")");
        s.sendMessage("§7成交税: " + plugin.taxPercent() + "% → 国库 · 央行工具: distribute/grant/tax/anchor");
    }

    private void cbDistribute(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§c用法: /ct cb distribute <每人金额>");
            return;
        }
        long amt;
        try {
            amt = Long.parseLong(args[2]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("§c金额必须是整数");
            return;
        }
        if (amt <= 0) {
            sender.sendMessage("§c金额必须大于 0");
            return;
        }
        List<BankAccount> accounts = bank.accountsSnapshot().stream()
                .filter(a -> !Bank.SYSTEM.equals(a.owner)).toList();
        if (accounts.isEmpty()) {
            sender.sendMessage("§c没有可发放的账户");
            return;
        }
        long total = amt * accounts.size();
        if (total > gold.freeTreasury()) {
            sender.sendMessage("§c国库可支配资金不足（需要 " + fmt(total) + "，可用 " + fmt(gold.freeTreasury()) + "）");
            return;
        }
        bank.debit(Bank.SYSTEM, total, TxEntry.CB_SPEND);
        for (BankAccount a : accounts) {
            bank.credit(a.owner, a.name, amt, TxEntry.CB_DISTRIBUTE);
        }
        sender.sendMessage("§a已向 " + accounts.size() + " 个账户人均发放 §e" + fmt(amt) + " 绿宝石"
                + "§7（国库支出 " + fmt(total) + "）");
    }

    private void cbGrant(CommandSender sender, String[] args, boolean isTax) {
        if (args.length < 4) {
            sender.sendMessage("§c用法: /ct cb " + (isTax ? "tax" : "grant") + " <玩家> <金额>");
            return;
        }
        long amt;
        try {
            amt = Long.parseLong(args[3]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("§c金额必须是整数");
            return;
        }
        if (amt <= 0) {
            sender.sendMessage("§c金额必须大于 0");
            return;
        }
        UUID target = resolveUuid(args[2]);
        if (target == null) {
            sender.sendMessage("§c找不到玩家 " + args[2]);
            return;
        }
        String uuid = target.toString();
        if (isTax) {
            if (bank.balance(uuid) < amt) {
                sender.sendMessage("§c对方余额不足");
                return;
            }
            bank.debit(uuid, amt, TxEntry.CB_TAX);
            bank.credit(Bank.SYSTEM, "SYSTEM", amt, TxEntry.CB_TAX);
            sender.sendMessage("§a已向 " + args[2] + " 征税 §e" + fmt(amt) + " 绿宝石§7（入国库）");
        } else {
            if (amt > gold.freeTreasury()) {
                sender.sendMessage("§c国库可支配资金不足（需要 " + fmt(amt) + "，可用 " + fmt(gold.freeTreasury()) + "）");
                return;
            }
            bank.debit(Bank.SYSTEM, amt, TxEntry.CB_SPEND);
            bank.credit(uuid, args[2], amt, TxEntry.CB_GRANT);
            sender.sendMessage("§a已向 " + args[2] + " 发放补贴 §e" + fmt(amt) + " 绿宝石§7（国库支出）");
        }
    }

    private void cbAnchor(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("§c用法: /ct cb anchor <lux|bond> <锚点>（0 = 恢复配置值）");
            return;
        }
        long v;
        try {
            v = Long.parseLong(args[3]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("§c锚点必须是整数");
            return;
        }
        if (v < 0) {
            sender.sendMessage("§c锚点必须 ≥ 0");
            return;
        }
        if (args[2].equalsIgnoreCase("lux")) {
            plugin.setLuxAnchorOverride(v);
            sender.sendMessage("§a央行利率决议：Lux 锚点 → §e" + (v == 0 ? "恢复配置（" + fmt(getConfigLuxAnchor()) + "）" : fmt(v))
                    + "§7 · 当前倍率 " + String.format("%.3f", luxury.multiplier()) + "×");
        } else if (args[2].equalsIgnoreCase("bond")) {
            plugin.setBondAnchorOverride(v);
            sender.sendMessage("§a央行利率决议：债券锚点 → §e" + (v == 0 ? "恢复配置（" + fmt(getConfigBondAnchor()) + "）" : fmt(v))
                    + "§7 · 当前倍率 " + String.format("%.3f", bonds.rateMultiplier()) + "×");
        } else {
            sender.sendMessage("§c目标必须是 lux 或 bond");
        }
    }

    private long getConfigLuxAnchor() {
        return plugin.getConfig().getLong("luxury.supply-anchor", 1000000L);
    }

    private long getConfigBondAnchor() {
        return plugin.getConfig().getLong("bond.rate-anchor", 1000000L);
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
            case "help" -> futHelp(sender);
            case "open" -> futOpen(sender, args);
            case "my" -> futMy(sender);
            case "cancel" -> futCancel(sender, args);
            case "long" -> futPosOpen(sender, args, true);
            case "short" -> futPosOpen(sender, args, false);
            case "pos" -> futPos(sender);
            case "close" -> futPosClose(sender, args);
            case "admin" -> futAdmin(sender, args);
            default -> sender.sendMessage("§c用法: /ct fut [页] | help | info | open <价格> <期限> | long|short <品种> <数量> <期限> | pos | close <编号> | my | cancel <编号>");
        }
    }

    // ---------- 多空头寸 ----------

    private void futPosOpen(CommandSender sender, String[] args, boolean isLong) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 5) {
            p.sendMessage("§c用法: /ct fut " + (isLong ? "long" : "short") + " <品种> <数量> <期限>");
            return;
        }
        String key = args[2];
        long qty;
        int term;
        try {
            qty = Long.parseLong(args[3]);
            term = Integer.parseInt(args[4]);
        } catch (NumberFormatException ex) {
            p.sendMessage("§c数量/期限必须是整数");
            return;
        }
        FuturesService.PosOpenResult r = futures.validateOpenPos(p, isLong ? "LONG" : "SHORT", key, qty, term);
        switch (r) {
            case SUCCESS -> {
                FuturesPosition pos = futures.openPos(p, isLong ? "LONG" : "SHORT", key, qty, term);
                if (pos == null) {
                    p.sendMessage("§c开仓失败（余额变化），请重试");
                } else {
                    p.sendMessage("§a开" + (isLong ? "多" : "空") + "成功！#" + pos.id + " · " + key + " ×" + qty
                            + " · 入场价 §e" + fmt(pos.entry) + " §a· 保证金 §e" + fmt(pos.margin)
                            + " §a· 期限 " + term + " 游戏日" + "§7（/ct fut pos 查看，/ct fut close 提前平仓）");
                }
            }
            case FROZEN -> p.sendMessage("§c账户已被冻结");
            case INSUFFICIENT_FUNDS -> p.sendMessage("§c银行余额不足以支付保证金（入场价×数量）");
            case INVALID_TYPE -> p.sendMessage("§c类型错误");
            case INVALID_COMMODITY -> p.sendMessage("§c未知品种，见 /ct fut info");
            case NO_ANCHOR -> p.sendMessage("§c该品种暂无结算价锚，无法开仓");
            case INVALID_QTY -> p.sendMessage("§c数量必须大于 0");
            case INVALID_TERM -> p.sendMessage("§c期限必须为（游戏日）: " + plugin.futuresTerms());
            case DISABLED -> p.sendMessage("§c期货市场暂未启用");
        }
    }

    private void futPos(CommandSender sender) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        List<FuturesPosition> list = futures.positionsOf(p.getUniqueId().toString());
        p.sendMessage("§e===== 我的期货头寸 =====");
        if (list.isEmpty()) {
            p.sendMessage("§7暂无头寸。开仓: /ct fut long|short <品种> <数量> <期限>");
            return;
        }
        for (FuturesPosition pos : list) {
            if (pos.isOpen()) {
                Long s = futures.anchorOf(pos.commodity);
                long unrealized = pos.isLong() ? (s - pos.entry) * pos.qty : (pos.entry - s) * pos.qty;
                p.sendMessage("§6#" + pos.id + " " + (pos.isLong() ? "多" : "空") + " " + pos.commodity
                        + " ×" + pos.qty + " §7· 入场 " + fmt(pos.entry) + " · 现锚 " + fmt(s)
                        + " · 浮盈 §e" + (unrealized >= 0 ? "+" : "") + fmt(unrealized)
                        + " §7· 剩余 " + futures.posDaysLeft(pos) + " 游戏日");
            } else {
                p.sendMessage("§7#" + pos.id + " 已结算 · 结算价 " + fmt(pos.settlementPrice)
                        + " · 盈亏 §e" + (pos.pnl >= 0 ? "+" : "") + fmt(pos.pnl) + " · 实付 " + fmt(pos.payout));
            }
        }
        p.sendMessage("§7提前平仓: /ct fut close <编号>（按当前锚即时结算）");
    }

    private void futPosClose(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 3) {
            p.sendMessage("§c用法: /ct fut close <编号>");
            return;
        }
        long id;
        try {
            id = Long.parseLong(args[2]);
        } catch (NumberFormatException ex) {
            p.sendMessage("§c编号无效");
            return;
        }
        FuturesService.PosCloseResult r = futures.closePos(p, id);
        switch (r) {
            case SUCCESS -> p.sendMessage("§a已平仓，本息已按当前结算价入账银行");
            case NOT_FOUND -> p.sendMessage("§c头寸不存在");
            case NOT_ACTIVE -> p.sendMessage("§c该头寸已结算");
            case NOT_OWNER -> p.sendMessage("§c这不是你的头寸");
        }
    }

    /** 期货通俗指南（给不熟悉期货的玩家）。 */
    private void futHelp(CommandSender s) {
        s.sendMessage("§e===== 期货交易 · 通俗指南 =====");
        s.sendMessage("§7期货就是【大宗整批货的\"先订后交\"】：");
        s.sendMessage("§71. §f卖方§7把正好一批标准货交给系统托管、标好价、约好交货日期");
        s.sendMessage("§72. §f买方§7先付全款锁定，到期系统自动一手交货、一手付钱");
        s.sendMessage("§73. §f双方都先交全款/全货§7（全额保证金）→ 不会跑单、不会诈骗");
        s.sendMessage("§74. §f到期自动交割§7：货进买方邮箱，钱进卖方银行，人不在线也没事");
        s.sendMessage("§7--- 卖方三步 ---");
        s.sendMessage("§7凑货(如640橡木原木) → §6/ct fut open <价格> <期限>§7 → 等买家或 §6/ct fut cancel <编号>§7 撤单");
        s.sendMessage("§7--- 买方三步 ---");
        s.sendMessage("§6/ct fut§7 逛市场 → 点合约确认付款 → 到期后 §6/ct mail§7 领取货物");
        s.sendMessage("§7--- 规则 ---");
        s.sendMessage("§7· 成交后不能反悔（定期合同）；没卖出去之前卖方可随时撤单");
        s.sendMessage("§7· 交货需要邮箱空格（下单前系统自动检查，不够会提醒你）");
        s.sendMessage("§7· 标准货品种类与数量见 §6/ct fut info");
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
        s.sendMessage("§7看不懂期货？输入 §6/ct fut help§7 查看通俗指南");
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
                    + " §7锁定待交割 · §a" + futures.countByStatus(FuturesContract.DELIVERED) + " §7已交割"
                    + " §7| 头寸 §a" + futures.posCountByStatus(FuturesPosition.OPEN)
                    + " §7未平 · 清算所 §a" + fmt(bank.balance(Bank.CLEARING))
                    + " §7· 敞口 §a" + fmt(futures.openExposure()));
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
            return List.of("market", "sell", "my", "collect", "mail", "bank", "lux", "bond", "fut", "opt", "gold", "help");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("gold")) {
            return List.of("info", "buy", "sell", "my");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cb")) {
            return List.of("report", "distribute", "grant", "tax", "anchor");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("cb") && args[1].equalsIgnoreCase("anchor")) {
            return List.of("lux", "bond");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("fut")) {
            return List.of("help", "info", "open", "my", "cancel", "long", "short", "pos", "close", "admin");
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
            return List.of("deposit", "withdraw", "send", "ledger", "admin");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("bank") && args[1].equalsIgnoreCase("admin")) {
            return List.of("view", "set", "add", "remove", "freeze", "unfreeze", "ledger");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("lux")) {
            return List.of("status", "list", "remove");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("opt")) {
            return List.of("help", "info", "open", "my", "cancel", "admin");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("opt") && args[1].equalsIgnoreCase("open")) {
            return List.of("call", "put");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("opt") && args[1].equalsIgnoreCase("admin")) {
            return List.of("stats", "settle", "cancel");
        }
        return List.of();
    }
}
