package com.cycletrading.command;

import com.cycletrading.CycleTradingPlugin;
import com.cycletrading.core.Market;
import com.cycletrading.core.bank.Bank;
import com.cycletrading.core.bond.BondService;
import com.cycletrading.core.futures.FuturesService;
import com.cycletrading.core.gold.GoldService;
import com.cycletrading.core.luxury.LuxuryMarket;
import com.cycletrading.core.options.OptionsService;
import com.cycletrading.gui.GuiManager;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/**
 * /ct 命令分发器：薄壳，按子命令路由到各模块命令类。
 * market/sell/my/mail/collect → MarketCommands；bank → BankCommands；lux → LuxuryCommands；
 * bond → BondCommands；fut → FuturesCommands；opt → OptionsCommands；gold → GoldCommands；
 * cb → CbCommands；admin → AdminCommands。
 */
public final class CycleTradingCommand implements CommandExecutor, TabCompleter {

    private final CycleTradingPlugin plugin;
    private final MarketCommands marketCmds;
    private final BankCommands bankCmds;
    private final LuxuryCommands luxCmds;
    private final BondCommands bondCmds;
    private final FuturesCommands futCmds;
    private final OptionsCommands optCmds;
    private final GoldCommands goldCmds;
    private final CbCommands cbCmds;
    private final AdminCommands adminCmds;

    public CycleTradingCommand(CycleTradingPlugin plugin, Market market, Bank bank, LuxuryMarket luxury,
            BondService bonds, FuturesService futures, OptionsService options, GoldService gold, GuiManager guis) {
        this.plugin = plugin;
        this.marketCmds = new MarketCommands(plugin, market, guis);
        this.bankCmds = new BankCommands(plugin, bank);
        this.luxCmds = new LuxuryCommands(plugin, luxury, bank, guis);
        this.bondCmds = new BondCommands(plugin, bonds, guis);
        this.futCmds = new FuturesCommands(plugin, futures, bank, guis);
        this.optCmds = new OptionsCommands(plugin, options, bank, guis);
        this.goldCmds = new GoldCommands(plugin, gold);
        this.cbCmds = new CbCommands(plugin, bank, luxury, bonds, futures, gold);
        this.adminCmds = new AdminCommands(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "market" -> marketCmds.market(sender, args);
            case "sell" -> marketCmds.sell(sender, args);
            case "my" -> marketCmds.my(sender);
            case "collect" -> marketCmds.collect(sender);
            case "mail" -> marketCmds.mail(sender);
            case "bank" -> bankCmds.bank(sender, args);
            case "lux" -> luxCmds.lux(sender, args);
            case "bond" -> bondCmds.bond(sender, args);
            case "fut" -> futCmds.fut(sender, args);
            case "opt" -> optCmds.opt(sender, args);
            case "gold" -> goldCmds.gold(sender, args);
            case "cb" -> cbCmds.cb(sender, args);
            case "admin" -> adminCmds.admin(sender, args);
            default -> sender.sendMessage("§c未知子命令，输入 /ct help 查看帮助");
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage("§e===== CycleTrading 经济系统 =====");
        s.sendMessage("§6/ct market [页]   §7浏览市场并购买");
        s.sendMessage("§6/ct sell <价格>  §7手持物品上架（价格单位：绿宝石）");
        s.sendMessage("§6/ct my           §7查看并下架自己的挂单");
        s.sendMessage("§6/ct mail         §7邮箱（只收不存，上限 " + plugin.mailbox().capacity() + "，点击领取）");
        s.sendMessage("§6/ct collect      §7一键领取邮箱");
        s.sendMessage("§6/ct bank         §7查看银行余额");
        s.sendMessage("§6/ct bank deposit [数量|all]  §7实物绿宝石存入银行");
        s.sendMessage("§6/ct bank withdraw <数量|all> §7从银行提取实物绿宝石");
        s.sendMessage("§6/ct bank send <玩家> <数量>  §7虚拟绿宝石转账");
        s.sendMessage("§6/ct bank ledger [条数] §7个人流水");
        s.sendMessage("§6/ct lux [页]    §7奢侈品商店（动态定价，仅管理员挂售）");
        s.sendMessage("§6/ct lux status  §7查看经济总存量与当前倍率");
        s.sendMessage("§6/ct bond        §7我的定期债券（到期自动结算）");
        s.sendMessage("§6/ct bond info   §7五档利率/期限/最低购买量");
        s.sendMessage("§6/ct bond buy <档位> <金额> §7购买定期债券（仅虚拟余额）");
        s.sendMessage("§6/ct fut [页]    §7期货市场（标准大宗合约）");
        s.sendMessage("§6/ct fut help    §7期货通俗指南（含多空单）");
        s.sendMessage("§6/ct fut open <价格> <期限> §7手持标准数量商品开仓");
        s.sendMessage("§6/ct fut long|short <品种> <数量> <期限> §7开多/空单（保证金交易）");
        s.sendMessage("§6/ct fut pos / close <编号> §7我的头寸 / 提前平仓");
        s.sendMessage("§6/ct opt [页]    §7期权市场（看涨/看跌，现金结算）");
        s.sendMessage("§6/ct opt help    §7期权通俗指南");
        s.sendMessage("§6/ct opt open <call|put> <品种> <行权价> <权利金> <期限> §7开仓卖期权");
        s.sendMessage("§6/ct gold        §7金条行情（恒定发行，价格挂钩国库）");
        s.sendMessage("§6/ct gold buy/sell <数量> §7即买即卖金条（仅虚拟余额）");
        if (s.hasPermission("cycletrading.admin")) {
            s.sendMessage("§c/ct cb report  §7中央银行经济公报");
            s.sendMessage("§c/ct cb distribute <金额> | grant <玩家> <金额> | tax <玩家> <金额>");
            s.sendMessage("§c/ct cb anchor <lux|bond> <锚点>  §7利率决议（0=恢复配置）");
            s.sendMessage("§c/ct lux list <基础价>  §7手持珍稀物品挂售（仅管理员）");
            s.sendMessage("§c/ct lux remove <编号> §7下架奢侈品");
            s.sendMessage("§c/ct bond admin stats|view <玩家>");
            s.sendMessage("§c/ct fut admin stats|deliver <编号>|cancel <编号>");
            s.sendMessage("§c/ct opt admin stats|settle <编号>|cancel <编号>");
            s.sendMessage("§c/ct bank admin view|set|add|remove|freeze|unfreeze|ledger");
            s.sendMessage("§c/ct admin reload");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("market", "sell", "my", "collect", "mail", "bank", "lux", "bond", "fut", "opt", "gold", "help");
        }
        List<String> out = new ArrayList<>();
        switch (args[0].toLowerCase()) {
            case "bank" -> out.addAll(bankCmds.complete(args));
            case "lux" -> out.addAll(luxCmds.complete(args));
            case "bond" -> out.addAll(bondCmds.complete(args));
            case "fut" -> out.addAll(futCmds.complete(args));
            case "opt" -> out.addAll(optCmds.complete(args));
            case "gold" -> out.addAll(goldCmds.complete(args));
            case "cb" -> out.addAll(cbCmds.complete(args));
            default -> {
            }
        }
        return out;
    }
}
