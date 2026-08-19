package com.cycletrading.command;

import com.cycletrading.CycleTradingPlugin;
import com.cycletrading.core.gold.GoldService;
import com.cycletrading.util.Money;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** 投资金条命令：/ct gold ... */
public final class GoldCommands {

    private final CycleTradingPlugin plugin;
    private final GoldService gold;

    public GoldCommands(CycleTradingPlugin plugin, GoldService gold) {
        this.plugin = plugin;
        this.gold = gold;
    }

    public void gold(CommandSender sender, String[] args) {
        if (args.length < 2) {
            info(sender);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "info" -> info(sender);
            case "buy" -> trade(sender, args, true);
            case "sell" -> trade(sender, args, false);
            case "my" -> my(sender);
            default -> info(sender);
        }
    }

    private void info(CommandSender s) {
        if (!plugin.goldEnabled()) {
            s.sendMessage("§c金条市场暂未启用");
            return;
        }
        s.sendMessage("§e===== 投资金条（国库股） =====");
        s.sendMessage("§6当前价: §a" + Money.fmt(gold.price()) + " 绿宝石/根");
        s.sendMessage("§7恒定发行: " + Money.fmt(gold.total()) + " 根 · 在外: " + Money.fmt(gold.outstanding())
                + " · 国库余额: " + Money.fmt(gold.treasury()) + " 绿宝石");
        s.sendMessage("§7准备金占用: " + Money.fmt(gold.reserved()) + " · 可自由支配: " + Money.fmt(gold.freeTreasury()));
        s.sendMessage("§7价格 = 国库余额 ÷ 发行量 · 买=资金入国库（价涨）· 卖=国库付款（价跌）");
        s.sendMessage("§7买入: /ct gold buy <数量> · 卖出: /ct gold sell <数量>（仅虚拟余额）");
    }

    private void trade(CommandSender sender, String[] args, boolean buy) {
        if (!Cmd.requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 3) {
            p.sendMessage("§c用法: /ct gold " + (buy ? "buy" : "sell") + " <数量>");
            return;
        }
        Long qty = Cmd.parseLong(args[2]);
        if (qty == null) {
            p.sendMessage("§c数量必须是整数");
            return;
        }
        GoldService.TradeResult r = buy ? gold.buy(p, qty) : gold.sell(p, qty);
        switch (r) {
            case SUCCESS -> p.sendMessage("§a" + (buy ? "买入" : "卖出") + " §e" + Money.fmt(qty)
                    + " §a根金条 · 成交价 §e" + Money.fmt(gold.price()) + " 绿宝石/根"
                    + " · 当前持仓 §e" + Money.fmt(gold.held(p.getUniqueId().toString())) + " 根");
            case FROZEN -> p.sendMessage("§c账户已被冻结");
            case INSUFFICIENT_FUNDS -> p.sendMessage("§c银行余额不足（需要 " + Money.fmt(gold.price() * qty) + " 绿宝石）");
            case INSUFFICIENT_BARS -> p.sendMessage("§c持仓不足");
            case INVALID_AMOUNT -> p.sendMessage("§c数量必须大于 0");
            case DISABLED -> p.sendMessage("§c金条市场暂未启用");
        }
    }

    private void my(CommandSender sender) {
        if (!Cmd.requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        long held = gold.held(p.getUniqueId().toString());
        p.sendMessage("§e===== 我的金条 =====");
        p.sendMessage("§6持仓: §a" + Money.fmt(held) + " §7根 · 市值约 §a" + Money.fmt(held * gold.price()) + " §7绿宝石");
        p.sendMessage("§7卖出: /ct gold sell <数量>（按当前价即时成交）");
    }

    public List<String> complete(String[] args) {
        if (args.length == 2) {
            return List.of("info", "buy", "sell", "my");
        }
        return List.of();
    }
}
