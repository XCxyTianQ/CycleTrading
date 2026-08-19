package com.cycletrading.command;

import com.cycletrading.CycleTradingPlugin;
import com.cycletrading.core.Items;
import com.cycletrading.core.bank.Bank;
import com.cycletrading.core.bank.BankAccount;
import com.cycletrading.core.bank.TxEntry;
import com.cycletrading.util.Money;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** 银行命令：/ct bank ... */
public final class BankCommands {

    private final CycleTradingPlugin plugin;
    private final Bank bank;

    public BankCommands(CycleTradingPlugin plugin, Bank bank) {
        this.plugin = plugin;
        this.bank = bank;
    }

    public void bank(CommandSender sender, String[] args) {
        if (args.length < 2) {
            status(sender);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "deposit" -> deposit(sender, args);
            case "withdraw" -> withdraw(sender, args);
            case "send" -> send(sender, args);
            case "ledger" -> ledger(sender, args);
            case "admin" -> admin(sender, args);
            default -> status(sender);
        }
    }

    private void status(CommandSender sender) {
        if (!Cmd.requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        String uuid = p.getUniqueId().toString();
        BankAccount a = bank.find(uuid);
        p.sendMessage("§e===== 银行账户 =====");
        p.sendMessage("§6余额: §a" + Money.fmt(a == null ? 0 : a.balance) + " 绿宝石"
                + "  §7(背包实物: " + Money.fmt(Items.currencyCount(p)) + ")");
        if (a != null && a.frozen) {
            p.sendMessage("§c⚠ 账户已被冻结，禁止存取/转账/购买");
        }
        p.sendMessage("§7存款: /ct bank deposit [数量|all]  ·  取款: /ct bank withdraw <数量|all>");
        p.sendMessage("§7个人流水: /ct bank ledger [条数]");
    }

    private void ledger(CommandSender sender, String[] args) {
        if (!Cmd.requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        int n = 5;
        if (args.length > 2) {
            Integer x = Cmd.parseInt(args[2]);
            if (x != null) {
                n = Math.max(1, Math.min(20, x));
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
                    + " §e" + Money.fmt(t.amount) + " §7→ 余额 " + Money.fmt(t.balanceAfter));
        }
    }

    private void deposit(CommandSender sender, String[] args) {
        if (!Cmd.requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        int amount = -1;
        if (args.length < 3 || args[2].equalsIgnoreCase("all")) {
            amount = Items.currencyCount(p);
        } else {
            Integer x = Cmd.parseInt(args[2]);
            if (x == null) {
                p.sendMessage("§c数量必须是整数");
                return;
            }
            amount = x;
        }
        Bank.DepositResult r = bank.deposit(p, amount);
        switch (r) {
            case SUCCESS -> p.sendMessage("§a已存入 §e" + Money.fmt(amount) + " 绿宝石§a，当前余额 §e"
                    + Money.fmt(bank.balance(p.getUniqueId().toString())) + " 绿宝石");
            case FROZEN -> p.sendMessage("§c账户已被冻结，无法存款");
            case INSUFFICIENT_PHYSICAL -> p.sendMessage("§c背包里的绿宝石不足");
            case OVER_CAP -> p.sendMessage("§c超出账户余额上限（" + Money.fmt(plugin.bankMaxBalance()) + "）");
        }
    }

    private void withdraw(CommandSender sender, String[] args) {
        if (!Cmd.requirePlayer(sender)) {
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
            Long x = Cmd.parseLong(args[2]);
            if (x == null) {
                p.sendMessage("§c数量必须是整数");
                return;
            }
            amount = x;
        }
        long before = bank.balance(p.getUniqueId().toString());
        Bank.WithdrawResult r = bank.withdraw(p, amount, all);
        switch (r) {
            case SUCCESS -> {
                long got = before - bank.balance(p.getUniqueId().toString());
                p.sendMessage("§a已提取 §e" + Money.fmt(got) + " §a个绿宝石到背包"
                        + (got < (all ? before : amount) ? "§7（背包已满，剩余保留在银行）" : "")
                        + "，余额 §e" + Money.fmt(bank.balance(p.getUniqueId().toString())) + " 绿宝石");
            }
            case FROZEN -> p.sendMessage("§c账户已被冻结，无法取款");
            case INSUFFICIENT_BALANCE -> p.sendMessage("§c余额不足");
            case INVENTORY_FULL -> p.sendMessage("§c背包已满，无法提取");
        }
    }

    private void send(CommandSender sender, String[] args) {
        if (!Cmd.requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 4) {
            p.sendMessage("§c用法: /ct bank send <玩家> <数量>");
            return;
        }
        Long amount = Cmd.parseLong(args[3]);
        if (amount == null) {
            p.sendMessage("§c数量必须是整数");
            return;
        }
        UUID target = Cmd.resolveUuid(plugin, args[2]);
        Bank.TransferResult r = bank.send(p.getUniqueId().toString(), target.toString(), args[2], amount);
        switch (r) {
            case SUCCESS -> {
                p.sendMessage("§a已转账 §e" + Money.fmt(amount) + " 绿宝石§a给 §e" + args[2]);
                Player to = plugin.getServer().getPlayer(target);
                if (to != null) {
                    to.sendMessage("§a收到 §e" + p.getName() + " §a的转账 §e" + Money.fmt(amount) + " 绿宝石");
                }
            }
            case FROZEN -> p.sendMessage("§c账户已被冻结，无法转账");
            case SELF -> p.sendMessage("§c不能转账给自己");
            case INSUFFICIENT_BALANCE -> p.sendMessage("§c余额不足");
            case INVALID -> p.sendMessage("§c数量必须大于 0");
        }
    }

    private void admin(CommandSender sender, String[] args) {
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
        } else if (name.equalsIgnoreCase("CLEARING")) {
            uuid = Bank.CLEARING;
        } else {
            UUID target = Cmd.resolveUuid(plugin, name);
            uuid = target.toString();
        }
        if ((Bank.SYSTEM.equals(uuid) || Bank.CLEARING.equals(uuid))
                && (op.equals("set") || op.equals("add") || op.equals("remove") || op.equals("freeze") || op.equals("unfreeze"))) {
            sender.sendMessage("§c系统账户只读（view/ledger），不可修改");
            return;
        }
        switch (op) {
            case "view" -> {
                BankAccount a = bank.find(uuid);
                if (a == null) {
                    sender.sendMessage("§7" + name + " 尚无银行账户");
                } else {
                    sender.sendMessage("§e" + name + " §7余额: §a" + Money.fmt(a.balance) + " 绿宝石"
                            + (a.frozen ? " §c[已冻结]" : ""));
                }
            }
            case "set" -> {
                Long v = args.length > 4 ? Cmd.parseLong(args[4]) : null;
                if (v == null || v < 0) {
                    sender.sendMessage("§c金额无效");
                    return;
                }
                Bank.AdminResult r = bank.adminSet(uuid, name, v);
                sender.sendMessage(r == Bank.AdminResult.SUCCESS
                        ? "§a已将 " + name + " 的余额设为 §e" + Money.fmt(v) + " 绿宝石"
                        : r == Bank.AdminResult.OVER_CAP ? "§c超出余额上限" : "§c操作失败");
            }
            case "add", "remove" -> {
                Long v = args.length > 4 ? Cmd.parseLong(args[4]) : null;
                if (v == null || v <= 0) {
                    sender.sendMessage("§c金额必须大于 0");
                    return;
                }
                Bank.AdminResult r = op.equals("add") ? bank.adminAdd(uuid, name, v) : bank.adminRemove(uuid, name, v);
                sender.sendMessage(r == Bank.AdminResult.SUCCESS
                        ? "§a操作成功，" + name + " 当前余额 §e" + Money.fmt(bank.balance(uuid)) + " 绿宝石"
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
                    Integer x = Cmd.parseInt(args[4]);
                    if (x != null) {
                        n = x;
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
                                + " §e" + Money.fmt(t.amount) + " §7→ 余额 " + Money.fmt(t.balanceAfter));
                    }
                }
            }
            default -> sender.sendMessage("§c未知管理操作: " + op);
        }
    }

    public List<String> complete(String[] args) {
        if (args.length == 2) {
            return List.of("deposit", "withdraw", "send", "ledger", "admin");
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("admin")) {
            return List.of("view", "set", "add", "remove", "freeze", "unfreeze", "ledger");
        }
        return List.of();
    }
}
