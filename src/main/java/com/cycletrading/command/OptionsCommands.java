package com.cycletrading.command;

import com.cycletrading.CycleTradingPlugin;
import com.cycletrading.core.bank.Bank;
import com.cycletrading.core.futures.Commodity;
import com.cycletrading.core.options.OptionContract;
import com.cycletrading.core.options.OptionsService;
import com.cycletrading.gui.GuiManager;
import com.cycletrading.util.Money;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** 期权命令：/ct opt ... */
public final class OptionsCommands {

    private final CycleTradingPlugin plugin;
    private final OptionsService options;
    private final Bank bank;
    private final GuiManager guis;

    public OptionsCommands(CycleTradingPlugin plugin, OptionsService options, Bank bank, GuiManager guis) {
        this.plugin = plugin;
        this.options = options;
        this.bank = bank;
        this.guis = guis;
    }

    public void opt(CommandSender sender, String[] args) {
        if (args.length < 2) {
            if (Cmd.requirePlayer(sender)) {
                guis.openOpt((Player) sender, 0);
            }
            return;
        }
        String a = args[1];
        if (a.matches("\\d+")) {
            if (Cmd.requirePlayer(sender)) {
                guis.openOpt((Player) sender, Math.max(0, Integer.parseInt(a) - 1));
            }
            return;
        }
        switch (a.toLowerCase()) {
            case "help" -> help(sender);
            case "info" -> info(sender);
            case "open" -> open(sender, args);
            case "my" -> my(sender);
            case "cancel" -> cancel(sender, args);
            case "admin" -> admin(sender, args);
            default -> sender.sendMessage("§c用法: /ct opt [页] | help | info | open <call|put> <品种> <行权价> <权利金> <期限> | my | cancel <编号>");
        }
    }

    public void help(CommandSender s) {
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

    private void info(CommandSender s) {
        if (!plugin.optionsEnabled()) {
            s.sendMessage("§c期权市场暂未启用");
            return;
        }
        s.sendMessage("§e===== 期权市场 · 标的与结算价（方案A） =====");
        for (Commodity c : plugin.futuresCommodities()) {
            Long anchor = options.settlementPrice(c.key());
            s.sendMessage("§6" + c.key() + "§7: 结算价 §a" + (anchor == null ? "§c无锚（禁止挂卖）" : Money.fmt(anchor))
                    + " §7（" + options.settlementSource(c.key()) + "）");
        }
        s.sendMessage("§7可选期限（游戏日）: " + plugin.futuresTerms());
        s.sendMessage("§7开仓: /ct opt open <call|put> <品种> <行权价> <权利金> <期限>"
                + "（开仓需托管保证金=行权价）");
        s.sendMessage("§7看不懂期权？输入 §6/ct opt help§7 查看通俗指南");
    }

    private void open(CommandSender sender, String[] args) {
        if (!Cmd.requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 7) {
            p.sendMessage("§c用法: /ct opt open <call|put> <品种> <行权价> <权利金> <期限>");
            return;
        }
        Long strike = Cmd.parseLong(args[4]);
        Long premium = Cmd.parseLong(args[5]);
        Integer term = Cmd.parseInt(args[6]);
        if (strike == null || premium == null || term == null) {
            p.sendMessage("§c行权价/权利金/期限必须是整数");
            return;
        }
        OptionsService.OpenResult r = options.validateOpen(p, args[2], args[3], strike, premium, term);
        switch (r) {
            case SUCCESS -> {
                OptionContract c = options.open(p, args[2], args[3], strike, premium, term);
                if (c == null) {
                    p.sendMessage("§c开仓失败（余额变化），请重试");
                } else {
                    p.sendMessage("§a开仓成功！" + c.type + " #" + c.id + " · " + args[3] + " · 行权价 §e" + Money.fmt(strike)
                            + " §a· 权利金 §e" + Money.fmt(premium) + " §a· 期限 " + term + " 游戏日"
                            + "§7（保证金 " + Money.fmt(strike) + " 已托管，未成交前可撤单）");
                }
            }
            case FROZEN -> p.sendMessage("§c账户已被冻结");
            case INSUFFICIENT_FUNDS -> p.sendMessage("§c银行余额不足以托管保证金（需 " + Money.fmt(strike) + " 绿宝石）");
            case INVALID_TYPE -> p.sendMessage("§c类型必须是 call（看涨）或 put（看跌）");
            case INVALID_COMMODITY -> p.sendMessage("§c未知品种，见 /ct opt info");
            case NO_ANCHOR -> p.sendMessage("§c该品种暂无结算价锚（无期货成交且无参考价），禁止挂卖");
            case INVALID_STRIKE -> p.sendMessage("§c行权价必须大于 0");
            case INVALID_PREMIUM -> p.sendMessage("§c权利金必须大于 0");
            case INVALID_TERM -> p.sendMessage("§c期限必须为（游戏日）: " + plugin.futuresTerms());
            case DISABLED -> p.sendMessage("§c期权市场暂未启用");
        }
    }

    private void my(CommandSender sender) {
        if (Cmd.requirePlayer(sender)) {
            guis.openOptMy((Player) sender);
        }
    }

    private void cancel(CommandSender sender, String[] args) {
        if (!Cmd.requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 3) {
            p.sendMessage("§c用法: /ct opt cancel <编号>");
            return;
        }
        Long id = Cmd.parseLong(args[2]);
        if (id == null) {
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

    private void admin(CommandSender sender, String[] args) {
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
                Long id = Cmd.parseLong(args[3]);
                if (id == null) {
                    sender.sendMessage("§c编号无效");
                    return;
                }
                boolean ok = args[2].equalsIgnoreCase("settle") ? options.adminSettle(id) : options.adminCancel(id);
                sender.sendMessage(ok ? "§a操作成功" : "§c合约不存在或状态不允许该操作");
            }
            default -> sender.sendMessage("§c用法: /ct opt admin <stats|settle|cancel> [编号]");
        }
    }

    public List<String> complete(String[] args) {
        if (args.length == 2) {
            return List.of("help", "info", "open", "my", "cancel", "admin");
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("open")) {
            return List.of("call", "put");
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("admin")) {
            return List.of("stats", "settle", "cancel");
        }
        return List.of();
    }
}
