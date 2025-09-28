package dev.fluffix.sentinel.commands;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.fluffix.sentinel.message.MessageHandler;
import dev.fluffix.sentinel.message.MessageKeys;
import dev.fluffix.sentinel.reasons.Reason;
import dev.fluffix.sentinel.reasons.ReasonManager;
import dev.fluffix.sentinel.reasons.ReasonType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class ReasonsCommand implements SimpleCommand {

    private final ReasonManager reasonManager;
    private final MessageHandler messages;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public ReasonsCommand(ReasonManager reasonManager, MessageHandler messages) {
        this.reasonManager = Objects.requireNonNull(reasonManager, "reasonManager");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    @Override
    public void execute(Invocation invocation) {
        var source = invocation.source();
        String[] args = invocation.arguments();

        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("[Sentinel] Diesen Command dürfen nur Spieler nutzen"));
            return;
        }

        if (!player.hasPermission("sentinel.reasons")) {
            messages.sendWithPrefix(player, MessageKeys.NO_PERMISSION.key());
            return;
        }

        if (args.length == 0) {
            player.sendMessage(messages.prefix().append(
                    mm.deserialize("<red>Verwendung:</red> /reasons <white><add|remove|list></white>")
            ));
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "add" -> handleAdd(player, args);
                case "remove" -> handleRemove(player, args);
                case "list" -> handleList(player, args);
                default -> player.sendMessage(messages.prefix().append(
                        mm.deserialize("<red>Unbekanntes Subcommand:</red> <white>" + sub + "</white>")
                ));
            }
        } catch (SQLException e) {
            player.sendMessage(messages.prefix().append(
                    mm.deserialize("<red>MySQL-Fehler:</red> <gray>(" + e.getMessage() + ")</gray>")
            ));
            e.printStackTrace();
        }
    }

    private void handleAdd(Player player, String[] args) throws SQLException {
        if (args.length < 4) {
            player.sendMessage(messages.prefix().append(
                    mm.deserialize("<red>Verwendung:</red> /reasons add <white><Name> <Typ> <DauerSekunden></white>")
            ));
            return;
        }
        String name = args[1];

        ReasonType type;
        try {
            type = ReasonType.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            player.sendMessage(messages.prefix().append(
                    mm.deserialize("<red>Ungültiger Typ.</red> Erlaubt: <white>BAN, MUTE, REPORT</white>")
            ));
            return;
        }

        long duration;
        try {
            duration = Long.parseLong(args[3]);
        } catch (NumberFormatException ex) {
            player.sendMessage(messages.prefix().append(
                    mm.deserialize("<red>Dauer muss eine Zahl sein</red> <gray>(Sekunden)</gray>")
            ));
            return;
        }

        if (reasonManager.exists(name, type)) {
            player.sendMessage(messages.prefix().append(
                    mm.deserialize("<yellow>Es existiert bereits ein Reason</yellow> '<white>" + name + "</white>' <gray>(</gray>" + type + "<gray>)</gray>")
            ));
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
            player.sendMessage(messages.prefix().append(
                    mm.deserialize("<red>Verwendung:</red> /reasons remove <white><Name> <Typ></white>")
            ));
            return;
        }
        String name = args[1];

        ReasonType type;
        try {
            type = ReasonType.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            player.sendMessage(messages.prefix().append(
                    mm.deserialize("<red>Ungültiger Typ.</red> Erlaubt: <white>BAN, MUTE, REPORT</white>")
            ));
            return;
        }

        if (!reasonManager.exists(name, type)) {
            player.sendMessage(messages.prefix().append(
                    mm.deserialize("<yellow>Kein Reason</yellow> '<white>" + name + "</white>' <gray>(</gray>" + type + "<gray>)</gray> <yellow>gefunden.</yellow>")
            ));
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
                player.sendMessage(messages.prefix().append(
                        mm.deserialize("<red>Ungültiger Typ für Filter.</red> Erlaubt: <white>BAN, MUTE, REPORT</white>")
                ));
                return;
            }
        }

        List<Reason> reasons = reasonManager.loadAll(filter);
        if (reasons.isEmpty()) {
            player.sendMessage(messages.prefix().append(
                    mm.deserialize("<gray>Keine Reasons gefunden.</gray>")
            ));
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

    // --- optionale Tab-Vervollständigung ---
    @Override
    public List<String> suggest(Invocation invocation) {
        var source = invocation.source();
        String[] args = invocation.arguments();

        if (!(source instanceof Player p) || !p.hasPermission("sentinel.reasons")) {
            return List.of();
        }

        if (args.length == 1) {
            return Arrays.asList("add", "remove", "list").stream()
                    .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        if (args.length == 3 && ("add".equalsIgnoreCase(args[0]) || "remove".equalsIgnoreCase(args[0]))) {
            return Arrays.stream(ReasonType.values())
                    .map(e -> e.name().toLowerCase(Locale.ROOT))
                    .filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        if (args.length == 2 && "list".equalsIgnoreCase(args[0])) {
            return Arrays.stream(ReasonType.values())
                    .map(Enum::name)
                    .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        return List.of();
    }
}
