/*
 * Copyright (c) 2025 FluffixYT
 *
 * Alle Rechte vorbehalten.
 *
 * Diese Datei ist Teil des Projekts sentinel.
 *
 * Die Nutzung, Vervielfältigung oder Verbreitung ohne vorherige schriftliche
 * Genehmigung des Rechteinhabers ist nicht gestattet.
 * 09/2025
 */

package dev.fluffix.sentinel.plugin;

import dev.fluffix.sentinel.commands.ReasonsCommand;
import dev.fluffix.sentinel.database.mysql.MySqlManager;
import dev.fluffix.sentinel.logger.PluginLogger;
import dev.fluffix.sentinel.message.MessageHandler;
import dev.fluffix.sentinel.player.SentinelPlayerManager;
import dev.fluffix.sentinel.reasons.ReasonManager;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

public class SentinelPaper extends JavaPlugin {

    private static SentinelPaper instance;

    private MySqlManager mySqlManager;
    private SentinelPlayerManager sentinelPlayerManager;
    private ReasonManager reasonManager;
    private MessageHandler messageHandler;

    @Override
    public void onEnable() {
        instance = this;

        // Stelle sicher, dass der Plugin-Ordner existiert
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            PluginLogger.printWithLabel("SENTINEL","Fehler beim erstellen der Konfiguration","RED");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        File configFile = new File(getDataFolder(), "mysql.json");

        try {
            mySqlManager = MySqlManager.fromConfig(configFile);

            try (Connection con = mySqlManager.getConnection()) {
                if (!con.isValid(2)) {
                    throw new IllegalStateException("MySQL-Connection ist nicht gültig (isValid=false).");
                }
            }

            PluginLogger.printWithLabel("SENTINEL","Die verbindung zum MySQL Server war erfolgreich","GREEN");

        } catch (IOException io) {
            PluginLogger.printWithLabel("SENTINEL","Fehler beim erstellen der Konfiguration" + io.getMessage(),"RED");
            Bukkit.getPluginManager().disablePlugin(this);
        } catch (Exception ex) {
            PluginLogger.printWithLabel("SENTINEL","Die verbindung zum MySQL Server ist fehlgeschlagen!","RED");
            Bukkit.getPluginManager().disablePlugin(this);
        }

        try {
            this.sentinelPlayerManager = new SentinelPlayerManager(mySqlManager);
            this.reasonManager = new ReasonManager(mySqlManager);
            this.messageHandler = new MessageHandler(getDataFolder());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        getCommand("reasons").setExecutor(new ReasonsCommand());
    }

    @Override
    public void onDisable() {
        // Pool sauber schließen (idempotent)
        if (mySqlManager != null) {
            try {
                mySqlManager.close();
                PluginLogger.printWithLabel("SENTINEL","Die verbindung zum MySQL Server ist beendet!","RED");
            } catch (Exception e) {
                PluginLogger.printWithLabel("SENTINEL","Die verbindung konnte nicht geschlossen werden" + e.getMessage(),"RED");
            } finally {
                mySqlManager = null;
            }
        }
        instance = null;
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
}
