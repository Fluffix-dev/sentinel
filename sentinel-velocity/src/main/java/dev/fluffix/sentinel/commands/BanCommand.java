package dev.fluffix.sentinel.commands;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.fluffix.sentinel.ban.Ban;
import dev.fluffix.sentinel.ban.BanManager;
import dev.fluffix.sentinel.message.MessageHandler;
import dev.fluffix.sentinel.message.MessageKeys;
import dev.fluffix.sentinel.reasons.Reason;
import dev.fluffix.sentinel.reasons.ReasonManager;
import dev.fluffix.sentinel.reasons.ReasonType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Velocity-Port von BanCommand.
 * Registrierung: commandManager.register(meta("ban"), new BanCommandVelocity(server, banMgr, reasonMgr, messages));
 */
public class BanCommand implements SimpleCommand {

    private final ProxyServer server;
    private final BanManager banManager;
    private final ReasonManager reasonManager;
    private final MessageHandler messages;

    public BanCommand(ProxyServer server,
                              BanManager banManager,
                              ReasonManager reasonManager,
                              MessageHandler messages) {
        this.server = Objects.requireNonNull(server, "server");
        this.banManager = Objects.requireNonNull(banManager, "banManager");
        this.reasonManager = Objects.requireNonNull(reasonManager, "reasonManager");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    @Override
    public void execute(Invocation invocation) {
        var source = invocation.source();
        String[] args = invocation.arguments();
        String label = invocation.alias();

        // wie im Paper-Original: nur Spieler
        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("[Sentinel] Dieser Befehl ist nur für Spieler."));
            return;
        }

        if (!player.hasPermission("sentinel.ban")) {
            messages.sendWithPrefix(player, MessageKeys.NO_PERMISSION.key());
            return;
        }

        if (args.length == 0) {
            sendUsage(player, label);
            return;
        }

        if (args[0].equalsIgnoreCase("list")) {
            if (args.length < 2) {
                messages.sendWithPrefix(player, MessageKeys.BAN_LIST_USAGE.key(),
                        Placeholder.unparsed("label", label));
                return;
            }

            String target = args[1];
            if ("all".equalsIgnoreCase(target)) {
                if (!player.hasPermission("sentinel.banlist")) {
                    messages.sendWithPrefix(player, MessageKeys.NO_PERMISSION.key());
                    return;
                }
            } else {
                if (!player.hasPermission("sentinel.banlist.players")) {
                    messages.sendWithPrefix(player, MessageKeys.NO_PERMISSION.key());
                    return;
                }
            }

            handleList(player, target);
            return;
        }

        if (args.length < 2) {
            sendUsage(player, label);
            return;
        }

        final String target = args[0];

        if (target.equalsIgnoreCase(player.getUsername())) {
            messages.sendWithPrefix(player, MessageKeys.BAN_ERROR.key(),
                    Placeholder.unparsed("error", "Du kannst dich nicht selbst bannen."));
            return;
        }

        // Bypass prüfen (falls online)
        Optional<Player> targetPlayerOpt = server.getPlayer(target);
        if (targetPlayerOpt.isPresent() && targetPlayerOpt.get().hasPermission("sentinel.bypass")) {
            messages.sendWithPrefix(player, MessageKeys.BAN_ERROR.key(),
                    Placeholder.unparsed("error", "Dieser Spieler kann nicht gebannt werden."));
            return;
        }

