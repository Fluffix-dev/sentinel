/*
 * Copyright (c) 2025 FluffixYT
 *
 * Alle Rechte vorbehalten.
 *
 * Diese Datei ist Teil des Projekts sentinel.
 *
 * Die Nutzung, Vervielfältigung oder Verbreitung ohne vorherige schriftliche
 * Genehmigung des Rechteinhabers ist nicht gestattet.
 * 09/2025
 */

package dev.fluffix.sentinel.commands;

import dev.fluffix.sentinel.ban.Ban;
import dev.fluffix.sentinel.ban.BanManager;
import dev.fluffix.sentinel.logger.PluginLogger;
import dev.fluffix.sentinel.message.MessageHandler;
import dev.fluffix.sentinel.message.MessageKeys;
import dev.fluffix.sentinel.plugin.SentinelPaper;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class UnBanCommand implements CommandExecutor {

    private final MessageHandler messages = SentinelPaper.getInstance().getMessageHandler();
    private final BanManager banManager = SentinelPaper.getInstance().getBanManager();

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            PluginLogger.printWithLabel("SENTINEL", "Der Command kann nur von einem Spieler ausgeführt werden", "RED");
            return true;
        }

        if (!player.hasPermission("sentinel.unban")) {
            messages.sendWithPrefix(player, MessageKeys.NO_PERMISSION.key());
            return true;
        }

        if (args.length < 1) {
            messages.sendWithPrefix(player, MessageKeys.UNBAN_USAGE.key(),
                    Placeholder.unparsed("label", label));
            return true;
        }

        String target = args[0];

        try {
            boolean success = false;

            if (isNumeric(target)) {
                long banId = Long.parseLong(target);
                success = banManager.unban(banId);
                if (success) {
                    messages.sendWithPrefix(player, MessageKeys.UNBAN_SUCCESS.key(),
                            Placeholder.unparsed("target", "#" + banId));
                    return true;
                }
            } else {
                UUID uuid = tryParseUuid(target);
                if (uuid != null) {
                    int count = banManager.unbanAll(uuid);
                    success = count > 0;
                } else {
                    List<Ban> bans = banManager.listAll(true);
                    Ban found = bans.stream()
                            .filter(b -> Objects.equals(b.getName(), target))
                            .findFirst().orElse(null);
                    if (found != null) {
                        success = banManager.unban(found.getId());
                    }
                }

                if (success) {
                    messages.sendWithPrefix(player, MessageKeys.UNBAN_SUCCESS.key(),
                            Placeholder.unparsed("target", target));
                    return true;
                }
            }
            messages.sendWithPrefix(player, MessageKeys.UNBAN_NOT_FOUND.key(),
                    Placeholder.unparsed("target", target));

        } catch (SQLException e) {
            messages.sendWithPrefix(player, MessageKeys.BAN_SQL_ERROR.key(),
                    Placeholder.unparsed("error", e.getMessage()));
            e.printStackTrace();
        }

        return true;
    }

    private static boolean isNumeric(String s) {
        try {
            Long.parseLong(s);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static UUID tryParseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (Exception ignored) {
            return null;
        }
    }
}
