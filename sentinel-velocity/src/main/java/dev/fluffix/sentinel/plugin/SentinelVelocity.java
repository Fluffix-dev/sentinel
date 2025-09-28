package dev.fluffix.sentinel.plugin;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import dev.fluffix.sentinel.ban.BanManager;
import dev.fluffix.sentinel.commands.BanCommand;
import dev.fluffix.sentinel.commands.ReasonsCommand;
import dev.fluffix.sentinel.commands.UnBanCommand;
import dev.fluffix.sentinel.database.mysql.MySqlManager;
import dev.fluffix.sentinel.github.UpdateChecker;
import dev.fluffix.sentinel.logger.PluginLogger;
import dev.fluffix.sentinel.message.MessageHandler;
import dev.fluffix.sentinel.player.SentinelPlayerManager;
import dev.fluffix.sentinel.reasons.ReasonManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "sentinel",
        name = "Sentinel",
        version = BuildConstants.VERSION, // lege dir eine BuildConstants-Klasse oder trage hier eine fixe Versions-String ein
        authors = {"FluffixYT"}
)
public final class SentinelVelocity {

    private static SentinelVelocity instance;

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final CommandManager commandManager;

    private MySqlManager mySqlManager;
    private SentinelPlayerManager sentinelPlayerManager;
    private ReasonManager reasonManager;
    private MessageHandler messageHandler;
    private BanManager banManager;

    private UpdateChecker updater;
    private volatile boolean updateAvailable = false;
    private volatile String latestVersion = null;
    private volatile String downloadUrl = null;

    private ScheduledTask updaterTask;
    private ScheduledTask expireTask;

    @Inject
    public SentinelVelocity(ProxyServer server,
                            Logger logger,
                            @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.commandManager = server.getCommandManager();
        instance = this;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        // Datenordner sicherstellen
        File folder = dataDirectory.toFile();
        if (!folder.exists() && !folder.mkdirs()) {
            PluginLogger.printWithLabel("SENTINEL", "Fehler beim Erstellen des Plugin-Ordners", "RED");
            logger.error("Konnte Plugin-Ordner nicht erstellen: {}", folder.getAbsolutePath());
            return;
        }

        // MySQL laden
        File configFile = dataDirectory.resolve("mysql.json").toFile();
        try {
            mySqlManager = MySqlManager.fromConfig(configFile);
            try (Connection con = mySqlManager.getConnection()) {
                if (!con.isValid(2)) {
                    throw new IllegalStateException("MySQL-Connection ist nicht gültig (isValid=false).");
                }
            }
            PluginLogger.printWithLabel("SENTINEL", "Verbindung zum MySQL-Server erfolgreich", "GREEN");
        } catch (IOException io) {
            PluginLogger.printWithLabel("SENTINEL", "Fehler beim Erstellen der Konfiguration: " + io.getMessage(), "RED");
            logger.error("Konfiguration/Datei-Fehler", io);
            return;
        } catch (Exception ex) {
            PluginLogger.printWithLabel("SENTINEL", "Verbindung zum MySQL-Server fehlgeschlagen!", "RED");
            logger.error("MySQL-Verbindung fehlgeschlagen", ex);
            return;
        }

        // Manager initialisieren
        try {
            this.sentinelPlayerManager = new SentinelPlayerManager(mySqlManager);
            this.reasonManager = new ReasonManager(mySqlManager);
            this.messageHandler = new MessageHandler(folder);
            this.banManager = new BanManager(mySqlManager, sentinelPlayerManager, reasonManager);
        } catch (SQLException | IOException e) {
            PluginLogger.printWithLabel("SENTINEL", "Das Plugin konnte nicht gestartet werden " + e.getMessage(), "RED");
            logger.error("Initialisierung fehlgeschlagen", e);
            return;
        }

        // Updater vorbereiten
        String currentVersion = BuildConstants.VERSION; // oder lies aus deiner eigenen Quelle
        this.updater = new UpdateChecker(currentVersion);

        // Events registrieren (ersetze PlayerListener durch deine Velocity-Listener)
        // server.getEventManager().register(this, new PlayerListenerVelocity(...));
        // Falls du keinen Listener brauchst, entferne diese Zeile.

        // Befehle registrieren (siehe Hinweise oben)
        registerCommands();

        // Updater-Timer (alle 6 Stunden)
        this.updaterTask = server.getScheduler()
                .buildTask(this, this::refreshUpdateInfo)
                .delay(0L, TimeUnit.SECONDS)
                .repeat(6L, TimeUnit.HOURS)
                .schedule();

        // Expire-Task (alle 60 Sekunden, Start nach 5 Sekunden)
        this.expireTask = server.getScheduler()
                .buildTask(this, () -> {
                    try {
                        int moved = banManager.expireDueBans();
                        if (moved > 0) {
                            PluginLogger.printWithLabel("SENTINEL",
                                    "Expire: " + moved + " Ban(s) verschoben/archiviert.", "GREEN");
                        }
                    } catch (SQLException e) {
                        PluginLogger.printWithLabel("SENTINEL",
                                "Fehler beim Archivieren abgelaufener Bans: " + e.getMessage(), "RED");
                        logger.warn("Expire-Durchlauf fehlgeschlagen", e);
                    }
                })
                .delay(Duration.ofSeconds(5))
                .repeat(Duration.ofSeconds(60))
                .schedule();

        // Konsolenbanner
        PluginLogger.print("SENTINEL wurde erfolgreich geladen", "BLUE");
        PluginLogger.print("Version » " + BuildConstants.VERSION, "BLUE");
        PluginLogger.print("Author » FluffixYT", "BLUE");
        PluginLogger.print("GitHub » https://github.com/FluffixYT", "BLUE");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        // Tasks stoppen
        if (updaterTask != null) {
            updaterTask.cancel();
            updaterTask = null;
        }
        if (expireTask != null) {
            expireTask.cancel();
            expireTask = null;
        }

        if (mySqlManager != null) {
            try {
                mySqlManager.close();
                PluginLogger.printWithLabel("SENTINEL", "Verbindung zum MySQL-Server beendet", "RED");
            } catch (Exception e) {
                PluginLogger.printWithLabel("SENTINEL", "Verbindung konnte nicht geschlossen werden: " + e.getMessage(), "RED");
                logger.warn("Fehler beim Schließen der MySQL-Verbindung", e);
            } finally {
                mySqlManager = null;
            }
        }
        instance = null;
    }

