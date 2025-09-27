package dev.fluffix.sentinel.commands;

import dev.fluffix.sentinel.ban.Ban;
import dev.fluffix.sentinel.ban.BanManager;
import dev.fluffix.sentinel.message.MessageHandler;
import dev.fluffix.sentinel.message.MessageKeys;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
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
            // Wenn nur Spieler nutzen dürfen:
            sender.sendMessage("[Sentinel] Dieser Befehl ist nur für Spieler.");
            return true;
        }

        if (!player.hasPermission("sentinel.ban")) {
            messages.sendWithPrefix(player, MessageKeys.NO_PERMISSION.key());
            return true; // WICHTIG: abbrechen
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
            // validiert Reasons (type=BAN), berechnet Dauer automatisch
            Ban ban = banManager.banOfflineAuto(target, operator, reasonsList, notice);

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
