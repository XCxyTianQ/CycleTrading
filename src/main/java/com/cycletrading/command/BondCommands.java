package com.cycletrading.command;

import com.cycletrading.CycleTradingPlugin;
import com.cycletrading.core.bond.Bond;
import com.cycletrading.core.bond.BondService;
import com.cycletrading.gui.GuiManager;
import com.cycletrading.util.Money;
import java.util.List;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** 定期债券命令：/ct bond ... */
public final class BondCommands {

    private final CycleTradingPlugin plugin;
    private final BondService bonds;
    private final GuiManager guis;

    public BondCommands(CycleTradingPlugin plugin, BondService bonds, GuiManager guis) {
        this.plugin = plugin;
        this.bonds = bonds;
        this.guis = guis;
    }

    public void bond(CommandSender sender, String[] args) {
        if (args.length < 2) {
            if (Cmd.requirePlayer(sender)) {
                guis.openBonds((Player) sender);
            }
            return;
        }
        switch (args[1].toLowerCase()) {
            case "info" -> info(sender);
            case "buy" -> buy(sender, args);
            case "admin" -> admin(sender, args);
            default -> {
                if (Cmd.requirePlayer(sender)) {
                    guis.openBonds((Player) sender);
                }
            }
        }
    }

    private void info(CommandSender s) {
        if (!plugin.bondEnabled()) {
            s.sendMessage("§c定期债券暂未启用");
            return;
        }
        s.sendMessage("§e===== 定期债券（仅虚拟绿宝石，按游戏日计息，到期自动结算）=====");
        s.sendMessage("§7总存量: §a" + Money.fmt(plugin.bank().playerSupply()) + " §7· 利率倍率: §a"
                + Money.fmtMultiplier(bonds.rateMultiplier()) + "§7（上限 " + plugin.bondMaxMultiplier() + "×）");
        for (int t = 1; t <= BondService.TIERS; t++) {
            s.sendMessage("§6档位" + t + ": " + plugin.bondDays(t) + "游戏日 · 基础利率 " + plugin.bondBaseRate(t)
                    + "% · 实际利率 §a" + BondService.fmtRate(bonds.currentRateBp(t))
                    + "§7 · 最低购买 " + Money.fmt(plugin.bondMin(t)) + " 绿宝石");
        }
        s.sendMessage("§7购买: /ct bond buy <档位> <金额>  ·  利率购买后锁定 · 利息退一法取整");
    }

    private void buy(CommandSender sender, String[] args) {
        if (!Cmd.requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 4) {
            p.sendMessage("§c用法: /ct bond buy <档位1-5> <金额>");
            return;
        }
        Integer tier = Cmd.parseInt(args[2]);
        Long amount = Cmd.parseLong(args[3]);
        if (tier == null || amount == null) {
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
                    p.sendMessage("§a购买成功！档位" + tier + " 定期债券 · 本金 §e" + Money.fmt(amount)
                            + " §a绿宝石 · 锁定利率 §e" + BondService.fmtRate(b.rateBp)
                            + "§a · 期限 " + plugin.bondDays(tier) + " 游戏日，到期本息自动入账");
                }
            }
            case FROZEN -> p.sendMessage("§c账户已被冻结，无法购买");
            case INSUFFICIENT_FUNDS -> p.sendMessage("§c银行余额不足");
            case INVALID_TIER -> p.sendMessage("§c档位无效，请输入 1-" + BondService.TIERS);
            case INVALID_AMOUNT -> p.sendMessage("§c金额必须大于 0");
            case BELOW_MINIMUM -> p.sendMessage("§c低于该档最低购买量（" + Money.fmt(plugin.bondMin(tier)) + " 绿宝石）");
            case DISABLED -> p.sendMessage("§c定期债券暂未启用");
        }
    }

    private void admin(CommandSender sender, String[] args) {
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
                    + Money.fmt(bonds.totalLocked()) + " §7绿宝石 · 利率倍率 §a"
                    + Money.fmtMultiplier(bonds.rateMultiplier()) + "×");
            return;
        }
        if (args[2].equalsIgnoreCase("view") && args.length >= 4) {
            UUID target = Cmd.resolveUuid(plugin, args[3]);
            List<Bond> act = bonds.activeBondsOf(target.toString());
            if (act.isEmpty()) {
                sender.sendMessage("§7" + args[3] + " 暂无在持债券");
            } else {
                sender.sendMessage("§e" + args[3] + " §7在持债券:");
                for (Bond b : act) {
                    sender.sendMessage("§7#" + b.id + " 档位" + b.tier + "(" + plugin.bondDays(b.tier) + "游戏日)"
                            + " · 本金 " + Money.fmt(b.principal) + " · 利率 " + BondService.fmtRate(b.rateBp)
                            + " · 剩余约 " + bonds.daysLeft(b) + " 游戏日");
                }
            }
            return;
        }
        sender.sendMessage("§c用法: /ct bond admin <stats|view> [玩家]");
    }

    public List<String> complete(String[] args) {
        if (args.length == 2) {
            return List.of("info", "buy", "admin");
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("buy")) {
            return List.of("1", "2", "3", "4", "5");
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("admin")) {
            return List.of("stats", "view");
        }
        return List.of();
    }
}
