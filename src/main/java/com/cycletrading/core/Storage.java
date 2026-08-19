package com.cycletrading.core;

import com.cycletrading.CycleTradingPlugin;
import com.cycletrading.core.bank.BankAccount;
import com.cycletrading.core.bank.TxEntry;
import com.cycletrading.core.bond.Bond;
import com.cycletrading.core.futures.FuturesContract;
import com.cycletrading.core.futures.FuturesPosition;
import com.cycletrading.core.luxury.LuxuryListing;
import com.cycletrading.core.mailbox.Mailbox;
import com.cycletrading.core.options.OptionContract;
import com.cycletrading.core.prices.PriceAnchor;
import com.cycletrading.storage.JsonRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 持久化协调器：JsonRepository 注册表 + 快照编排。
 * 单线程 IO 执行器；变更异步落盘（全部快照），禁用时同步 flush。
 */
public final class Storage {

    /** 市场快照（mailbox 字段仅用于读取旧版存档并迁移，写入恒为空）。 */
    public static final class MarketSnapshot {
        public List<Listing> listings = new ArrayList<>();
        public List<MailEntry> mailbox = new ArrayList<>();
    }

    /** 银行快照。 */
    public static final class BankSnapshot {
        public List<BankAccount> accounts = new ArrayList<>();
        public List<TxEntry> ledger = new ArrayList<>();
    }

    /** 奢侈品商店快照。 */
    public static final class LuxurySnapshot {
        public List<LuxuryListing> listings = new ArrayList<>();
    }

    /** 邮箱快照。 */
    public static final class MailboxSnapshot {
        public List<MailEntry> entries = new ArrayList<>();
    }

    /** 定期债券快照。 */
    public static final class BondSnapshot {
        public List<Bond> bonds = new ArrayList<>();
    }

    /** 期货快照（实物合约 + 多空头寸）。 */
    public static final class FuturesSnapshot {
        public List<FuturesContract> contracts = new ArrayList<>();
        public List<FuturesPosition> positions = new ArrayList<>();
    }

    /** 期权合约快照。 */
    public static final class OptionsSnapshot {
        public List<OptionContract> contracts = new ArrayList<>();
    }

    /** 市场锚点快照（成交学习窗口；村民基础价启动时重新注册）。 */
    public static final class PriceAnchorSnapshot {
        public Map<String, List<Long>> recent = new HashMap<>();
    }

    /** 金条快照。 */
    public static final class GoldSnapshot {
        public boolean seeded = false;
        public Map<String, Long> holdings = new HashMap<>();
    }

