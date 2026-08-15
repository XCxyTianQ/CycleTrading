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
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * JSON 持久化：market.json（挂单+邮箱）与 bank.json（账户+流水）。
 * 单线程 IO 执行器 + 原子写（tmp + ATOMIC_MOVE）；变更异步落盘，禁用时同步 flush。
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

    /** 死亡保险已移除（v1.1）；旧 insurance.json 文件将被忽略。 */

    /** 邮箱快照。 */
    public static final class MailboxSnapshot {
        public List<MailEntry> entries = new ArrayList<>();
    }

    /** 定期债券快照。 */
    public static final class BondSnapshot {
        public List<Bond> bonds = new ArrayList<>();
    }

    /** 期货合约快照。 */
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
        public Map<String, List<Long>> recent = new java.util.HashMap<>();
    }

    /** 金条快照。 */
    public static final class GoldSnapshot {
        public boolean seeded = false;
        public Map<String, Long> holdings = new java.util.HashMap<>();
    }

    private final CycleTradingPlugin plugin;
    private final Path marketFile;
    private final Path bankFile;
    private final Path luxuryFile;
    private final Path mailboxFile;
    private final Path bondFile;
    private final Path futuresFile;
    private final Path optionsFile;
    private final Path pricesFile;
    private final Path goldFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
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
        this.marketFile = dataDir.resolve("market.json");
        this.bankFile = dataDir.resolve("bank.json");
        this.luxuryFile = dataDir.resolve("luxury.json");
        this.mailboxFile = dataDir.resolve("mailbox.json");
        this.bondFile = dataDir.resolve("bonds.json");
        this.futuresFile = dataDir.resolve("futures.json");
        this.optionsFile = dataDir.resolve("options.json");
        this.pricesFile = dataDir.resolve("prices.json");
        this.goldFile = dataDir.resolve("gold.json");
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

    /** 启动时加载存档。文件不存在或损坏时从空状态开始（损坏文件保留 .corrupt 备份）。 */
    public void load() {
        loadMarket();
        loadBank();
        loadLuxury();
        loadMailbox();
        loadBonds();
        loadFutures();
        loadOptions();
        loadPrices();
        loadGold();
    }

    private void loadMarket() {
        if (!Files.exists(marketFile)) {
            return;
        }
        try {
            String raw = Files.readString(marketFile);
            MarketSnapshot snap = gson.fromJson(raw, MarketSnapshot.class);
            if (snap == null) {
                return;
            }
            if (snap.listings != null) {
                for (Listing l : snap.listings) {
                    market.restoreListing(l);
                }
            }
            if (snap.mailbox != null) {
                for (MailEntry m : snap.mailbox) {
                    mailbox.restore(m);
                }
                if (!snap.mailbox.isEmpty()) {
                    plugin.getLogger().info("Migrated " + snap.mailbox.size() + " legacy mailbox entries");
                }
            }
            market.rebuildNextId();
            plugin.getLogger().info("Data loaded: " + (snap.listings == null ? 0 : snap.listings.size()) + " listings");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load market data: " + e.getMessage());
            quarantine(marketFile);
        }
    }

    private void loadBank() {
        if (!Files.exists(bankFile)) {
            return;
        }
        try {
            String raw = Files.readString(bankFile);
            BankSnapshot snap = gson.fromJson(raw, BankSnapshot.class);
            if (snap == null) {
                return;
            }
            if (snap.accounts != null) {
                for (BankAccount a : snap.accounts) {
                    bank.restoreAccount(a);
                }
            }
            if (snap.ledger != null) {
                for (TxEntry t : snap.ledger) {
                    bank.restoreTx(t);
                }
            }
            bank.rebuildTxId();
            bank.rebuildSupply();
            plugin.getLogger().info("Bank loaded: " + (snap.accounts == null ? 0 : snap.accounts.size())
                    + " accounts, " + (snap.ledger == null ? 0 : snap.ledger.size())
                    + " ledger entries, player supply " + bank.playerSupply());
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load bank data: " + e.getMessage());
            quarantine(bankFile);
        }
    }

    private void loadLuxury() {
        if (!Files.exists(luxuryFile)) {
            return;
        }
        try {
            String raw = Files.readString(luxuryFile);
            LuxurySnapshot snap = gson.fromJson(raw, LuxurySnapshot.class);
            if (snap == null) {
                return;
            }
            if (snap.listings != null) {
                for (LuxuryListing l : snap.listings) {
                    luxury.restore(l);
                }
            }
            luxury.rebuildNextId();
            plugin.getLogger().info("Luxury loaded: " + (snap.listings == null ? 0 : snap.listings.size()) + " listings");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load luxury data: " + e.getMessage());
            quarantine(luxuryFile);
        }
    }

    private void loadMailbox() {
        if (!Files.exists(mailboxFile)) {
            return;
        }
        try {
            String raw = Files.readString(mailboxFile);
            MailboxSnapshot snap = gson.fromJson(raw, MailboxSnapshot.class);
            if (snap == null) {
                return;
            }
            if (snap.entries != null) {
                for (MailEntry m : snap.entries) {
                    mailbox.restore(m);
                }
            }
            plugin.getLogger().info("Mailbox loaded: " + (snap.entries == null ? 0 : snap.entries.size()) + " entries");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load mailbox data: " + e.getMessage());
            quarantine(mailboxFile);
        }
    }

    private void loadBonds() {
        if (!Files.exists(bondFile)) {
            return;
        }
        try {
            String raw = Files.readString(bondFile);
            BondSnapshot snap = gson.fromJson(raw, BondSnapshot.class);
            if (snap == null) {
                return;
            }
            if (snap.bonds != null) {
                for (Bond b : snap.bonds) {
                    bonds.restore(b);
                }
            }
            bonds.rebuildNextId();
            plugin.getLogger().info("Bonds loaded: " + (snap.bonds == null ? 0 : snap.bonds.size())
                    + " bonds, " + bonds.activeCount() + " active");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load bonds data: " + e.getMessage());
            quarantine(bondFile);
        }
    }

    private void loadFutures() {
        if (!Files.exists(futuresFile)) {
            return;
        }
        try {
            String raw = Files.readString(futuresFile);
            FuturesSnapshot snap = gson.fromJson(raw, FuturesSnapshot.class);
            if (snap == null) {
                return;
            }
            if (snap.contracts != null) {
                for (FuturesContract c : snap.contracts) {
                    futures.restore(c);
                }
            }
            if (snap.positions != null) {
                for (FuturesPosition p : snap.positions) {
                    futures.restorePosition(p);
                }
            }
            futures.rebuildNextId();
            futures.rebuildPosId();
            plugin.getLogger().info("Futures loaded: " + (snap.contracts == null ? 0 : snap.contracts.size())
                    + " contracts, " + futures.countByStatus(FuturesContract.OPEN) + " open, "
                    + futures.countByStatus(FuturesContract.LOCKED) + " locked | "
                    + futures.posCountByStatus(FuturesPosition.OPEN) + " positions open");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load futures data: " + e.getMessage());
            quarantine(futuresFile);
        }
    }

    private void loadOptions() {
        if (!Files.exists(optionsFile)) {
            return;
        }
        try {
            String raw = Files.readString(optionsFile);
            OptionsSnapshot snap = gson.fromJson(raw, OptionsSnapshot.class);
            if (snap == null) {
                return;
            }
            if (snap.contracts != null) {
                for (OptionContract c : snap.contracts) {
                    options.restore(c);
                }
            }
            options.rebuildNextId();
            plugin.getLogger().info("Options loaded: " + (snap.contracts == null ? 0 : snap.contracts.size())
                    + " contracts, " + options.countByStatus(OptionContract.OPEN) + " open, "
                    + options.countByStatus(OptionContract.LOCKED) + " locked");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load options data: " + e.getMessage());
            quarantine(optionsFile);
        }
    }

    private void loadPrices() {
        if (!Files.exists(pricesFile)) {
            return;
        }
        try {
            String raw = Files.readString(pricesFile);
            PriceAnchorSnapshot snap = gson.fromJson(raw, PriceAnchorSnapshot.class);
            if (snap != null && snap.recent != null) {
                priceAnchor.restore(snap.recent);
            }
            plugin.getLogger().info("PriceAnchor loaded: " + (snap == null || snap.recent == null ? 0 : snap.recent.size())
                    + " items with market history");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load prices data: " + e.getMessage());
            quarantine(pricesFile);
        }
    }

    private void loadGold() {
        if (!Files.exists(goldFile)) {
            return;
        }
        try {
            String raw = Files.readString(goldFile);
            GoldSnapshot snap = gson.fromJson(raw, GoldSnapshot.class);
            if (snap != null) {
                gold.restore(snap.holdings, snap.seeded);
            }
            plugin.getLogger().info("Gold loaded: " + gold.outstanding() + " bars outstanding, treasury " + gold.treasury());
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load gold data: " + e.getMessage());
            quarantine(goldFile);
        }
    }

    private void quarantine(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName().toString() + ".corrupt-" + System.currentTimeMillis()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // 保留原文件
        }
    }

    /** 请求异步落盘（市场或银行变更后调用）。 */
    public void requestSave() {
        if (market == null || bank == null || io.isShutdown()) {
            return;
        }
        io.submit(this::save);
    }

    private void save() {
        MarketSnapshot ms = new MarketSnapshot();
        ms.listings = new ArrayList<>(market.listingsSnapshot());
        writeJson(marketFile, ms);

        BankSnapshot bs = new BankSnapshot();
        bs.accounts = new ArrayList<>(bank.accountsSnapshot());
        bs.ledger = new ArrayList<>(bank.ledgerSnapshot());
        writeJson(bankFile, bs);

        LuxurySnapshot ls = new LuxurySnapshot();
        ls.listings = new ArrayList<>(luxury.snapshot());
        writeJson(luxuryFile, ls);

        MailboxSnapshot mbs = new MailboxSnapshot();
        mbs.entries = new ArrayList<>(mailbox.snapshot());
        writeJson(mailboxFile, mbs);

        BondSnapshot bds = new BondSnapshot();
        bds.bonds = new ArrayList<>(bonds.snapshot());
        writeJson(bondFile, bds);

        FuturesSnapshot fs = new FuturesSnapshot();
        fs.contracts = new ArrayList<>(futures.snapshot());
        fs.positions = new ArrayList<>(futures.positionsSnapshot());
        writeJson(futuresFile, fs);

        OptionsSnapshot os = new OptionsSnapshot();
        os.contracts = new ArrayList<>(options.snapshot());
        writeJson(optionsFile, os);

        PriceAnchorSnapshot ps = new PriceAnchorSnapshot();
        ps.recent.putAll(priceAnchor.snapshot());
        writeJson(pricesFile, ps);

        GoldSnapshot gs = new GoldSnapshot();
        gs.seeded = gold.isSeeded();
        gs.holdings.putAll(gold.snapshot());
        writeJson(goldFile, gs);
    }

    private void writeJson(Path file, Object obj) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.writeString(tmp, gson.toJson(obj));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to write data file " + file.getFileName() + ": " + e.getMessage());
        }
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
