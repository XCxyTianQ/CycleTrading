package com.cycletrading;

import com.cycletrading.command.CycleTradingCommand;
import com.cycletrading.core.Market;
import com.cycletrading.core.Storage;
import com.cycletrading.core.bank.Bank;
import com.cycletrading.core.bond.BondService;
import com.cycletrading.core.futures.Commodity;
import com.cycletrading.core.futures.FuturesService;
import com.cycletrading.core.insurance.InsuranceListener;
import com.cycletrading.core.insurance.InsuranceService;
import com.cycletrading.core.luxury.LuxuryMarket;
import com.cycletrading.core.mailbox.Mailbox;
import com.cycletrading.gui.GuiManager;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * CycleTrading —— 以绿宝石为唯一通货的玩家间挂单市场。
 *
 * 目标环境：AzureBranches EXP5Plus（Folia fork，MC 26.1.2，Java 25）。
 * 线程纪律：全部物品/世界操作经 entity/region 线程执行（见 sched.Scheduler 与 gui.GuiManager）。
 */
public final class CycleTradingPlugin extends JavaPlugin {

    private static CycleTradingPlugin instance;

    private Market market;
    private Bank bank;
    private LuxuryMarket luxury;
    private InsuranceService insurance;
    private Mailbox mailbox;
    private BondService bonds;
    private FuturesService futures;
    private Storage storage;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        storage = new Storage(this, new File(getDataFolder(), "data").toPath());
        market = new Market(this);
        bank = new Bank(this);
        luxury = new LuxuryMarket(this);
        insurance = new InsuranceService(this);
        mailbox = new Mailbox(this);
        bonds = new BondService(this);
        futures = new FuturesService(this);
        market.attachBank(bank);
        luxury.attach(bank);
        insurance.attachBank(bank);
        bonds.attachBank(bank);
        futures.attachBank(bank);
        storage.attach(market, bank, luxury, insurance, mailbox, bonds, futures);
        storage.load();
        bonds.start();
        futures.start();

        GuiManager guis = new GuiManager(this, market, luxury);
        getServer().getPluginManager().registerEvents(guis, this);
        getServer().getPluginManager().registerEvents(new InsuranceListener(this, insurance), this);

        PluginCommand cmd = getCommand("cycletrading");
        if (cmd != null) {
            CycleTradingCommand executor = new CycleTradingCommand(this, market, bank, luxury, insurance, bonds, futures, guis);
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
                + " | Futures: " + futures.countByStatus("OPEN") + " open, " + futures.countByStatus("LOCKED") + " locked");
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

    public InsuranceService insurance() {
        return insurance;
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

    public boolean insuranceEnabled() {
        return getConfig().getBoolean("insurance.enabled", true);
    }

    public long insT1Price() {
        return getConfig().getLong("insurance.t1-price", 10L);
    }

    public long insT2Price() {
        return getConfig().getLong("insurance.t2-price", 20L);
    }

    public long insT3Price() {
        return getConfig().getLong("insurance.t3-price", 40L);
    }

    public long insT4Price() {
        return getConfig().getLong("insurance.t4-price", 64L);
    }

    public long insT4Compensation() {
        return getConfig().getLong("insurance.t4-compensation", 10L);
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
