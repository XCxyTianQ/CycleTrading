package com.cycletrading.command;

import com.cycletrading.CycleTradingPlugin;
import com.cycletrading.core.bank.Bank;
import com.cycletrading.core.bank.BankAccount;
import com.cycletrading.core.bank.TxEntry;
import com.cycletrading.core.bond.BondService;
import com.cycletrading.core.futures.FuturesService;
import com.cycletrading.core.gold.GoldService;
import com.cycletrading.core.luxury.LuxuryMarket;
import com.cycletrading.util.Money;
import java.util.List;
import java.util.UUID;
import org.bukkit.command.CommandSender;

/** 中央银行命令：/ct cb ...（report / distribute / grant / tax / anchor）。 */
public final class CbCommands {

    private final CycleTradingPlugin plugin;
    private final Bank bank;
    private final LuxuryMarket luxury;
    private final BondService bonds;
    private final FuturesService futures;
    private final GoldService gold;

    public CbCommands(CycleTradingPlugin plugin, Bank bank, LuxuryMarket luxury,
            BondService bonds, FuturesService futures, GoldService gold) {
        this.plugin = plugin;
        this.bank = bank;
        this.luxury = luxury;
        this.bonds = bonds;
        this.futures = futures;
        this.gold = gold;
    }

    public void cb(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cycletrading.admin")) {
            sender.sendMessage("§c权限不足");
            return;
        }
        if (args.length < 2) {
            report(sender);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "report" -> report(sender);
            case "distribute" -> distribute(sender, args);
            case "grant" -> grant(sender, args, false);
            case "tax" -> grant(sender, args, true);
            case "anchor" -> anchor(sender, args);
            default -> report(sender);
        }
    }

    public void report(CommandSender s) {
        long locked = bonds.totalLocked() + gold.reserved() + futures.lockedValue() + plugin.options().lockedValue();
        s.sendMessage("§e===== 中央银行经济公报 =====");
        s.sendMessage("§6总存量 M: §a" + Money.fmt(bank.playerSupply()) + " 绿宝石");
        s.sendMessage("§6锁定资金: §a" + Money.fmt(locked) + " §7(债券 " + Money.fmt(bonds.totalLocked())
                + " + 金条准备金 " + Money.fmt(gold.reserved()) + " + 期货 " + Money.fmt(futures.lockedValue())
                + " + 期权 " + Money.fmt(plugin.options().lockedValue()) + ")");
        s.sendMessage("§6国库: §a" + Money.fmt(gold.treasury()) + " §7(准备金占用 " + Money.fmt(gold.reserved())
                + " · 可支配 " + Money.fmt(gold.freeTreasury()) + ")");
        s.sendMessage("§6金条: §a" + Money.fmt(gold.price()) + " 绿宝石/根 §7(发行 " + Money.fmt(gold.total())
                + " · 在外 " + Money.fmt(gold.outstanding()) + ")");
        s.sendMessage("§6期货清算所: §a" + Money.fmt(bank.balance(Bank.CLEARING)) + " §7(未平头寸敞口 "
                + Money.fmt(futures.openExposure()) + ")");
        s.sendMessage("§6Lux 倍率: §a" + Money.fmtMultiplier(luxury.multiplier()) + "§7(锚点 "
                + Money.fmt(plugin.luxurySupplyAnchor()) + (plugin.getLuxAnchorOverride() > 0 ? "，央行覆盖" : "") + ")");
        s.sendMessage("§6债券倍率: §a" + Money.fmtMultiplier(bonds.rateMultiplier()) + "§7(锚点 "
                + Money.fmt(plugin.bondRateAnchor()) + (plugin.getBondAnchorOverride() > 0 ? "，央行覆盖" : "") + ")");
        s.sendMessage("§7成交税: " + plugin.taxPercent() + "% → 国库 · 央行工具: distribute/grant/tax/anchor");
    }

    private void distribute(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§c用法: /ct cb distribute <每人金额>");
            return;
        }
        Long amt = Cmd.parseLong(args[2]);
        if (amt == null || amt <= 0) {
            sender.sendMessage("§c金额必须是大于 0 的整数");
            return;
        }
        List<BankAccount> accounts = bank.accountsSnapshot().stream()
                .filter(a -> !Bank.SYSTEM.equals(a.owner) && !Bank.CLEARING.equals(a.owner)).toList();
        if (accounts.isEmpty()) {
            sender.sendMessage("§c没有可发放的账户");
            return;
        }
        long total = amt * accounts.size();
        if (total > gold.freeTreasury()) {
            sender.sendMessage("§c国库可支配资金不足（需要 " + Money.fmt(total) + "，可用 " + Money.fmt(gold.freeTreasury()) + "）");
            return;
        }
        bank.debit(Bank.SYSTEM, total, TxEntry.CB_SPEND);
        for (BankAccount a : accounts) {
            bank.credit(a.owner, a.name, amt, TxEntry.CB_DISTRIBUTE);
        }
        sender.sendMessage("§a已向 " + accounts.size() + " 个账户人均发放 §e" + Money.fmt(amt) + " 绿宝石"
                + "§7（国库支出 " + Money.fmt(total) + "）");
    }

    private void grant(CommandSender sender, String[] args, boolean isTax) {
        if (args.length < 4) {
            sender.sendMessage("§c用法: /ct cb " + (isTax ? "tax" : "grant") + " <玩家> <金额>");
            return;
        }
        Long amt = Cmd.parseLong(args[3]);
        if (amt == null || amt <= 0) {
            sender.sendMessage("§c金额必须是大于 0 的整数");
            return;
        }
        UUID target = Cmd.resolveUuid(plugin, args[2]);
        String uuid = target.toString();
        if (isTax) {
            if (bank.balance(uuid) < amt) {
                sender.sendMessage("§c对方余额不足");
                return;
            }
            bank.debit(uuid, amt, TxEntry.CB_TAX);
            bank.credit(Bank.SYSTEM, "SYSTEM", amt, TxEntry.CB_TAX);
            sender.sendMessage("§a已向 " + args[2] + " 征税 §e" + Money.fmt(amt) + " 绿宝石§7（入国库）");
        } else {
            if (amt > gold.freeTreasury()) {
                sender.sendMessage("§c国库可支配资金不足（需要 " + Money.fmt(amt) + "，可用 " + Money.fmt(gold.freeTreasury()) + "）");
                return;
            }
            bank.debit(Bank.SYSTEM, amt, TxEntry.CB_SPEND);
            bank.credit(uuid, args[2], amt, TxEntry.CB_GRANT);
            sender.sendMessage("§a已向 " + args[2] + " 发放补贴 §e" + Money.fmt(amt) + " 绿宝石§7（国库支出）");
        }
    }

    private void anchor(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("§c用法: /ct cb anchor <lux|bond> <锚点>（0 = 恢复配置值）");
            return;
        }
        Long v = Cmd.parseLong(args[3]);
        if (v == null || v < 0) {
            sender.sendMessage("§c锚点必须是 ≥ 0 的整数");
            return;
        }
        if (args[2].equalsIgnoreCase("lux")) {
            plugin.setLuxAnchorOverride(v);
            sender.sendMessage("§a央行利率决议：Lux 锚点 → §e" + (v == 0 ? "恢复配置（" + Money.fmt(configLuxAnchor()) + "）" : Money.fmt(v))
                    + "§7 · 当前倍率 " + Money.fmtMultiplier(luxury.multiplier()) + "×");
        } else if (args[2].equalsIgnoreCase("bond")) {
            plugin.setBondAnchorOverride(v);
            sender.sendMessage("§a央行利率决议：债券锚点 → §e" + (v == 0 ? "恢复配置（" + Money.fmt(configBondAnchor()) + "）" : Money.fmt(v))
                    + "§7 · 当前倍率 " + Money.fmtMultiplier(bonds.rateMultiplier()) + "×");
        } else {
            sender.sendMessage("§c目标必须是 lux 或 bond");
        }
    }

    private long configLuxAnchor() {
        return plugin.getConfig().getLong("luxury.supply-anchor", 1000000L);
    }

    private long configBondAnchor() {
        return plugin.getConfig().getLong("bond.rate-anchor", 1000000L);
    }

    public List<String> complete(String[] args) {
        if (args.length == 2) {
            return List.of("report", "distribute", "grant", "tax", "anchor");
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("anchor")) {
            return List.of("lux", "bond");
        }
        return List.of();
    }
}
