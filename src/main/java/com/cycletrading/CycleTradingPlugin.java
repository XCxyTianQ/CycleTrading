package com.cycletrading;

import com.cycletrading.command.CycleTradingCommand;
import com.cycletrading.core.Market;
import com.cycletrading.core.Storage;
import com.cycletrading.core.bank.Bank;
import com.cycletrading.core.bond.BondService;
import com.cycletrading.core.futures.Commodity;
import com.cycletrading.core.futures.FuturesService;
import com.cycletrading.core.luxury.LuxuryMarket;
import com.cycletrading.core.mailbox.Mailbox;
import com.cycletrading.core.options.OptionsService;
import com.cycletrading.core.options.PriceHistory;
import com.cycletrading.gui.GuiManager;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * CycleTrading —— 以绿宝石为唯一通货的经济/交易插件（v1.1）。
 *
 * 目标环境：AzureBranches EXP5Plus（Folia fork，MC 26.1.2，Java 25）。
 * 线程纪律：全部物品/世界操作经 entity/region 线程执行（见 sched.Scheduler 与 gui.GuiManager）；
 * 债券到期/期货交割/期权结算在全局线程轮询（纯数据操作）。
 */
public final class CycleTradingPlugin extends JavaPlugin {

    private static CycleTradingPlugin instance;

    private Market market;
    private Bank bank;
    private LuxuryMarket luxury;
    private Mailbox mailbox;
    private BondService bonds;
    private FuturesService futures;
    private OptionsService options;
    private PriceHistory priceHistory;
    private Storage storage;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        storage = new Storage(this, new File(getDataFolder(), "data").toPath());
        market = new Market(this);
        bank = new Bank(this);
        luxury = new LuxuryMarket(this);
        mailbox = new Mailbox(this);
        bonds = new BondService(this);
        futures = new FuturesService(this);
        options = new OptionsService(this);
        priceHistory = new PriceHistory(this);
        market.attachBank(bank);
        luxury.attach(bank);
        bonds.attachBank(bank);
        futures.attachBank(bank);
        options.attach(bank, priceHistory);
        storage.attach(market, bank, luxury, mailbox, bonds, futures, options);
        storage.load();
        priceHistory.rebuild(futures.deliveredContracts());
        bonds.start();
        futures.start();
        options.start();

        GuiManager guis = new GuiManager(this, market, luxury);
        getServer().getPluginManager().registerEvents(guis, this);

        PluginCommand cmd = getCommand("cycletrading");
        if (cmd != null) {
            CycleTradingCommand executor = new CycleTradingCommand(this, market, bank, luxury, bonds, futures, options, guis);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        getLogger().info("CycleTrading v" + getDescription().getVersion() + " enabled | "
                + getServer().getName() + " (MC " + getServer().getMinecraftVersion() + ")");
        getLogger().info("Active listings: " + market.activeNewestFirst().size()
                + " | Luxury: " + luxury.activeNewestFirst().size()
                + " | Bank accounts: " + bank.accountsSnapshot().size()
                + " | Player supply: " + bank.playerSupply()
                + " | Bonds: " + bonds.activeCount() + " active (locked " + bonds.totalLocked() + ")"
                + " | Futures: " + futures.countByStatus("OPEN") + " open, " + futures.countByStatus("LOCKED") + " locked"
                + " | Options: " + options.countByStatus("OPEN") + " open, " + options.countByStatus("LOCKED") + " locked");
    }

    @Override
    public void onDisable() {
        if (storage != null) {
            storage.flush();
        }
        getLogger().info("CycleTrading disabled, data flushed");
    }

    public static CycleTradingPlugin instance() {
        return instance;
    }

    public Market market() {
        return market;
    }

    public Bank bank() {
        return bank;
    }

    public LuxuryMarket luxury() {
        return luxury;
    }

    public Mailbox mailbox() {
        return mailbox;
    }

    public BondService bonds() {
        return bonds;
    }

    public FuturesService futures() {
        return futures;
    }

    public OptionsService options() {
        return options;
    }

    public PriceHistory priceHistory() {
        return priceHistory;
    }

    public Storage storage() {
        return storage;
    }

