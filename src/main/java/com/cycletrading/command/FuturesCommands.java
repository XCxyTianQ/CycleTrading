package com.cycletrading.command;

import com.cycletrading.CycleTradingPlugin;
import com.cycletrading.core.bank.Bank;
import com.cycletrading.core.futures.Commodity;
import com.cycletrading.core.futures.FuturesContract;
import com.cycletrading.core.futures.FuturesPosition;
import com.cycletrading.core.futures.FuturesService;
import com.cycletrading.gui.GuiManager;
import com.cycletrading.util.Money;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** 期货命令：/ct fut ...（实物交割 + 多空头寸）。 */
public final class FuturesCommands {

    private final CycleTradingPlugin plugin;
    private final FuturesService futures;
    private final Bank bank;
    private final GuiManager guis;

    public FuturesCommands(CycleTradingPlugin plugin, FuturesService futures, Bank bank, GuiManager guis) {
        this.plugin = plugin;
        this.futures = futures;
        this.bank = bank;
        this.guis = guis;
    }

    public void fut(CommandSender sender, String[] args) {
        if (args.length < 2) {
            if (Cmd.requirePlayer(sender)) {
                guis.openFut((Player) sender, 0);
            }
            return;
        }
        String a = args[1];
        if (a.matches("\\d+")) {
            if (Cmd.requirePlayer(sender)) {
                guis.openFut((Player) sender, Math.max(0, Integer.parseInt(a) - 1));
            }
            return;
        }
        switch (a.toLowerCase()) {
            case "info" -> info(sender);
            case "help" -> help(sender);
            case "open" -> open(sender, args);
            case "my" -> my(sender);
            case "cancel" -> cancel(sender, args);
            case "long" -> posOpen(sender, args, true);
            case "short" -> posOpen(sender, args, false);
            case "pos" -> pos(sender);
            case "close" -> posClose(sender, args);
            case "admin" -> admin(sender, args);
            default -> sender.sendMessage("§c用法: /ct fut [页] | help | info | open <价格> <期限> | long|short <品种> <数量> <期限> | pos | close <编号> | my | cancel <编号>");
        }
    }

    public void help(CommandSender s) {
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
        s.sendMessage("§7--- 多空单（看涨/看跌，保证金交易，不搬货） ---");
        s.sendMessage("§7§f多单 long = 看涨§7：交保证金(入场价×数量)锁仓，到期价涨就赚差价、价跌亏差价");
        s.sendMessage("§7§f空单 short = 看跌§7：到期价跌就赚差价、价涨亏差价");
        s.sendMessage("§7· 盈亏封顶 = 保证金 → 最坏亏光保证金，§f绝不会倒欠§7");
        s.sendMessage("§7· 开仓: §6/ct fut long|short <品种> <数量> <期限>§7（入场价 = 当前结算价）");
        s.sendMessage("§7· 持仓与浮盈: §6/ct fut pos§7 · 提前平仓: §6/ct fut close <编号>§7（按当前价即时结算）");
        s.sendMessage("§7· 到期自动按结算价（期货近期均价/参考价）结算入银行");
    }

    private void info(CommandSender s) {
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
        s.sendMessage("§7投机: /ct fut long|short <品种> <数量> <期限> 开多/空单 · /ct fut pos 持仓 · /ct fut close 平仓");
        s.sendMessage("§7看不懂期货？输入 §6/ct fut help§7 查看通俗指南");
    }

