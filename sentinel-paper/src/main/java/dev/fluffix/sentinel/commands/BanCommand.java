package dev.fluffix.sentinel.commands;

import dev.fluffix.sentinel.ban.Ban;
import dev.fluffix.sentinel.ban.BanManager;
import dev.fluffix.sentinel.message.MessageHandler;
import dev.fluffix.sentinel.message.MessageKeys;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

public class BanCommand implements CommandExecutor {

    private final BanManager banManager;
    private final MessageHandler messages;

    public BanCommand(BanManager banManager, MessageHandler messages) {
        this.banManager = Objects.requireNonNull(banManager, "banManager");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage("[Sentinel] Dieser Befehl ist nur für Spieler.");
            return true;
        }

        if (!player.hasPermission("sentinel.ban")) {
            messages.sendWithPrefix(player, MessageKeys.NO_PERMISSION.key());
            return true;
        }

        if (args.length < 2) {
            sendUsage(player, label);
            return true;
        }

        final String target = args[0];
        final List<String> reasonsList = Arrays.stream(args[1].split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        final String notice = (args.length > 2)
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : "";

        final String operator = player.getName();

        try {
            // Ban (Auto-Dauer aus Reasons)
            Ban ban = banManager.banOfflineAuto(target, operator, reasonsList, notice);

            // Erfolgsmeldung für den Ausführenden
            String reasonsJoined = String.join(", ", reasonsList);
            String durationPretty = (ban.getRemainingSeconds() == 0)
                    ? "permanent"
                    : (ban.getRemainingSeconds() + "s");

            messages.sendWithPrefix(player,
                    MessageKeys.BAN_SUCCESS.key(),
                    Placeholder.unparsed("target", ban.getName()),
                    Placeholder.unparsed("operator", operator),
                    Placeholder.unparsed("reasons", reasonsJoined),
                    Placeholder.unparsed("duration", durationPretty),
                    Placeholder.unparsed("notice", notice));

            // === NEU: Wenn Ziel online ist -> sofort kicken mit ban_kick ===
            Player targetPlayer = null;
            if (ban.getUniqueId() != null) {
                targetPlayer = Bukkit.getPlayer(ban.getUniqueId());
            }
            if (targetPlayer == null) {
                // Fallback über Namen
                targetPlayer = Bukkit.getPlayerExact(ban.getName());
            }

            if (targetPlayer != null && targetPlayer.isOnline()) {
                // Kick-Message rendern
                var kickMsg = messages.render(
                        MessageKeys.BAN_KICK.key(),
                        Placeholder.unparsed("player", targetPlayer.getName()),
                        Placeholder.unparsed("reasons", reasonsJoined),
                        Placeholder.unparsed("duration", (ban.getRemainingSeconds() == 0) ? "permanent" : durationPretty),
                        Placeholder.unparsed("operator", operator),
                        Placeholder.unparsed("notice", notice == null ? "" : notice)
                );
                targetPlayer.kick(kickMsg);
            }

        } catch (IllegalStateException | IllegalArgumentException ex) {
            messages.sendWithPrefix(player,
                    MessageKeys.BAN_ERROR.key(),
                    Placeholder.unparsed("error", ex.getMessage() == null ? "Unbekannter Fehler" : ex.getMessage()));
        } catch (SQLException sql) {
            messages.sendWithPrefix(player,
                    MessageKeys.BAN_SQL_ERROR.key(),
                    Placeholder.unparsed("error", sql.getMessage() == null ? "SQL-Fehler" : sql.getMessage()));
            sql.printStackTrace();
        }

        return true;
    }

    private void sendUsage(Player player, String label) {
        messages.sendWithPrefix(player,
                MessageKeys.BAN_USAGE.key(),
                Placeholder.unparsed("label", label));
    }
}
