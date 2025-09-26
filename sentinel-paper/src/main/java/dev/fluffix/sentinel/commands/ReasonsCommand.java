package dev.fluffix.sentinel.commands;

import dev.fluffix.sentinel.logger.PluginLogger;
import dev.fluffix.sentinel.plugin.SentinelPaper;
import dev.fluffix.sentinel.reasons.Reason;
import dev.fluffix.sentinel.reasons.ReasonManager;
import dev.fluffix.sentinel.reasons.ReasonType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

public class ReasonsCommand implements CommandExecutor {

    private final ReasonManager reasonManager = SentinelPaper.getInstance().getReasonManager();


    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!(sender instanceof Player)) {
            PluginLogger.printWithLabel("SENTINEL", "Diesen Command dürfen nur spieler nutzen", "YELLOW");
            return true;
        }

        Player player = (Player) sender;


        if (!player.hasPermission("sentinel.reasons")) {
            player.sendMessage("config init");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§cVerwendung: /reasons <add|remove|list>");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "add" -> handleAdd(sender, args);
                case "remove" -> handleRemove(sender, args);
                case "list" -> handleList(sender, args);
                default -> sender.sendMessage("§cUnbekanntes Subcommand: " + sub);
            }
        } catch (SQLException e) {
            sender.sendMessage("§cMySQL-Fehler: " + e.getMessage());
            e.printStackTrace();
        }
        return true;
    }

    private void handleAdd(CommandSender sender, String[] args) throws SQLException {
        if (args.length < 4) {
            sender.sendMessage("§cVerwendung: /reasons add <Name> <Typ> <DauerSekunden>");
            return;
        }
        String name = args[1];
        ReasonType type;
        try {
            type = ReasonType.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            sender.sendMessage("§cUngültiger Typ. Erlaubt: BAN, MUTE, REPORT");
            return;
        }
        long duration;
        try {
            duration = Long.parseLong(args[3]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("§cDauer muss eine Zahl sein (Sekunden)");
            return;
        }

        if (reasonManager.exists(name, type)) {
            sender.sendMessage("§eEs existiert bereits ein Reason '" + name + "' vom Typ " + type);
            return;
        }

        reasonManager.save(name, type, duration);
        sender.sendMessage("§aReason hinzugefügt: " + name + " (" + type + ", Dauer=" + duration + "s)");
    }

    private void handleRemove(CommandSender sender, String[] args) throws SQLException {
        if (args.length < 3) {
            sender.sendMessage("§cVerwendung: /reasons remove <Name> <Typ>");
            return;
        }
        String name = args[1];
        ReasonType type;
        try {
            type = ReasonType.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            sender.sendMessage("§cUngültiger Typ. Erlaubt: BAN, MUTE, REPORT");
            return;
        }

        if (!reasonManager.exists(name, type)) {
            sender.sendMessage("§eKein Reason '" + name + "' vom Typ " + type + " gefunden.");
            return;
        }

        reasonManager.delete(name, type);
        sender.sendMessage("§aReason entfernt: " + name + " (" + type + ")");
    }

    private void handleList(CommandSender sender, String[] args) throws SQLException {
        ReasonType filter = null;
        if (args.length >= 2) {
            try {
                filter = ReasonType.valueOf(args[1].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                sender.sendMessage("§cUngültiger Typ für Filter. Erlaubt: BAN, MUTE, REPORT");
                return;
            }
        }

        List<Reason> reasons = reasonManager.loadAll(filter);
        if (reasons.isEmpty()) {
            sender.sendMessage("§7Keine Reasons gefunden.");
            return;
        }

        sender.sendMessage("§a--- Reasons " + (filter != null ? "(" + filter + ")" : "") + " ---");
        for (Reason r : reasons) {
            String dur = r.getDurationSeconds() == 0 ? "permanent" : r.getDurationSeconds() + "s";
            sender.sendMessage("§e" + r.getName() + " §7[" + r.getType() + "] §f" + dur);
        }
    }
}
