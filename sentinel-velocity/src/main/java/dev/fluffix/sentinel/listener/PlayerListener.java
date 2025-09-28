package dev.fluffix.sentinel.listener;

import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import dev.fluffix.sentinel.ban.Ban;
import dev.fluffix.sentinel.ban.BanManager;
import dev.fluffix.sentinel.message.MessageHandler;
import dev.fluffix.sentinel.message.MessageKeys;
import dev.fluffix.sentinel.player.SentinelPlayerManager;
import dev.fluffix.sentinel.plugin.SentinelVelocity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PlayerListener {

    private final BanManager banManager;
    private final SentinelPlayerManager playerManager;
    private final MessageHandler messages;
    private final SentinelVelocity plugin; // für Update-Infos

    private final MiniMessage mm = MiniMessage.miniMessage();

    public PlayerListener(SentinelVelocity plugin,
                                  BanManager banManager,
                                  SentinelPlayerManager playerManager,
                                  MessageHandler messages) {
        this.plugin = plugin;
        this.banManager = banManager;
        this.playerManager = playerManager;
        this.messages = messages;
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        Ban ban;
        try {
            ban = banManager.getActive(uuid);
        } catch (SQLException e) {
            plugin.getSlf4jLogger().warn("[Sentinel] Konnte Ban-Status nicht prüfen: {}", e.getMessage());
            return;
        }
        if (ban == null) return;

        long remaining = ban.getRemainingSeconds();
        Instant expiresAt = ban.getExpiresAt();
        if (expiresAt != null) {
            long secs = Duration.between(Instant.now(), expiresAt).getSeconds();
            remaining = Math.max(0, secs);
            if (remaining == 0) return; // abgelaufen
        }

        List<String> reasonList = ban.getReasons();
        String reasonsJoined = (reasonList == null || reasonList.isEmpty()) ? "-" : String.join(", ", reasonList);

        String durationPretty = remaining == 0 ? "permanent" : formatDuration(remaining);
        String operator = ban.getOperator() == null ? "-" : ban.getOperator();
        String notice = ban.getNotice() == null ? "" : ban.getNotice();

        Component kickMsg = messages.render(
                MessageKeys.BAN_KICK.key(),
                Placeholder.unparsed("player", player.getUsername()),
                Placeholder.unparsed("reasons", reasonsJoined),
                Placeholder.unparsed("duration", durationPretty),
                Placeholder.unparsed("operator", operator),
                Placeholder.unparsed("notice", notice)
        );

        event.setResult(ResultedEvent.ComponentResult.denied(kickMsg));
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();

        // IP erfassen
        String ip = "unknown";
        if (player.getRemoteAddress() instanceof InetSocketAddress isa && isa.getAddress() != null) {
            ip = isa.getAddress().getHostAddress();
        }

        try {
            playerManager.registerOrUpdate(player.getUniqueId(), player.getUsername(), ip);
        } catch (SQLException e) {
            plugin.getSlf4jLogger().warn("[Sentinel] Konnte Spieler nicht registrieren/aktualisieren: {}", e.getMessage());
        }

        // Update-Hinweis (nur für berechtigte Spieler)
        if (plugin.isUpdateAvailable() && (player.hasPermission("sentinel.setup") || player.hasPermission("*"))) {
            String current = plugin.getClass().getPackage().getImplementationVersion();
            if (current == null) current = "unbekannt";
            String latest  = plugin.getLatestVersion() != null ? plugin.getLatestVersion() : "unbekannt";
            String url     = plugin.getDownloadUrl() != null ? plugin.getDownloadUrl() : "—";

            player.sendMessage(mm.deserialize("<b><dark_gray>[</dark_gray>SENTINEL UPDATE<dark_gray>]</dark_gray></b> <green>" + latest));
            player.sendMessage(mm.deserialize("<gray>Deine Version: <green>" + current));
            player.sendMessage(mm.deserialize("<gray>Neue Version: <dark_green><b>" + latest + "</b></dark_green>"));
            player.sendMessage(
                    mm.deserialize("<gray>Download Url: ")
                            .append(Component.text(url).color(NamedTextColor.BLUE).clickEvent(ClickEvent.openUrl(url)))
            );
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
