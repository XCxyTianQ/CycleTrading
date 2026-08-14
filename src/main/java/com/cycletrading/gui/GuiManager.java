package com.cycletrading.gui;

import com.cycletrading.CycleTradingPlugin;
import com.cycletrading.core.Items;
import com.cycletrading.core.Listing;
import com.cycletrading.core.MailEntry;
import com.cycletrading.core.Market;
import com.cycletrading.core.bond.Bond;
import com.cycletrading.core.bond.BondService;
import com.cycletrading.core.futures.FuturesContract;
import com.cycletrading.core.futures.FuturesService;
import com.cycletrading.core.luxury.LuxuryListing;
import com.cycletrading.core.luxury.LuxuryMarket;
import com.cycletrading.core.mailbox.Mailbox;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** 绿宝石市场 GUI：市场浏览（分页）、购买确认、我的挂单。 */
public final class GuiManager implements Listener {

    /** 每页挂单数（5 行 × 9）。 */
    public static final int PAGE_SIZE = 45;
    /** "我的挂单" 每页挂单数（4 行 × 11 列? 不，44 格 = 4 行 + 8）。 */
    private static final int MY_SIZE = 44;

    public enum Type { MARKET, MY, CONFIRM, LUX, LUX_CONFIRM, MAIL, BOND, FUT, FUT_CONFIRM, FUT_MY }

    /** GUI 持有者：区分界面类型并携带上下文（页码 / 挂单 id）。 */
    public static final class GuiHolder implements InventoryHolder {
        public final Type type;
        public int page;
        public long listingId;
        private final Inventory inv;

        GuiHolder(Type type, String title, int size) {
            this.type = type;
            this.inv = Bukkit.createInventory(this, size, title);
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final CycleTradingPlugin plugin;
    private final Market market;
    private final LuxuryMarket luxury;

    public GuiManager(CycleTradingPlugin plugin, Market market, LuxuryMarket luxury) {
        this.plugin = plugin;
        this.market = market;
        this.luxury = luxury;
    }

    // ---------- 打开界面 ----------

    public void openMarket(Player p, int page) {
        List<Listing> act = market.activeNewestFirst();
        int total = act.size();
        int maxPage = Math.max(0, (total - 1) / PAGE_SIZE);
        if (page < 0) {
            page = 0;
        }
        if (page > maxPage) {
            page = maxPage;
        }
        GuiHolder h = new GuiHolder(Type.MARKET,
                "§8绿宝石市场 §7· 第 " + (page + 1) + "/" + (maxPage + 1) + " 页", 54);
        h.page = page;
        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < total; i++) {
            Listing l = act.get(from + i);
            h.getInventory().setItem(i, display(l, "§7点击查看并购买"));
        }
        h.getInventory().setItem(45, button(Material.ARROW, "§a上一页"));
        h.getInventory().setItem(49, button(Material.EMERALD, "§a在售挂单: " + total,
                "§7价格单位: 绿宝石",
                "§7银行余额: §a" + fmt(plugin.bank().balance(p.getUniqueId().toString())),
                "§7背包实物: §a" + fmt(Items.currencyCount(p))));
        h.getInventory().setItem(52, button(Material.BARRIER, "§c关闭"));
        h.getInventory().setItem(53, button(Material.ARROW, "§a下一页"));
        p.openInventory(h.getInventory());
    }

    public void openMy(Player p) {
        List<Listing> mine = market.listingsOf(p.getUniqueId().toString());
        GuiHolder h = new GuiHolder(Type.MY, "§8我的挂单", 54);
        for (int i = 0; i < MY_SIZE && i < mine.size(); i++) {
            h.getInventory().setItem(i, display(mine.get(i), "§7点击下架并取回物品"));
        }
        int pending = plugin.mailbox().count(p.getUniqueId().toString());
        h.getInventory().setItem(49, button(Material.CHEST, "§a邮箱: " + pending + "/" + plugin.mailbox().capacity(),
                "§7/ct mail 查看 · /ct collect 一键领取"));
        h.getInventory().setItem(52, button(Material.BARRIER, "§c关闭"));
        h.getInventory().setItem(53, button(Material.EMERALD, "§a绿宝石",
                "§7银行余额: §a" + fmt(plugin.bank().balance(p.getUniqueId().toString())),
                "§7背包实物: §a" + fmt(Items.currencyCount(p))));
        p.openInventory(h.getInventory());
    }

