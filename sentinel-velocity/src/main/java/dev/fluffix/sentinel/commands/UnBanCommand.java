package dev.fluffix.sentinel.commands;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.fluffix.sentinel.ban.Ban;
import dev.fluffix.sentinel.ban.BanManager;
import dev.fluffix.sentinel.message.MessageHandler;
import dev.fluffix.sentinel.message.MessageKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class UnBanCommand implements SimpleCommand {

    private final MessageHandler messages;
    private final BanManager banManager;

    public UnBanCommand(BanManager banManager, MessageHandler messages) {
        this.banManager = Objects.requireNonNull(banManager, "banManager");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    @Override
    public void execute(Invocation invocation) {
        var source = invocation.source();
        String[] args = invocation.arguments();

        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("[Sentinel] Der Command kann nur von einem Spieler ausgeführt werden"));
            return;
        }

        if (!player.hasPermission("sentinel.unban")) {
            messages.sendWithPrefix(player, MessageKeys.NO_PERMISSION.key());
            return;
        }

        if (args.length < 1) {
            messages.sendWithPrefix(player, MessageKeys.UNBAN_USAGE.key(),
                    Placeholder.unparsed("label", invocation.alias()));
            return;
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
                    return;
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
                    return;
                }
            }
            messages.sendWithPrefix(player, MessageKeys.UNBAN_NOT_FOUND.key(),
                    Placeholder.unparsed("target", target));

        } catch (SQLException e) {
            messages.sendWithPrefix(player, MessageKeys.BAN_SQL_ERROR.key(),
                    Placeholder.unparsed("error", e.getMessage()));
            e.printStackTrace();
        }
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
