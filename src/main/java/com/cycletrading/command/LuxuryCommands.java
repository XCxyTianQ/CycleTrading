package com.cycletrading.command;

import com.cycletrading.CycleTradingPlugin;
import com.cycletrading.core.bank.Bank;
import com.cycletrading.core.luxury.LuxuryMarket;
import com.cycletrading.gui.GuiManager;
import com.cycletrading.util.Money;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** 奢侈品商店命令：/ct lux ... */
public final class LuxuryCommands {

    private final CycleTradingPlugin plugin;
    private final LuxuryMarket luxury;
    private final Bank bank;
    private final GuiManager guis;

    public LuxuryCommands(CycleTradingPlugin plugin, LuxuryMarket luxury, Bank bank, GuiManager guis) {
        this.plugin = plugin;
        this.luxury = luxury;
        this.bank = bank;
        this.guis = guis;
    }

    public void lux(CommandSender sender, String[] args) {
        if (args.length < 2) {
            if (Cmd.requirePlayer(sender)) {
                guis.openLux((Player) sender, 0);
            }
            return;
        }
        String a = args[1];
        if (a.matches("\\d+")) {
            if (Cmd.requirePlayer(sender)) {
                guis.openLux((Player) sender, Math.max(0, Integer.parseInt(a) - 1));
            }
            return;
        }
        switch (a.toLowerCase()) {
            case "status" -> status(sender);
            case "list" -> list(sender, args);
            case "remove" -> remove(sender, args);
            default -> sender.sendMessage("§c用法: /ct lux [页] | status | list <基础价> | remove <编号>");
        }
    }

    private void status(CommandSender s) {
        long supply = bank.playerSupply();
        s.sendMessage("§e===== 奢侈品商店 =====");
        s.sendMessage("§7经济总存量(全体玩家银行余额): §a" + Money.fmt(supply) + " 绿宝石");
        s.sendMessage("§7定价锚点: §a" + Money.fmt(plugin.luxurySupplyAnchor())
                + " §7· 当前倍率: §a" + Money.fmtMultiplier(luxury.multiplier())
                + " §7(上限 " + plugin.luxuryMaxMultiplier() + "×)");
        s.sendMessage("§7成交价 = 基础价 × 倍率（保底基础价），在售 " + luxury.activeNewestFirst().size() + " 件");
        if (s.hasPermission("cycletrading.admin")) {
            s.sendMessage("§7系统国库: §6" + Money.fmt(bank.balance(Bank.SYSTEM)) + " 绿宝石§7（金条准备金）");
        }
    }

    private void list(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cycletrading.admin")) {
            sender.sendMessage("§c权限不足：仅管理员可挂售奢侈品");
            return;
        }
        if (!Cmd.requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 3) {
            p.sendMessage("§c用法: /ct lux list <基础价>  （手持要挂售的珍稀物品）");
            return;
        }
        Long base = Cmd.parseLong(args[2]);
        if (base == null) {
            p.sendMessage("§c基础价必须是整数（单位：绿宝石）");
            return;
        }
        if (base < 1 || base > plugin.luxuryMaxBasePrice()) {
            p.sendMessage("§c基础价超出允许范围 [1, " + Money.fmt(plugin.luxuryMaxBasePrice()) + "]");
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR || hand.getAmount() <= 0) {
            p.sendMessage("§c请手持要挂售的珍稀物品再执行 /ct lux list <基础价>");
            return;
        }
        p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        long id = luxury.create(p.getName(), p.getUniqueId().toString(), hand, base).id;
        p.sendMessage("§6已挂售！编号 #" + id + " · 基础价 " + Money.fmt(base) + " 绿宝石"
                + " · 当前成交价 " + Money.fmt(luxury.effectivePrice(base)) + " 绿宝石"
                + "§7（用 /ct lux 查看商店）");
    }

    private void remove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cycletrading.admin")) {
            sender.sendMessage("§c权限不足");
            return;
        }
        if (!Cmd.requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 3) {
            p.sendMessage("§c用法: /ct lux remove <编号>");
            return;
        }
        Long id = Cmd.parseLong(args[2]);
        if (id == null) {
            p.sendMessage("§c编号无效");
            return;
        }
        guis.doLuxRemove(p, id);
    }

    public List<String> complete(String[] args) {
        if (args.length == 2) {
            return List.of("status", "list", "remove");
        }
        return List.of();
    }
}
