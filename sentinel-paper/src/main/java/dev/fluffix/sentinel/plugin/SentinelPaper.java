package dev.fluffix.sentinel.plugin;

import dev.fluffix.sentinel.ban.BanManager;
import dev.fluffix.sentinel.commands.BanCommand;
import dev.fluffix.sentinel.commands.ReasonsCommand;
import dev.fluffix.sentinel.commands.UnBanCommand;
import dev.fluffix.sentinel.database.mysql.MySqlManager;
import dev.fluffix.sentinel.github.UpdateChecker;
import dev.fluffix.sentinel.listener.PlayerListener;
import dev.fluffix.sentinel.logger.PluginLogger;
import dev.fluffix.sentinel.message.MessageHandler;
import dev.fluffix.sentinel.player.SentinelPlayerManager;
import dev.fluffix.sentinel.reasons.ReasonManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

public class SentinelPaper extends JavaPlugin implements Listener {

    private static SentinelPaper instance;

    private MySqlManager mySqlManager;
    private SentinelPlayerManager sentinelPlayerManager;
    private ReasonManager reasonManager;
    private MessageHandler messageHandler;
    private BanManager banManager;

    private UpdateChecker updater;
    public volatile boolean updateAvailable = false;
    public volatile String latestVersion = null;
    public volatile String downloadUrl = null;

    @Override
    public void onEnable() {
        instance = this;

        // Plugin-Ordner sicherstellen
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            PluginLogger.printWithLabel("SENTINEL", "Fehler beim Erstellen des Plugin-Ordners", "RED");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        File configFile = new File(getDataFolder(), "mysql.json");

        // MySQL initialisieren & prüfen
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
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        } catch (Exception ex) {
            PluginLogger.printWithLabel("SENTINEL", "Verbindung zum MySQL-Server fehlgeschlagen!", "RED");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        try {
            this.sentinelPlayerManager = new SentinelPlayerManager(mySqlManager);
            this.reasonManager = new ReasonManager(mySqlManager);
            this.messageHandler = new MessageHandler(getDataFolder());
            this.banManager = new BanManager(mySqlManager, sentinelPlayerManager, reasonManager);
        } catch (SQLException | IOException e) {
            PluginLogger.printWithLabel("SENTINEL","Das Plugin koonnte nicht gestartet werden " + e.getMessage(), "RED");
            e.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        String currentVersion = getDescription().getVersion();
        this.updater = new UpdateChecker(this, currentVersion);

        Bukkit.getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskAsynchronously(this, this::refreshUpdateInfo);
        long intervalTicks = 20L * 60L * 60L * 6; // alle 6 Stunden
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::refreshUpdateInfo, intervalTicks, intervalTicks);

        PluginCommand reasonsCmd = getCommand("reasons");
        if (reasonsCmd != null) {
            reasonsCmd.setExecutor(new ReasonsCommand());
        } else {
            PluginLogger.printWithLabel("SENTINEL","Fehler beim 'REASON' Command", "RED");
        }

        PluginCommand banCmd = getCommand("ban");
        if (banCmd != null) {
            banCmd.setExecutor(new BanCommand(banManager,reasonManager, messageHandler));
            banCmd.setTabCompleter(new BanCommand(banManager,reasonManager,messageHandler));
        } else {
            PluginLogger.printWithLabel("SENTINEL","Fehler beim 'BAN' Command", "RED");
        }

        PluginCommand unBanCmd = getCommand("unban");
        if (unBanCmd != null) {
            unBanCmd.setExecutor(new UnBanCommand());
        } else {
            PluginLogger.printWithLabel("SENTINEL","Fehler beim 'UNBAN' Command", "RED");
        }

        // nach initialisierung von banManager (z.B. in onEnable)
        long initialDelay = 20L * 5L;        // 5 Sekunden nach Startup
        long period = 20L * 60L;             // jede 60 Sekunden
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                int moved = banManager.expireDueBans();
                if (moved > 0) {
                    PluginLogger.printWithLabel("SENTINEL", "Expire: " + moved + " Ban(s) verschoben/archiviert.", "GREEN");
                }
            } catch (SQLException e) {
                PluginLogger.printWithLabel("SENTINEL", "Fehler beim Archivieren abgelaufener Bans: " + e.getMessage(), "RED");
                e.printStackTrace();
            }
        }, initialDelay, period);


        PluginLogger.print("SENTINEL wurde erfolgreich geladen", "BLUE");
        PluginLogger.print("Version » " + getInstance().getDescription().getVersion(), "BLUE");
        PluginLogger.print("Author » FluffixYT", "BLUE");
        PluginLogger.print("GitHub » https://github.com/FluffixYT", "BLUE");

        new PlayerListener();
    }

    @Override
    public void onDisable() {
        if (mySqlManager != null) {
            try {
                mySqlManager.close();
                PluginLogger.printWithLabel("SENTINEL", "Verbindung zum MySQL-Server beendet", "RED");
            } catch (Exception e) {
                PluginLogger.printWithLabel("SENTINEL", "Verbindung konnte nicht geschlossen werden: " + e.getMessage(), "RED");
            } finally {
                mySqlManager = null;
            }
        }
        instance = null;
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


    public static SentinelPaper getInstance() {
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
}