    public void openConfirm(Player p, Listing l) {
        GuiHolder h = new GuiHolder(Type.CONFIRM, "§8确认购买", 9);
        h.listingId = l.id;
        h.getInventory().setItem(2, display(l, "§7卖家: §f" + l.sellerName,
                "§7价格: §a" + l.price + " 绿宝石"));
        h.getInventory().setItem(4, button(Material.EMERALD_BLOCK, "§a确认购买",
                "§7支付: " + l.price + " 绿宝石（优先银行余额）",
                "§7银行余额: §a" + fmt(plugin.bank().balance(p.getUniqueId().toString())),
                "§7背包实物: §a" + fmt(Items.currencyCount(p))));
        h.getInventory().setItem(6, button(Material.BARRIER, "§c取消", "§7返回市场"));
        p.openInventory(h.getInventory());
    }

    // ---------- 奢侈品商店 ----------

    public void openLux(Player p, int page) {
        List<LuxuryListing> act = luxury.activeNewestFirst();
        int total = act.size();
        int maxPage = Math.max(0, (total - 1) / PAGE_SIZE);
        if (page < 0) {
            page = 0;
        }
        if (page > maxPage) {
            page = maxPage;
        }
        GuiHolder h = new GuiHolder(Type.LUX,
                "§8奢侈品商店 §7· 第 " + (page + 1) + "/" + (maxPage + 1) + " 页", 54);
        h.page = page;
        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < total; i++) {
            LuxuryListing l = act.get(from + i);
            h.getInventory().setItem(i, luxDisplay(l, "§7点击查看并购买"));
        }
        h.getInventory().setItem(45, button(Material.ARROW, "§a上一页"));
        h.getInventory().setItem(49, button(Material.GOLD_BLOCK, "§6奢侈品商店",
                "§7在售: " + total + " · 仅管理员挂售",
                "§7当前倍率: §a" + String.format("%.3f", luxury.multiplier()) + "×",
                "§7成交价 = 基础价 × 倍率",
                "§7银行余额: §a" + fmt(plugin.bank().balance(p.getUniqueId().toString())),
                "§7背包实物: §a" + fmt(Items.currencyCount(p))));
        h.getInventory().setItem(52, button(Material.BARRIER, "§c关闭"));
        h.getInventory().setItem(53, button(Material.ARROW, "§a下一页"));
        p.openInventory(h.getInventory());
    }

    public void openLuxConfirm(Player p, LuxuryListing l) {
        GuiHolder h = new GuiHolder(Type.LUX_CONFIRM, "§8确认购买奢侈品", 9);
        h.listingId = l.id;
        long price = luxury.effectivePrice(l.basePrice);
        h.getInventory().setItem(2, luxDisplay(l, "§7当前成交价: §6" + fmt(price) + " 绿宝石"));
        h.getInventory().setItem(4, button(Material.GOLD_BLOCK, "§6确认购买",
                "§7支付: " + fmt(price) + " 绿宝石（优先银行余额）",
                "§7基础价: " + fmt(l.basePrice) + " × 倍率 " + String.format("%.3f", luxury.multiplier()),
                "§7银行余额: §a" + fmt(plugin.bank().balance(p.getUniqueId().toString())),
                "§7背包实物: §a" + fmt(Items.currencyCount(p))));
        h.getInventory().setItem(6, button(Material.BARRIER, "§c取消", "§7返回商店"));
        p.openInventory(h.getInventory());
    }