    private void registerCommands() {
        // === Beispiel: SimpleCommand-Adapter ===
        // Ersetze die Bodies der Commands durch deine echte Logik oder
        // verwende deine bestehenden Klassen, sofern sie SimpleCommand implementieren.
        commandManager.register(commandManager.metaBuilder("reasons").build(), new SimpleCommand() {
            @Override public void execute(Invocation invocation) {
                invocation.source().sendMessage(Component.text("Reasons-Command ist noch nicht verdrahtet.")
                        .color(NamedTextColor.YELLOW));
                new ReasonsCommand(reasonManager,messageHandler);
            }
        });

        commandManager.register(commandManager.metaBuilder("ban").build(), new SimpleCommand() {
            @Override public void execute(Invocation invocation) {
                invocation.source().sendMessage(Component.text("Ban-Command ist noch nicht verdrahtet.")
                        .color(NamedTextColor.YELLOW));
                new BanCommand(server,banManager,reasonManager,messageHandler);
            }

            @Override public java.util.List<String> suggest(Invocation invocation) {
                // Optional: Tab-Vervollständigung
                return java.util.Collections.emptyList();
            }
        });

        commandManager.register(commandManager.metaBuilder("unban").build(), new SimpleCommand() {
            @Override public void execute(Invocation invocation) {
                invocation.source().sendMessage(Component.text("Unban-Command ist noch nicht verdrahtet.")
                        .color(NamedTextColor.YELLOW));
                new UnBanCommand(banManager,messageHandler);
            }
        });
    }

    private void refreshUpdateInfo() {
        UpdateChecker.UpdateResult res = updater.checkNow();
        if (res.error() != null) {
            PluginLogger.print("[Updater] Fehler: " + res.error(), "RED");
            return;
        }
        if (res.isNewerAvailable()) {
            this.updateAvailable = true;
            this.latestVersion = res.latestTag();
            this.downloadUrl = res.htmlUrl();
            PluginLogger.print("[Updater] Neue Version gefunden: " + latestVersion + " → " + downloadUrl, "GREEN");
        } else {
            this.updateAvailable = false;
            this.latestVersion = null;
            this.downloadUrl = null;
        }
    }

    public static SentinelVelocity getInstance() {
        return instance;
    }

    public MySqlManager getMySqlManager() {
        return mySqlManager;
    }

    public ReasonManager getReasonManager() {
        return reasonManager;
    }

    public MessageHandler getMessageHandler() {
        return messageHandler;
    }

    public SentinelPlayerManager getSentinelPlayerManager() {
        return sentinelPlayerManager;
    }

    public BanManager getBanManager() {
        return banManager;
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public ProxyServer getServer() {
        return server;
    }

    public Logger getSlf4jLogger() {
        return logger;
    }
}

/**
 * Kleines Hilfs-Konstrukt, damit @Plugin(version = ...) nicht konstanten Text braucht.
 * Erstelle diese Klasse in demselben Package (oder ersetze die Verwendung oben).
 */
final class BuildConstants {
    static final String VERSION = "1.0.0";
    private BuildConstants() {}
}