    public double taxPercent() {
        return getConfig().getDouble("tax-percent", 0.0);
    }

    public long minPrice() {
        return getConfig().getLong("min-price", 1L);
    }

    public long maxPrice() {
        return getConfig().getLong("max-price", 100000000L);
    }

    public long bankMaxBalance() {
        return getConfig().getLong("bank.max-balance", 999999999999L);
    }

    public int bankLedgerKeep() {
        return getConfig().getInt("bank.ledger-keep", 5000);
    }

    public long luxurySupplyAnchor() {
        return getConfig().getLong("luxury.supply-anchor", 1000000L);
    }

    public double luxuryMaxMultiplier() {
        return getConfig().getDouble("luxury.max-multiplier", 100.0);
    }

    public long luxuryMaxBasePrice() {
        return getConfig().getLong("luxury.max-base-price", 100000000L);
    }

    public int mailboxCapacity() {
        return getConfig().getInt("mailbox.capacity", 27);
    }

    public boolean bondEnabled() {
        return getConfig().getBoolean("bond.enabled", true);
    }

    public long bondRateAnchor() {
        return getConfig().getLong("bond.rate-anchor", 1000000L);
    }

    public double bondMaxMultiplier() {
        return getConfig().getDouble("bond.max-multiplier", 3.0);
    }

    public int bondDays(int tier) {
        return getConfig().getInt("bond.t" + tier + "-days", 0);
    }

    public double bondBaseRate(int tier) {
        return getConfig().getDouble("bond.t" + tier + "-rate", 0.0);
    }

    public long bondMin(int tier) {
        return getConfig().getLong("bond.t" + tier + "-min", 0L);
    }

    public boolean futuresEnabled() {
        return getConfig().getBoolean("futures.enabled", true);
    }

    public List<Integer> futuresTerms() {
        List<Integer> t = getConfig().getIntegerList("futures.terms");
        return (t == null || t.isEmpty()) ? List.of(1, 3, 7, 14, 30) : t;
    }

    /** 标准合约品种列表（config futures.contracts，缺省用内置 9 个大宗品种）。 */
    public List<Commodity> futuresCommodities() {
        ConfigurationSection sec = getConfig().getConfigurationSection("futures.contracts");
        List<Commodity> list = new ArrayList<>();
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                String mat = sec.getString(key + ".material");
                int amount = sec.getInt(key + ".amount");
                Material m = mat == null ? null : Material.matchMaterial(mat);
                if (m != null && amount > 0) {
                    list.add(new Commodity(key, m, amount));
                }
            }
        }
        return list.isEmpty() ? defaultCommodities() : list;
    }

    public boolean optionsEnabled() {
        return getConfig().getBoolean("options.enabled", true);
    }

    /** 品种参考价（期权结算价兜底锚，config options.reference.<key>）。 */
    public long optionReference(String key) {
        return getConfig().getLong("options.reference." + key, DEFAULT_REFERENCE.getOrDefault(key, 0L));
    }

    private static final Map<String, Long> DEFAULT_REFERENCE = Map.of(
            "oak_log", 500L,
            "coal_block", 2000L,
            "iron_block", 4000L,
            "gold_block", 8000L,
            "redstone_block", 6000L,
            "lapis_block", 9000L,
            "nether_quartz", 2000L,
            "diamond_block", 30000L,
            "netherite_block", 150000L);

    private static List<Commodity> defaultCommodities() {
        return List.of(
                new Commodity("oak_log", Material.OAK_LOG, 640),
                new Commodity("coal_block", Material.COAL_BLOCK, 320),
                new Commodity("iron_block", Material.IRON_BLOCK, 320),
                new Commodity("gold_block", Material.GOLD_BLOCK, 320),
                new Commodity("redstone_block", Material.REDSTONE_BLOCK, 320),
                new Commodity("lapis_block", Material.LAPIS_BLOCK, 320),
                new Commodity("nether_quartz", Material.QUARTZ, 64),
                new Commodity("diamond_block", Material.DIAMOND_BLOCK, 64),
                new Commodity("netherite_block", Material.NETHERITE_BLOCK, 64));
    }
}
