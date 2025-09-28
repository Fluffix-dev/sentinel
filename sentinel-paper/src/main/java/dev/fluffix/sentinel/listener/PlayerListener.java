package dev.fluffix.sentinel.listener;

import dev.fluffix.sentinel.ban.Ban;
import dev.fluffix.sentinel.ban.BanManager;
import dev.fluffix.sentinel.message.MessageHandler;
import dev.fluffix.sentinel.message.MessageKeys;
import dev.fluffix.sentinel.player.SentinelPlayerManager;
import dev.fluffix.sentinel.plugin.SentinelPaper;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class PlayerListener implements Listener {

    private final BanManager banManager = SentinelPaper.getInstance().getBanManager();
    private final SentinelPlayerManager playerManager = SentinelPaper.getInstance().getSentinelPlayerManager();
    private final MessageHandler messages = SentinelPaper.getInstance().getMessageHandler();

    public PlayerListener() {
       Bukkit.getPluginManager().registerEvents(this, SentinelPaper.getInstance());
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID uuid = event.getUniqueId();
        Ban ban;
        try {
            ban = banManager.getActive(uuid);
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[Sentinel] Konnte Ban-Status nicht prüfen: " + e.getMessage());
            return;
        }
        if (ban == null) return;

        long remaining = ban.getRemainingSeconds();
        Instant expiresAt = ban.getExpiresAt();
        if (expiresAt != null) {
            long secs = Duration.between(Instant.now(), expiresAt).getSeconds();
            remaining = Math.max(0, secs);
            if (remaining == 0) return;
        }

        List<String> reasonList = ban.getReasons();
        String reasonsJoined = (reasonList == null || reasonList.isEmpty()) ? "-" : String.join(", ", reasonList);

        String durationPretty = remaining == 0 ? "permanent" : formatDuration(remaining);
        String operator = ban.getOperator() == null ? "-" : ban.getOperator();
        String notice = ban.getNotice() == null ? "" : ban.getNotice();

        var kickMsg = messages.render(MessageKeys.BAN_KICK.key(),
                Placeholder.unparsed("player", event.getName()),
                Placeholder.unparsed("reasons", reasonsJoined),
                Placeholder.unparsed("duration", durationPretty),
                Placeholder.unparsed("operator", operator),
                Placeholder.unparsed("notice", notice)
        );

        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, kickMsg);
    }

    @EventHandler
    public void handleJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String ip = player.getAddress() == null ? "unknown" : player.getAddress().getAddress().getHostAddress();
        try {
            playerManager.registerOrUpdate(player.getUniqueId(), player.getName(), ip);
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[Sentinel] Konnte Spieler nicht registrieren/aktualisieren: " + e.getMessage());
        }

        if (SentinelPaper.getInstance().updateAvailable && (player.hasPermission("sentinel.setup") || player.hasPermission("*"))) {
            String current = SentinelPaper.getInstance().getDescription().getVersion();
            String latest  = (SentinelPaper.getInstance().latestVersion != null ? SentinelPaper.getInstance().latestVersion : "unbekannt");
            String url     = (SentinelPaper.getInstance().downloadUrl != null ? SentinelPaper.getInstance().downloadUrl : "—");

            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<b><dark_gray>[</dark_gray>SENTINEL UPDATE<dark_gray>]</dark_gray></b> <green>" + latest));

            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<gray>Deine Version: <green>" + current));

            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<gray>Neue Version: <dark_green><b>" + latest + "</b></dark_green>"));

            player.sendMessage(MiniMessage.miniMessage().deserialize("<gray>Download Url: ")
                    .append(Component.text(url)
                            .color(NamedTextColor.BLUE)
                            .clickEvent(ClickEvent.openUrl(url))));
        }
    }

    private static String formatDuration(long seconds) {
        long days = seconds / 86400; seconds %= 86400;
        long hours = seconds / 3600; seconds %= 3600;
        long minutes = seconds / 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0 || sb.length() == 0) sb.append(minutes).append("m");
        return sb.toString().trim();
    }
}
