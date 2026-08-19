package com.cycletrading.command;

import com.cycletrading.CycleTradingPlugin;
import org.bukkit.command.CommandSender;

/** 管理命令：/ct admin reload。 */
public final class AdminCommands {

    private final CycleTradingPlugin plugin;

    public AdminCommands(CycleTradingPlugin plugin) {
        this.plugin = plugin;
    }

    public void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cycletrading.admin")) {
            sender.sendMessage("§c权限不足");
            return;
        }
        if (args.length > 1 && args[1].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            sender.sendMessage("§a配置已重载");
        } else {
            sender.sendMessage("§c用法: /ct admin reload");
        }
    }
}