    private void open(CommandSender sender, String[] args) {
        if (!Cmd.requirePlayer(sender)) {
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
        Long price = Cmd.parseLong(args[2]);
        Integer term = Cmd.parseInt(args[3]);
        if (price == null || term == null) {
            p.sendMessage("§c价格/期限必须是整数");
            return;
        }
        if (price < plugin.minPrice() || price > plugin.maxPrice()) {
            p.sendMessage("§c价格超出允许范围 [" + Money.fmt(plugin.minPrice()) + ", " + Money.fmt(plugin.maxPrice()) + "]");
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
                + " · 价格 §e" + Money.fmt(price) + " 绿宝石§a · 交割期限 " + term + " 游戏日"
                + "§7（商品已托管，未成交前可 /ct fut cancel " + c.id + " 撤单）");
    }

    private void my(CommandSender sender) {
        if (Cmd.requirePlayer(sender)) {
            guis.openFutMy((Player) sender);
        }
    }

    private void cancel(CommandSender sender, String[] args) {
        if (!Cmd.requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 3) {
            p.sendMessage("§c用法: /ct fut cancel <编号>");
            return;
        }
        Long id = Cmd.parseLong(args[2]);
        if (id == null) {
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

    private void posOpen(CommandSender sender, String[] args, boolean isLong) {
        if (!Cmd.requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 5) {
            p.sendMessage("§c用法: /ct fut " + (isLong ? "long" : "short") + " <品种> <数量> <期限>");
            return;
        }
        Long qty = Cmd.parseLong(args[3]);
        Integer term = Cmd.parseInt(args[4]);
        if (qty == null || term == null) {
            p.sendMessage("§c数量/期限必须是整数");
            return;
        }
        FuturesService.PosOpenResult r = futures.validateOpenPos(p, isLong ? "LONG" : "SHORT", args[2], qty, term);
        switch (r) {
            case SUCCESS -> {
                FuturesPosition pos = futures.openPos(p, isLong ? "LONG" : "SHORT", args[2], qty, term);
                if (pos == null) {
                    p.sendMessage("§c开仓失败（余额变化），请重试");
                } else {
                    p.sendMessage("§a开" + (isLong ? "多" : "空") + "成功！#" + pos.id + " · " + args[2] + " ×" + qty
                            + " · 入场价 §e" + Money.fmt(pos.entry) + " §a· 保证金 §e" + Money.fmt(pos.margin)
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

    private void pos(CommandSender sender) {
        if (!Cmd.requirePlayer(sender)) {
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
                        + " ×" + pos.qty + " §7· 入场 " + Money.fmt(pos.entry) + " · 现锚 " + Money.fmt(s)
                        + " · 浮盈 §e" + (unrealized >= 0 ? "+" : "") + Money.fmt(unrealized)
                        + " §7· 剩余 " + futures.posDaysLeft(pos) + " 游戏日");
            } else {
                p.sendMessage("§7#" + pos.id + " 已结算 · 结算价 " + Money.fmt(pos.settlementPrice)
                        + " · 盈亏 §e" + (pos.pnl >= 0 ? "+" : "") + Money.fmt(pos.pnl) + " · 实付 " + Money.fmt(pos.payout));
            }
        }
        p.sendMessage("§7提前平仓: /ct fut close <编号>（按当前锚即时结算）");
    }

    private void posClose(CommandSender sender, String[] args) {
        if (!Cmd.requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 3) {
            p.sendMessage("§c用法: /ct fut close <编号>");
            return;
        }
        Long id = Cmd.parseLong(args[2]);
        if (id == null) {
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

    private void admin(CommandSender sender, String[] args) {
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
                    + " §7未平 · 清算所 §a" + Money.fmt(bank.balance(Bank.CLEARING))
                    + " §7· 敞口 §a" + Money.fmt(futures.openExposure()));
            case "deliver", "cancel" -> {
                if (args.length < 4) {
                    sender.sendMessage("§c用法: /ct fut admin " + args[2] + " <编号>");
                    return;
                }
                Long id = Cmd.parseLong(args[3]);
                if (id == null) {
                    sender.sendMessage("§c编号无效");
                    return;
                }
                boolean ok = args[2].equalsIgnoreCase("deliver") ? futures.adminDeliver(id) : futures.adminCancel(id);
                sender.sendMessage(ok ? "§a操作成功" : "§c合约不存在或状态不允许该操作");
            }
            default -> sender.sendMessage("§c用法: /ct fut admin <stats|deliver|cancel> [编号]");
        }
    }

    public List<String> complete(String[] args) {
        if (args.length == 2) {
            return List.of("help", "info", "open", "my", "cancel", "long", "short", "pos", "close", "admin");
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("admin")) {
            return List.of("stats", "deliver", "cancel");
        }
        if (args.length == 3 && (args[1].equalsIgnoreCase("long") || args[1].equalsIgnoreCase("short"))) {
            return plugin.futuresCommodities().stream().map(Commodity::key).toList();
        }
        return List.of();
    }
}
