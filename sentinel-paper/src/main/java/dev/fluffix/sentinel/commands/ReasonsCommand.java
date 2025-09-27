package dev.fluffix.sentinel.commands;

import dev.fluffix.sentinel.logger.PluginLogger;
import dev.fluffix.sentinel.plugin.SentinelPaper;
import dev.fluffix.sentinel.reasons.Reason;
import dev.fluffix.sentinel.reasons.ReasonManager;
import dev.fluffix.sentinel.reasons.ReasonType;
import dev.fluffix.sentinel.message.MessageHandler;
import dev.fluffix.sentinel.message.MessageKeys;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

public class ReasonsCommand implements CommandExecutor {

    private final ReasonManager reasonManager = SentinelPaper.getInstance().getReasonManager();
    private final MessageHandler messages = SentinelPaper.getInstance().getMessageHandler();

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!(sender instanceof Player player)) {
            PluginLogger.printWithLabel("SENTINEL", "Diesen Command dürfen nur Spieler nutzen", "YELLOW");
            return true;
        }

        if (!player.hasPermission("sentinel.reasons")) {
            messages.sendWithPrefix(player, MessageKeys.NO_PERMISSION.key());
            return true;
        }

        if (args.length == 0) {
            player.sendMessage("§cVerwendung: /reasons <add|remove|list>");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "add" -> handleAdd(player, args);
                case "remove" -> handleRemove(player, args);
                case "list" -> handleList(player, args);
                default -> player.sendMessage("§cUnbekanntes Subcommand: " + sub);
            }
        } catch (SQLException e) {
            player.sendMessage("§cMySQL-Fehler: " + e.getMessage());
            e.printStackTrace();
        }
        return true;
    }

    private void handleAdd(Player player, String[] args) throws SQLException {
        if (args.length < 4) {
            player.sendMessage("§cVerwendung: /reasons add <Name> <Typ> <DauerSekunden>");
            return;
        }
        String name = args[1];
        ReasonType type;
        try {
            type = ReasonType.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            player.sendMessage("§cUngültiger Typ. Erlaubt: BAN, MUTE, REPORT");
            return;
        }
        long duration;
        try {
            duration = Long.parseLong(args[3]);
        } catch (NumberFormatException ex) {
            player.sendMessage("§cDauer muss eine Zahl sein (Sekunden)");
            return;
        }

        if (reasonManager.exists(name, type)) {
            player.sendMessage("§eEs existiert bereits ein Reason '" + name + "' vom Typ " + type);
            return;
        }

        reasonManager.save(name, type, duration);

        messages.sendWithPrefix(
                player,
                MessageKeys.REASONS_ADDED.key(),
                Placeholder.unparsed("name", name),
                Placeholder.unparsed("type", type.name()),
                Placeholder.unparsed("duration", duration == 0 ? "permanent" : duration + "s")
        );
    }

    private void handleRemove(Player player, String[] args) throws SQLException {
        if (args.length < 3) {
            player.sendMessage("§cVerwendung: /reasons remove <Name> <Typ>");
            return;
        }
        String name = args[1];
        ReasonType type;
        try {
            type = ReasonType.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            player.sendMessage("§cUngültiger Typ. Erlaubt: BAN, MUTE, REPORT");
            return;
        }

        if (!reasonManager.exists(name, type)) {
            player.sendMessage("§eKein Reason '" + name + "' vom Typ " + type + " gefunden.");
            return;
        }

        reasonManager.delete(name, type);

        messages.sendWithPrefix(
                player,
                MessageKeys.REASONS_REMOVED.key(),
                Placeholder.unparsed("name", name),
                Placeholder.unparsed("type", type.name())
        );
    }

    private void handleList(Player player, String[] args) throws SQLException {
        ReasonType filter = null;
        if (args.length >= 2) {
            try {
                filter = ReasonType.valueOf(args[1].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                player.sendMessage("§cUngültiger Typ für Filter. Erlaubt: BAN, MUTE, REPORT");
                return;
            }
        }

        List<Reason> reasons = reasonManager.loadAll(filter);
        if (reasons.isEmpty()) {
            player.sendMessage("§7Keine Reasons gefunden.");
            return;
        }

        messages.sendWithPrefix(
                player,
                MessageKeys.REASONS_HEADER.key(),
                Placeholder.unparsed("type", filter == null ? "ALLE" : filter.name())
        );

        for (Reason r : reasons) {
            String dur = r.getDurationSeconds() == 0 ? "permanent" : r.getDurationSeconds() + "s";
            messages.send(
                    player,
                    MessageKeys.REASONS_LINE.key(),
                    Placeholder.unparsed("name", r.getName()),
                    Placeholder.unparsed("type", r.getType().name()),
                    Placeholder.unparsed("duration", dur)
            );
        }
    }
}