    // ---------- 点击路由 ----------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof GuiHolder h)) {
            return;
        }
        e.setCancelled(true);
        if (e.getClickedInventory() != top) {
            return;
        }
        int slot = e.getSlot();
        switch (h.type) {
            case MARKET -> {
                if (slot < PAGE_SIZE) {
                    List<Listing> act = market.activeNewestFirst();
                    int idx = h.page * PAGE_SIZE + slot;
                    if (idx < act.size()) {
                        openConfirm(p, act.get(idx));
                    }
                } else if (slot == 45) {
                    openMarket(p, h.page - 1);
                } else if (slot == 53) {
                    openMarket(p, h.page + 1);
                } else if (slot == 52) {
                    p.closeInventory();
                }
            }
            case CONFIRM -> {
                if (slot == 4) {
                    doBuy(p, h.listingId);
                } else if (slot == 6) {
                    openMarket(p, 0);
                }
            }
            case MY -> {
                if (slot < MY_SIZE) {
                    List<Listing> mine = market.listingsOf(p.getUniqueId().toString());
                    if (slot < mine.size()) {
                        doCancel(p, mine.get(slot).id);
                    }
                } else if (slot == 52) {
                    p.closeInventory();
                }
            }
            case LUX -> {
                if (slot < PAGE_SIZE) {
                    List<LuxuryListing> act = luxury.activeNewestFirst();
                    int idx = h.page * PAGE_SIZE + slot;
                    if (idx < act.size()) {
                        openLuxConfirm(p, act.get(idx));
                    }
                } else if (slot == 45) {
                    openLux(p, h.page - 1);
                } else if (slot == 53) {
                    openLux(p, h.page + 1);
                } else if (slot == 52) {
                    p.closeInventory();
                }
            }
            case LUX_CONFIRM -> {
                if (slot == 4) {
                    doLuxBuy(p, h.listingId);
                } else if (slot == 6) {
                    openLux(p, 0);
                }
            }
            case MAIL -> {
                if (slot < 27) {
                    doMailClaim(p, slot);
                }
            }
            case BOND -> {
                if (slot == 53) {
                    p.closeInventory();
                }
            }
            case FUT -> {
                if (slot < PAGE_SIZE) {
                    List<FuturesContract> act = plugin.futures().openNewestFirst();
                    int idx = h.page * PAGE_SIZE + slot;
                    if (idx < act.size()) {
                        openFutConfirm(p, act.get(idx));
                    }
                } else if (slot == 45) {
                    openFut(p, h.page - 1);
                } else if (slot == 53) {
                    openFut(p, h.page + 1);
                } else if (slot == 52) {
                    p.closeInventory();
                }
            }
            case FUT_CONFIRM -> {
                if (slot == 4) {
                    doFutBuy(p, h.listingId);
                } else if (slot == 6) {
                    openFut(p, 0);
                }
            }
            case FUT_MY -> {
                if (slot < 26) {
                    List<FuturesContract> mine = plugin.futures().ofSeller(p.getUniqueId().toString());
                    if (slot < mine.size() && mine.get(slot).isOpen()) {
                        doFutCancel(p, mine.get(slot).id);
                    }
                } else if (slot == 53) {
                    p.closeInventory();
                }
            }
        }
    }

    // ---------- 业务动作（均在点击玩家自己的线程上执行） ----------

    private void doBuy(Player p, long listingId) {
        Market.BuyResult r = market.tryBuy(p, listingId);
        switch (r) {
            case SUCCESS -> {
                p.closeInventory();
                p.sendMessage("§a购买成功！物品已放入背包（背包满则存入邮箱，用 /ct collect 领取）");
            }
            case NOT_ACTIVE -> {
                p.closeInventory();
                p.sendMessage("§c该挂单已被他人买走或已下架");
            }
            case NOT_FOUND -> p.sendMessage("§c挂单不存在");
            case SELF_PURCHASE -> p.sendMessage("§c不能购买自己的挂单");
            case INSUFFICIENT_FUNDS -> p.sendMessage("§c绿宝石不足（银行余额 + 背包实物），无法完成购买");
            case FROZEN -> p.sendMessage("§c你的银行账户已被冻结，无法购买");
            case NO_SPACE -> p.sendMessage("§c背包与邮箱均无空间（邮箱上限 " + plugin.mailbox().capacity() + "），请先清理");
            case ERROR -> p.sendMessage("§c交易失败，请稍后再试（已自动退款）");
        }
    }

    private void doCancel(Player p, long listingId) {
        Market.CancelResult r = market.cancel(p, listingId);
        switch (r) {
            case SUCCESS -> {
                p.sendMessage("§a已下架，物品已归还背包（背包满则存入邮箱）");
                openMy(p);
            }
            case NOT_ACTIVE -> {
                p.sendMessage("§c该挂单已成交或已下架");
                openMy(p);
            }
            case NOT_FOUND -> p.sendMessage("§c挂单不存在");
            case NOT_OWNER -> p.sendMessage("§c这不是你的挂单");
            case NO_SPACE -> p.sendMessage("§c背包与邮箱均无空间，无法下架（请先清理）");
            case ERROR -> p.sendMessage("§c下架失败，请稍后再试");
        }
    }

    private void doLuxBuy(Player p, long listingId) {
        LuxuryMarket.BuyResult r = luxury.buy(p, listingId);
        switch (r) {
            case SUCCESS -> {
                p.closeInventory();
                p.sendMessage("§6购买成功！奢侈品已放入背包（背包满则存入邮箱，用 /ct collect 领取）");
            }
            case NOT_ACTIVE -> {
                p.closeInventory();
                p.sendMessage("§c该奢侈品已被他人买走或已下架");
            }
            case NOT_FOUND -> p.sendMessage("§c挂单不存在");
            case INSUFFICIENT_FUNDS -> p.sendMessage("§c绿宝石不足（银行余额 + 背包实物），无法完成购买");
            case FROZEN -> p.sendMessage("§c你的银行账户已被冻结，无法购买");
            case NO_SPACE -> p.sendMessage("§c背包与邮箱均无空间（邮箱上限 " + plugin.mailbox().capacity() + "），请先清理");
            case ERROR -> p.sendMessage("§c交易失败，请稍后再试（已自动退款）");
        }
    }

    public void doLuxRemove(Player p, long listingId) {
        LuxuryMarket.RemoveResult r = luxury.remove(p, listingId);
        switch (r) {
            case SUCCESS -> p.sendMessage("§a已下架，物品已归还背包（背包满则存入邮箱）");
            case NOT_FOUND -> p.sendMessage("§c挂单不存在");
            case NOT_ACTIVE -> p.sendMessage("§c该挂单已成交或已下架");
            case NO_SPACE -> p.sendMessage("§c背包与邮箱均无空间，无法下架（请先清理）");
            case ERROR -> p.sendMessage("§c下架失败，请稍后再试");
        }
    }

    // ---------- 邮箱（只收不存，上限 27） ----------

    public void openMail(Player p) {
        String uuid = p.getUniqueId().toString();
        List<MailEntry> mine = plugin.mailbox().entriesOf(uuid);
        GuiHolder h = new GuiHolder(Type.MAIL,
                "§8邮箱 §7· " + mine.size() + "/" + plugin.mailbox().capacity() + "（只收不存）", 27);
        for (int i = 0; i < mine.size() && i < 27; i++) {
            h.getInventory().setItem(i, mailDisplay(mine.get(i)));
        }
        p.openInventory(h.getInventory());
    }

    private ItemStack mailDisplay(MailEntry m) {
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm");
        String time = f.format(new Date(m.createdAt));
        String source = switch (m.source == null ? "" : m.source) {
            case "MARKET" -> "市场购买";
            case "LUXURY" -> "奢侈品";
            case "INSURANCE" -> "死亡保险";
            default -> "系统";
        };
        ItemStack it;
        List<String> lore = new ArrayList<>();
        if (m.item != null) {
            try {
                it = Items.fromBase64(m.item);
            } catch (RuntimeException ex) {
                it = new ItemStack(Material.BARRIER);
            }
            lore.add("§7来源: §f" + source + "  ·  " + time);
            lore.add("§7点击领取到背包");
        } else {
            it = Items.emeralds(Math.min(64, Math.max(1, m.emeralds)));
            lore.add("§7绿宝石 ×§a" + m.emeralds);
            lore.add("§7来源: §f" + source + "  ·  " + time);
            lore.add("§7点击领取到背包");
        }
        ItemMeta meta = it.getItemMeta();
        List<String> old = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        old.addAll(lore);
        meta.setLore(old);
        it.setItemMeta(meta);
        return it;
    }

    private void doMailClaim(Player p, int idx) {
        Mailbox.ClaimResult r = plugin.mailbox().claim(p, idx);
        switch (r) {
            case SUCCESS -> p.sendMessage("§a已领取");
            case PARTIAL -> p.sendMessage("§7背包已满，已领取部分，剩余保留在邮箱");
            case INVENTORY_FULL -> p.sendMessage("§c背包已满，无法领取");
            case NOT_FOUND -> { /* 忽略 */ }
        }
        openMail(p);
    }

    // ---------- 定期债券 ----------

    public void openBonds(Player p) {
        String uuid = p.getUniqueId().toString();
        List<Bond> act = plugin.bonds().activeBondsOf(uuid);
        GuiHolder h = new GuiHolder(Type.BOND,
                "§8我的定期债券 §7· 在持 " + act.size() + " 笔", 54);
        int i = 0;
        for (Bond b : act) {
            if (i >= 44) {
                break;
            }
            h.getInventory().setItem(i++, bondItem(b, true));
        }
        h.getInventory().setItem(49, button(Material.EMERALD, "§a全服总锁定: " + fmt(plugin.bonds().totalLocked()),
                "§7当前利率倍率: " + String.format("%.3f", plugin.bonds().rateMultiplier()) + "×",
                "§7购买: /ct bond buy <档位> <金额>",
                "§7行情: /ct bond info"));
        h.getInventory().setItem(53, button(Material.BARRIER, "§c关闭"));
        p.openInventory(h.getInventory());
    }

    private ItemStack bondItem(Bond b, boolean active) {
        ItemStack it = new ItemStack(active ? Material.PAPER : Material.MAP);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(active
                ? "§6定期债券 #" + b.id + " §7(档位" + b.tier + ")"
                : "§7已结算债券 #" + b.id + " §7(档位" + b.tier + ")");
        List<String> lore = new ArrayList<>();
        lore.add("§7本金: §a" + fmt(b.principal) + " 绿宝石");
        lore.add("§7锁定利率: §a" + BondService.fmtRate(b.rateBp));
        if (active) {
            long left = plugin.bonds().daysLeft(b);
            lore.add("§7剩余: §a" + left + " §7游戏日（" + plugin.bondDays(b.tier) + " 天期）");
            lore.add("§7到期自动结算：本金 + 利息入银行");
        } else {
            lore.add("§7已结算利息: §a" + fmt(b.interest) + " 绿宝石");
            lore.add("§7本息已入账银行");
        }
        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    // ---------- 期货交割市场 ----------

    public void openFut(Player p, int page) {
        List<FuturesContract> act = plugin.futures().openNewestFirst();
        int total = act.size();
        int maxPage = Math.max(0, (total - 1) / PAGE_SIZE);
        if (page < 0) {
            page = 0;
        }
        if (page > maxPage) {
            page = maxPage;
        }
        GuiHolder h = new GuiHolder(Type.FUT,
                "§8期货市场 §7· 第 " + (page + 1) + "/" + (maxPage + 1) + " 页", 54);
        h.page = page;
        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < total; i++) {
            FuturesContract c = act.get(from + i);
            h.getInventory().setItem(i, futDisplay(c, "§7点击查看并成交"));
        }
        h.getInventory().setItem(45, button(Material.ARROW, "§a上一页"));
        h.getInventory().setItem(49, button(Material.HOPPER, "§6期货交易所",
                "§7在售标准合约: " + total,
                "§7全额保证金 · 到期实物交割入邮箱",
                "§7标准品种: /ct fut info"));
        h.getInventory().setItem(52, button(Material.BARRIER, "§c关闭"));
        h.getInventory().setItem(53, button(Material.ARROW, "§a下一页"));
        p.openInventory(h.getInventory());
    }

    public void openFutConfirm(Player p, FuturesContract c) {
        GuiHolder h = new GuiHolder(Type.FUT_CONFIRM, "§8确认成交期货合约", 9);
        h.listingId = c.id;
        h.getInventory().setItem(2, futDisplay(c, "§7卖家: §f" + c.sellerName));
        h.getInventory().setItem(4, button(Material.HOPPER, "§a确认成交",
                "§7支付: " + fmt(c.price) + " 绿宝石（优先银行余额）",
                "§7交割: " + c.termDays + " 游戏日后商品入你邮箱",
                "§7银行余额: §a" + fmt(plugin.bank().balance(p.getUniqueId().toString())),
                "§7背包实物: §a" + fmt(Items.currencyCount(p))));
        h.getInventory().setItem(6, button(Material.BARRIER, "§c取消", "§7返回市场"));
        p.openInventory(h.getInventory());
    }

    public void openFutMy(Player p) {
        String uuid = p.getUniqueId().toString();
        List<FuturesContract> mine = plugin.futures().ofSeller(uuid);
        List<FuturesContract> bought = plugin.futures().ofBuyer(uuid);
        GuiHolder h = new GuiHolder(Type.FUT_MY,
                "§8我的期货 §7· 卖出 " + mine.size() + " 笔 · 买入 " + bought.size() + " 笔", 54);
        int i = 0;
        for (FuturesContract c : mine) {
            if (i >= 26) {
                break;
            }
            h.getInventory().setItem(i++, futDisplay(c,
                    c.isOpen() ? "§7点击撤单取回商品" : "§7" + statusText(c)));
        }
        for (FuturesContract c : bought) {
            if (i >= 52) {
                break;
            }
            h.getInventory().setItem(i++, futDisplay(c, "§7" + statusText(c)));
        }
        h.getInventory().setItem(53, button(Material.BARRIER, "§c关闭"));
        p.openInventory(h.getInventory());
    }

    private String statusText(FuturesContract c) {
        return switch (c.status) {
            case FuturesContract.OPEN -> "§a挂单中（未成交）";
            case FuturesContract.LOCKED -> "§e已成交锁定，剩余 " + plugin.futures().daysLeft(c) + " 游戏日交割";
            case FuturesContract.DELIVERED -> "§a已交割";
            case FuturesContract.WITHDRAWN -> "§7已撤单";
            case FuturesContract.CANCELLED -> "§c已撤销";
            default -> c.status;
        };
    }

    private ItemStack futDisplay(FuturesContract c, String... extraLore) {
        ItemStack it;
        try {
            it = Items.fromBase64(c.item);
        } catch (RuntimeException ex) {
            it = new ItemStack(Material.BARRIER);
        }
        ItemMeta meta = it.getItemMeta();
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add("");
        lore.add("§7数量: §f" + it.getAmount() + " §7（" + (it.getAmount() + 63) / 64 + " 组）");
        lore.add("§7价格: §a" + fmt(c.price) + " 绿宝石");
        lore.add("§7交割期限: §a" + c.termDays + " §7游戏日");
        lore.add("§7卖家: §f" + c.sellerName + "  ·  编号: #" + c.id);
        for (String s : extraLore) {
            lore.add(s);
        }
        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private void doFutBuy(Player p, long listingId) {
        FuturesService.BuyResult r = plugin.futures().validateBuy(p, listingId);
        switch (r) {
            case SUCCESS -> {
                FuturesContract c = plugin.futures().buy(p, listingId);
                if (c == null) {
                    p.sendMessage("§c交易失败（余额变化或合约已被抢），请重试");
                } else {
                    p.closeInventory();
                    p.sendMessage("§a成交成功！合约 #" + c.id + " 已锁定，"
                            + c.termDays + " 游戏日后商品交割入你的邮箱（/ct mail 领取）");
                }
            }
            case NOT_FOUND -> p.sendMessage("§c合约不存在");
            case NOT_ACTIVE -> {
                p.closeInventory();
                p.sendMessage("§c该合约已被成交或撤销");
            }
            case SELF_PURCHASE -> p.sendMessage("§c不能成交自己的合约");
            case INSUFFICIENT_FUNDS -> p.sendMessage("§c绿宝石不足（银行余额 + 背包实物）");
            case FROZEN -> p.sendMessage("§c你的银行账户已被冻结");
            case NO_SPACE -> p.sendMessage("§c邮箱空间不足：交割需占用邮箱格位，请先清理（/ct mail）");
            case ERROR -> p.sendMessage("§c交易失败，请稍后再试");
        }
    }

    private void doFutCancel(Player p, long listingId) {
        FuturesService.CancelResult r = plugin.futures().cancel(p, listingId);
        switch (r) {
            case SUCCESS -> {
                p.sendMessage("§a已撤单，商品已归还背包（放不下进邮箱）");
                openFutMy(p);
            }
            case NOT_FOUND -> p.sendMessage("§c合约不存在");
            case NOT_ACTIVE -> {
                p.sendMessage("§c该合约已成交，无法撤单");
                openFutMy(p);
            }
            case NOT_OWNER -> p.sendMessage("§c这不是你的合约");
            case NO_SPACE -> p.sendMessage("§c背包与邮箱均无空间，无法撤单（请先清理）");
            case ERROR -> p.sendMessage("§c撤单失败，请稍后再试");
        }
    }

    // ---------- 展示工具 ----------

    private ItemStack display(Listing l, String... extraLore) {
        ItemStack it;
        try {
            it = Items.fromBase64(l.item);
        } catch (RuntimeException ex) {
            it = new ItemStack(Material.BARRIER);
        }
        ItemMeta meta = it.getItemMeta();
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add("");
        lore.add("§7价格: §a" + l.price + " 绿宝石");
        lore.add("§7卖家: §f" + l.sellerName);
        lore.add("§7编号: #" + l.id);
        for (String s : extraLore) {
            lore.add(s);
        }
        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack luxDisplay(LuxuryListing l, String... extraLore) {
        ItemStack it;
        try {
            it = Items.fromBase64(l.item);
        } catch (RuntimeException ex) {
            it = new ItemStack(Material.BARRIER);
        }
        ItemMeta meta = it.getItemMeta();
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add("");
        lore.add("§7基础定价: §6" + fmt(l.basePrice) + " 绿宝石");
        lore.add("§7当前成交价: §6" + fmt(luxury.effectivePrice(l.basePrice)) + " 绿宝石");
        lore.add("§7当前倍率: §a" + String.format("%.3f", luxury.multiplier()) + "×");
        lore.add("§7挂售: §f" + l.listedBy + "  ·  编号: #" + l.id);
        for (String s : extraLore) {
            lore.add(s);
        }
        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack button(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(List.of(lore));
        it.setItemMeta(meta);
        return it;
    }

    private static String fmt(long n) {
        return String.format("%,d", n);
    }
}