        final List<String> reasonsList = Arrays.stream(args[1].split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        final String notice = (args.length > 2)
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : "";

        final String operator = player.getUsername();

        try {
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

            // ggf. online -> disconnect mit Kick-Nachricht
            Optional<Player> onlineByUuid = (ban.getUniqueId() != null) ? server.getPlayer(ban.getUniqueId()) : Optional.empty();
            Optional<Player> onlineByName = server.getPlayer(ban.getName() == null ? target : ban.getName());
            Optional<Player> onlineTarget = onlineByUuid.isPresent() ? onlineByUuid : onlineByName;

            onlineTarget.ifPresent(p -> {
                Component kickMsg = messages.render(
                        MessageKeys.BAN_KICK.key(),
                        Placeholder.unparsed("player", p.getUsername()),
                        Placeholder.unparsed("reasons", reasonsJoined),
                        Placeholder.unparsed("duration", durationPretty),
                        Placeholder.unparsed("operator", operator),
                        Placeholder.unparsed("notice", notice == null ? "" : notice)
                );
                p.disconnect(kickMsg);
            });

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
    }

    private void handleList(Player player, String target) {
        try {
            try { banManager.expireDueBans(); } catch (SQLException ignore) {}

            final Instant now = Instant.now();

            if ("all".equalsIgnoreCase(target)) {
                List<Ban> all = banManager.listAll(false).stream()
                        .limit(25)
                        .collect(Collectors.toList());

                if (all.isEmpty()) {
                    messages.sendWithPrefix(player, MessageKeys.BAN_LIST_EMPTY.key(),
                            Placeholder.unparsed("target", "ALL"));
                    return;
                }

                messages.sendWithPrefix(player, MessageKeys.BAN_LIST_HEADER.key(),
                        Placeholder.unparsed("target", "ALL"));

                for (Ban b : all) {
                    String dur = prettyRemaining(now, b.getExpiresAt());
                    boolean activeNow = isActiveNow(now, b.isActive(), b.getExpiresAt());
                    messages.send(player, MessageKeys.BAN_LIST_LINE.key(),
                            Placeholder.unparsed("id", String.valueOf(b.getId())),
                            Placeholder.unparsed("player", b.getName() == null ? "-" : b.getName()),
                            Placeholder.unparsed("operator", b.getOperator() == null ? "-" : b.getOperator()),
                            Placeholder.unparsed("reasons", String.join(", ", b.getReasons())),
                            Placeholder.unparsed("duration", dur),
                            Placeholder.unparsed("active", String.valueOf(activeNow)));
                }
                return;
            }

            UUID uuid = tryParseUuid(target);
            List<Ban> entries;
            if (uuid != null) {
                entries = banManager.listFor(uuid);
            } else {
                entries = banManager.listAll(false).stream()
                        .filter(b -> b.getName() != null && b.getName().equalsIgnoreCase(target))
                        .collect(Collectors.toList());
            }

            if (entries.isEmpty()) {
                messages.sendWithPrefix(player, MessageKeys.BAN_LIST_EMPTY.key(),
                        Placeholder.unparsed("target", target));
                return;
            }

            messages.sendWithPrefix(player, MessageKeys.BAN_LIST_HEADER.key(),
                    Placeholder.unparsed("target", target));

            for (Ban b : entries) {
                String dur = prettyRemaining(now, b.getExpiresAt());
                boolean activeNow = isActiveNow(now, b.isActive(), b.getExpiresAt());
                messages.send(player, MessageKeys.BAN_LIST_LINE.key(),
                        Placeholder.unparsed("id", String.valueOf(b.getId())),
                        Placeholder.unparsed("player", b.getName() == null ? "-" : b.getName()),
                        Placeholder.unparsed("operator", b.getOperator() == null ? "-" : b.getOperator()),
                        Placeholder.unparsed("reasons", String.join(", ", b.getReasons())),
                        Placeholder.unparsed("duration", dur),
                        Placeholder.unparsed("active", String.valueOf(activeNow)));
            }

        } catch (SQLException e) {
            messages.sendWithPrefix(player,
                    MessageKeys.BAN_SQL_ERROR.key(),
                    Placeholder.unparsed("error", e.getMessage() == null ? "SQL-Fehler" : e.getMessage()));
            e.printStackTrace();
        }
    }

    private static String prettyRemaining(Instant now, Instant expiresAt) {
        if (expiresAt == null) return "permanent";
        long sec = Math.max(0, Duration.between(now, expiresAt).getSeconds());
        return sec + "s";
    }

    private static boolean isActiveNow(Instant now, boolean activeFlag, Instant expiresAt) {
        if (!activeFlag) return false;
        if (expiresAt == null) return true; // permanent & aktiv
        return expiresAt.isAfter(now);
    }

    private void sendUsage(Player player, String label) {
        messages.sendWithPrefix(player,
                MessageKeys.BAN_USAGE.key(),
                Placeholder.unparsed("label", label));
    }

    private static UUID tryParseUuid(String s) {
        try { return UUID.fromString(s); } catch (Exception ignored) { return null; }
    }

    // --- Tab-Vervollständigung ---
    @Override
    public List<String> suggest(Invocation invocation) {
        var source = invocation.source();
        String[] args = invocation.arguments();

        if (!(source instanceof Player p) || !p.hasPermission("sentinel.ban")) {
            return Collections.emptyList();
        }

        try {
            if (args.length == 1) {
                Set<String> suggestions = new LinkedHashSet<>();
                suggestions.add("list");

                banManager.listAll(false).forEach(b -> {
                    if (b.getName() != null) suggestions.add(b.getName());
                });
                server.getAllPlayers().forEach(op -> suggestions.add(op.getUsername()));

                String prefix = args[0].toLowerCase(Locale.ROOT);
                return suggestions.stream()
                        .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList();
            }

            if (args.length == 2) {
                if ("list".equalsIgnoreCase(args[0])) {
                    Set<String> suggestions = new LinkedHashSet<>();
                    suggestions.add("all");

                    banManager.listAll(false).forEach(b -> {
                        if (b.getName() != null) suggestions.add(b.getName());
                    });
                    server.getAllPlayers().forEach(op -> suggestions.add(op.getUsername()));

                    String prefix = args[1].toLowerCase(Locale.ROOT);
                    return suggestions.stream()
                            .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix))
                            .sorted(String.CASE_INSENSITIVE_ORDER)
                            .toList();
                } else {
                    String prefix = args[1].toLowerCase(Locale.ROOT);
                    return reasonManager.loadAll(ReasonType.BAN).stream()
                            .map(Reason::getName)
                            .filter(r -> r.toLowerCase(Locale.ROOT).startsWith(prefix))
                            .sorted(String.CASE_INSENSITIVE_ORDER)
                            .toList();
                }
            }

            return Collections.emptyList();
        } catch (SQLException e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }
}
