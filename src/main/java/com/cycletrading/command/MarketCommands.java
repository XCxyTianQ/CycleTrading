package com.cycletrading.command;

import com.cycletrading.CycleTradingPlugin;
import com.cycletrading.core.Items;
import com.cycletrading.core.Market;
import com.cycletrading.gui.GuiManager;
import com.cycletrading.util.Money;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** 市场命令：market / sell / my / mail / collect。 */
public final class MarketCommands {

    private final CycleTradingPlugin plugin;
    private final Market market;
    private final GuiManager guis;

    public MarketCommands(CycleTradingPlugin plugin, Market market, GuiManager guis) {
        this.plugin = plugin;
        this.market = market;
        this.guis = guis;
    }

    public void market(CommandSender sender, String[] args) {
        if (!Cmd.requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        int page = 0;
        if (args.length > 1) {
            Integer pg = Cmd.parseInt(args[1]);
            if (pg != null) {
                page = Math.max(0, pg - 1);
            }
        }
        guis.openMarket(p, page);
    }

    public void sell(CommandSender sender, String[] args) {
        if (!Cmd.requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (args.length < 2) {
            p.sendMessage("§c用法: /ct sell <价格>  （手持要出售的物品）");
            return;
        }
        Long price = Cmd.parseLong(args[1]);
        if (price == null) {
            p.sendMessage("§c价格必须是整数（单位：绿宝石）");
            return;
        }
        if (price < plugin.minPrice() || price > plugin.maxPrice()) {
            p.sendMessage("§c价格超出允许范围 [" + Money.fmt(plugin.minPrice()) + ", " + Money.fmt(plugin.maxPrice()) + "]");
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR || hand.getAmount() <= 0) {
            p.sendMessage("§c请手持要出售的物品再执行 /ct sell <价格>");
            return;
        }
        if (hand.getType() == Material.EMERALD || hand.getType() == Material.EMERALD_BLOCK) {
            p.sendMessage("§c绿宝石是通货，不接受上架（存取/兑换请用 /ct bank）");
            return;
        }
        long anchor = plugin.priceAnchor().anchorMilli(hand.getType());
        if (anchor > 0) {
            p.sendMessage("§7参考价: " + Money.fmtPrice(anchor) + " 绿宝石/个（"
                    + plugin.priceAnchor().anchorSource(hand.getType()) + "）· 该组参考总价约 "
                    + Money.fmtPrice(anchor * hand.getAmount()) + " 绿宝石");
            if (!plugin.priceAnchor().inBand(hand.getType(), hand.getAmount(), price)) {
                double band = plugin.anchorBand();
                p.sendMessage("§c价格超出参考区间（0.5× ~ " + band + "×），请按参考价合理定价。参考总价区间约 ["
                        + Money.fmtPrice((long) (anchor * hand.getAmount() / band)) + ", "
                        + Money.fmtPrice((long) (anchor * hand.getAmount() * band)) + "] 绿宝石");
                return;
            }
        }
        p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        market.create(p.getUniqueId().toString(), p.getName(), hand, price);
        p.sendMessage("§a上架成功！§e" + hand.getType().name() + " ×" + hand.getAmount()
                + " §a售价 §e" + price + " 绿宝石§a。用 /ct market 查看市场");
    }

    public void my(CommandSender sender) {
        if (Cmd.requirePlayer(sender)) {
            guis.openMy((Player) sender);
        }
    }

    public void mail(CommandSender sender) {
        if (Cmd.requirePlayer(sender)) {
            guis.openMail((Player) sender);
        }
    }

    public void collect(CommandSender sender) {
        if (!Cmd.requirePlayer(sender)) {
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
}
