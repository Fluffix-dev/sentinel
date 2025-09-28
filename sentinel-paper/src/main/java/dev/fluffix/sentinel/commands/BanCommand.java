package dev.fluffix.sentinel.commands;

import dev.fluffix.sentinel.ban.Ban;
import dev.fluffix.sentinel.ban.BanManager;
import dev.fluffix.sentinel.message.MessageHandler;
import dev.fluffix.sentinel.message.MessageKeys;
import dev.fluffix.sentinel.reasons.Reason;
import dev.fluffix.sentinel.reasons.ReasonManager;
import dev.fluffix.sentinel.reasons.ReasonType;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class BanCommand implements CommandExecutor, TabCompleter {

    private final BanManager banManager;
    private final ReasonManager reasonManager;
    private final MessageHandler messages;

    public BanCommand(BanManager banManager, ReasonManager reasonManager, MessageHandler messages) {
        this.banManager = Objects.requireNonNull(banManager, "banManager");
        this.reasonManager = Objects.requireNonNull(reasonManager, "reasonManager");
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

        if (args.length == 0) {
            sendUsage(player, label);
            return true;
        }

        if (args[0].equalsIgnoreCase("list")) {
            if (args.length < 2) {
                messages.sendWithPrefix(player, MessageKeys.BAN_LIST_USAGE.key(),
                        Placeholder.unparsed("label", label));
                return true;
            }

            String target = args[1];
            if ("all".equalsIgnoreCase(target)) {
                if (!player.hasPermission("sentinel.banlist")) {
                    messages.sendWithPrefix(player, MessageKeys.NO_PERMISSION.key());
                    return true;
                }
            } else {
                if (!player.hasPermission("sentinel.banlist.players")) {
                    messages.sendWithPrefix(player, MessageKeys.NO_PERMISSION.key());
                    return true;
                }
            }

            handleList(player, target);
            return true;
        }

        if (args.length < 2) {
            sendUsage(player, label);
            return true;
        }

        final String target = args[0];

        if (target.equalsIgnoreCase(player.getName())) {
            messages.sendWithPrefix(player, MessageKeys.BAN_ERROR.key(),
                    Placeholder.unparsed("error", "Du kannst dich nicht selbst bannen."));
            return true;
        }

        Player targetPlayer = Bukkit.getPlayerExact(target);
        if (targetPlayer != null && targetPlayer.hasPermission("sentinel.bypass")) {
            messages.sendWithPrefix(player, MessageKeys.BAN_ERROR.key(),
                    Placeholder.unparsed("error", "Dieser Spieler kann nicht gebannt werden."));
            return true;
        }

        final List<String> reasonsList = Arrays.stream(args[1].split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        final String notice = (args.length > 2)
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : "";

        final String operator = player.getName();

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

            Player onlineTarget = Bukkit.getPlayer(ban.getUniqueId());
            if (onlineTarget == null) {
                onlineTarget = Bukkit.getPlayerExact(ban.getName());
            }
            if (onlineTarget != null && onlineTarget.isOnline()) {
                var kickMsg = messages.render(
                        MessageKeys.BAN_KICK.key(),
                        Placeholder.unparsed("player", onlineTarget.getName()),
                        Placeholder.unparsed("reasons", reasonsJoined),
                        Placeholder.unparsed("duration", durationPretty),
                        Placeholder.unparsed("operator", operator),
                        Placeholder.unparsed("notice", notice == null ? "" : notice)
                );
                onlineTarget.kick(kickMsg);
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
                    Placeholder.unparsed("error", e.getMessage()));
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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player) || !sender.hasPermission("sentinel.ban")) {
            return Collections.emptyList();
        }

        try {
            if (args.length == 1) {
                Set<String> suggestions = new LinkedHashSet<>();
                suggestions.add("list");

                banManager.listAll(false).forEach(b -> {
                    if (b.getName() != null) suggestions.add(b.getName());
                });
                Bukkit.getOnlinePlayers().forEach(p -> suggestions.add(p.getName()));

                return suggestions.stream()
                        .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
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
                    Bukkit.getOnlinePlayers().forEach(p -> suggestions.add(p.getName()));

                    return suggestions.stream()
                            .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                            .sorted(String.CASE_INSENSITIVE_ORDER)
                            .toList();
                } else {
                    return reasonManager.loadAll(ReasonType.BAN).stream()
                            .map(Reason::getName)
                            .filter(r -> r.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
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
