package com.cycletrading.command;

import com.cycletrading.CycleTradingPlugin;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** 命令层共享助手。 */
public final class Cmd {

    private Cmd() {
    }

    public static boolean requirePlayer(CommandSender s) {
        if (s instanceof Player) {
            return true;
        }
        s.sendMessage("§c该命令只能由玩家执行");
        return false;
    }

    public static Long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static UUID resolveUuid(CycleTradingPlugin plugin, String name) {
        Player on = plugin.getServer().getPlayerExact(name);
        if (on != null) {
            return on.getUniqueId();
        }
        return Bukkit.getOfflinePlayer(name).getUniqueId();
    }
}