    private final CycleTradingPlugin plugin;
    private final JsonRepository marketRepo;
    private final JsonRepository bankRepo;
    private final JsonRepository luxuryRepo;
    private final JsonRepository mailboxRepo;
    private final JsonRepository bondRepo;
    private final JsonRepository futuresRepo;
    private final JsonRepository optionsRepo;
    private final JsonRepository pricesRepo;
    private final JsonRepository goldRepo;

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cycletrading-io");
        t.setDaemon(true);
        return t;
    });

    private volatile Market market;
    private volatile com.cycletrading.core.bank.Bank bank;
    private volatile com.cycletrading.core.luxury.LuxuryMarket luxury;
    private volatile Mailbox mailbox;
    private volatile com.cycletrading.core.bond.BondService bonds;
    private volatile com.cycletrading.core.futures.FuturesService futures;
    private volatile com.cycletrading.core.options.OptionsService options;
    private volatile PriceAnchor priceAnchor;
    private volatile com.cycletrading.core.gold.GoldService gold;

    public Storage(CycleTradingPlugin plugin, Path dataDir) {
        this.plugin = plugin;
        this.marketRepo = new JsonRepository(plugin, dataDir, "market");
        this.bankRepo = new JsonRepository(plugin, dataDir, "bank");
        this.luxuryRepo = new JsonRepository(plugin, dataDir, "luxury");
        this.mailboxRepo = new JsonRepository(plugin, dataDir, "mailbox");
        this.bondRepo = new JsonRepository(plugin, dataDir, "bonds");
        this.futuresRepo = new JsonRepository(plugin, dataDir, "futures");
        this.optionsRepo = new JsonRepository(plugin, dataDir, "options");
        this.pricesRepo = new JsonRepository(plugin, dataDir, "prices");
        this.goldRepo = new JsonRepository(plugin, dataDir, "gold");
    }

    public void attach(Market market, com.cycletrading.core.bank.Bank bank,
            com.cycletrading.core.luxury.LuxuryMarket luxury,
            Mailbox mailbox,
            com.cycletrading.core.bond.BondService bonds,
            com.cycletrading.core.futures.FuturesService futures,
            com.cycletrading.core.options.OptionsService options,
            PriceAnchor priceAnchor,
            com.cycletrading.core.gold.GoldService gold) {
        this.market = market;
        this.bank = bank;
        this.luxury = luxury;
        this.mailbox = mailbox;
        this.bonds = bonds;
        this.futures = futures;
        this.options = options;
        this.priceAnchor = priceAnchor;
        this.gold = gold;
    }

    /** 启动时加载全部存档。文件不存在或损坏时从空状态开始。 */
    public void load() {
        MarketSnapshot ms = marketRepo.load(MarketSnapshot.class);
        if (ms != null) {
            if (ms.listings != null) {
                for (Listing l : ms.listings) {
                    market.restoreListing(l);
                }
            }
            if (ms.mailbox != null) {
                for (MailEntry m : ms.mailbox) {
                    mailbox.restore(m);
                }
                if (!ms.mailbox.isEmpty()) {
                    plugin.getLogger().info("Migrated " + ms.mailbox.size() + " legacy mailbox entries");
                }
            }
            market.rebuildNextId();
            plugin.getLogger().info("Data loaded: " + (ms.listings == null ? 0 : ms.listings.size()) + " listings");
        }

        BankSnapshot bs = bankRepo.load(BankSnapshot.class);
        if (bs != null) {
            if (bs.accounts != null) {
                for (BankAccount a : bs.accounts) {
                    bank.restoreAccount(a);
                }
            }
            if (bs.ledger != null) {
                for (TxEntry t : bs.ledger) {
                    bank.restoreTx(t);
                }
            }
            bank.rebuildTxId();
            bank.rebuildSupply();
            plugin.getLogger().info("Bank loaded: " + (bs.accounts == null ? 0 : bs.accounts.size())
                    + " accounts, " + (bs.ledger == null ? 0 : bs.ledger.size())
                    + " ledger entries, player supply " + bank.playerSupply());
        }

        LuxurySnapshot ls = luxuryRepo.load(LuxurySnapshot.class);
        if (ls != null) {
            if (ls.listings != null) {
                for (LuxuryListing l : ls.listings) {
                    luxury.restore(l);
                }
            }
            luxury.rebuildNextId();
            plugin.getLogger().info("Luxury loaded: " + (ls.listings == null ? 0 : ls.listings.size()) + " listings");
        }

        MailboxSnapshot mbs = mailboxRepo.load(MailboxSnapshot.class);
        if (mbs != null) {
            if (mbs.entries != null) {
                for (MailEntry m : mbs.entries) {
                    mailbox.restore(m);
                }
            }
            plugin.getLogger().info("Mailbox loaded: " + (mbs.entries == null ? 0 : mbs.entries.size()) + " entries");
        }

        BondSnapshot bds = bondRepo.load(BondSnapshot.class);
        if (bds != null) {
            if (bds.bonds != null) {
                for (Bond b : bds.bonds) {
                    bonds.restore(b);
                }
            }
            bonds.rebuildNextId();
            plugin.getLogger().info("Bonds loaded: " + (bds.bonds == null ? 0 : bds.bonds.size())
                    + " bonds, " + bonds.activeCount() + " active");
        }

        FuturesSnapshot fs = futuresRepo.load(FuturesSnapshot.class);
        if (fs != null) {
            if (fs.contracts != null) {
                for (FuturesContract c : fs.contracts) {
                    futures.restore(c);
                }
            }
            if (fs.positions != null) {
                for (FuturesPosition p : fs.positions) {
                    futures.restorePosition(p);
                }
            }
            futures.rebuildNextId();
            futures.rebuildPosId();
            plugin.getLogger().info("Futures loaded: " + (fs.contracts == null ? 0 : fs.contracts.size())
                    + " contracts, " + futures.countByStatus(FuturesContract.OPEN) + " open, "
                    + futures.countByStatus(FuturesContract.LOCKED) + " locked | "
                    + futures.posCountByStatus(FuturesPosition.OPEN) + " positions open");
        }

        OptionsSnapshot os = optionsRepo.load(OptionsSnapshot.class);
        if (os != null) {
            if (os.contracts != null) {
                for (OptionContract c : os.contracts) {
                    options.restore(c);
                }
            }
            options.rebuildNextId();
            plugin.getLogger().info("Options loaded: " + (os.contracts == null ? 0 : os.contracts.size())
                    + " contracts, " + options.countByStatus(OptionContract.OPEN) + " open, "
                    + options.countByStatus(OptionContract.LOCKED) + " locked");
        }

        PriceAnchorSnapshot ps = pricesRepo.load(PriceAnchorSnapshot.class);
        if (ps != null && ps.recent != null) {
            priceAnchor.restore(ps.recent);
        }
        plugin.getLogger().info("PriceAnchor loaded: "
                + (ps == null || ps.recent == null ? 0 : ps.recent.size()) + " items with market history");

        GoldSnapshot gs = goldRepo.load(GoldSnapshot.class);
        if (gs != null) {
            gold.restore(gs.holdings, gs.seeded);
        }
        plugin.getLogger().info("Gold loaded: " + gold.outstanding() + " bars outstanding, treasury " + gold.treasury());
    }

    /** 请求异步落盘（任何模块变更后调用）。 */
    public void requestSave() {
        if (market == null || bank == null || io.isShutdown()) {
            return;
        }
        io.submit(this::save);
    }

    private void save() {
        MarketSnapshot ms = new MarketSnapshot();
        ms.listings = new ArrayList<>(market.listingsSnapshot());
        marketRepo.save(ms);

        BankSnapshot bs = new BankSnapshot();
        bs.accounts = new ArrayList<>(bank.accountsSnapshot());
        bs.ledger = new ArrayList<>(bank.ledgerSnapshot());
        bankRepo.save(bs);

        LuxurySnapshot ls = new LuxurySnapshot();
        ls.listings = new ArrayList<>(luxury.snapshot());
        luxuryRepo.save(ls);

        MailboxSnapshot mbs = new MailboxSnapshot();
        mbs.entries = new ArrayList<>(mailbox.snapshot());
        mailboxRepo.save(mbs);

        BondSnapshot bds = new BondSnapshot();
        bds.bonds = new ArrayList<>(bonds.snapshot());
        bondRepo.save(bds);

        FuturesSnapshot fs = new FuturesSnapshot();
        fs.contracts = new ArrayList<>(futures.snapshot());
        fs.positions = new ArrayList<>(futures.positionsSnapshot());
        futuresRepo.save(fs);

        OptionsSnapshot os = new OptionsSnapshot();
        os.contracts = new ArrayList<>(options.snapshot());
        optionsRepo.save(os);

        PriceAnchorSnapshot ps = new PriceAnchorSnapshot();
        ps.recent.putAll(priceAnchor.snapshot());
        pricesRepo.save(ps);

        GoldSnapshot gs = new GoldSnapshot();
        gs.seeded = gold.isSeeded();
        gs.holdings.putAll(gold.snapshot());
        goldRepo.save(gs);
    }

    /** 禁用时同步落盘并关闭 IO。 */
    public void flush() {
        try {
            io.submit(this::save).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().severe("Flush failed: " + e.getMessage());
        } finally {
            io.shutdownNow();
        }
    }
}
