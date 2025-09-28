package dev.fluffix.sentinel.message;

import dev.fluffix.sentinel.configuration.JsonFileBuilder;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MessageHandler lädt/verwaltet MiniMessage-Templates aus messages.json.
 * - Erstellt bei Bedarf Default-Datei
 * - Merged fehlende Keys in bestehende Datei
 * - Prefix-Unterstützung (Key "prefix")
 * - Platzhalter via TagResolver/Placeholder
 * - Reload zur Laufzeit
 */
public class MessageHandler {

    private final File file;
    private final MiniMessage mm;
    private JsonFileBuilder json;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public MessageHandler(File dataFolder) throws IOException {
        Objects.requireNonNull(dataFolder, "dataFolder");
        if (!dataFolder.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dataFolder.mkdirs();
        }
        this.file = new File(dataFolder, "messages.json");
        this.mm = MiniMessage.miniMessage();

        ensureDefaults(); // erstellt oder merged fehlende Keys
        load();
    }

    /* ------------------------- Public API ------------------------- */

    /** Lädt messages.json neu (z. B. für /reload). */
    public synchronized void reload() throws IOException {
        load();
        cache.clear();
    }

    /** Gibt MiniMessage-Template als String (roh) zurück. */
    public String raw(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return cache.computeIfAbsent(k, ignored -> json.getString(k));
    }

    /** Prefix als Component (falls vorhanden, sonst leeres Component). */
    public Component prefix() {
        String p = raw(MessageKeys.PREFIX.key());
        return p == null ? Component.empty() : mm.deserialize(p);
    }

    /** Prefix als roher MiniMessage-String (z. B. für Logger). */
    public String prefixRaw() {
        return raw(MessageKeys.PREFIX.key());
    }

    /** Baut eine Nachricht (ohne Prefix) als Component. */
    public Component render(String key, TagResolver... resolvers) {
        String tpl = raw(key);
        if (tpl == null || tpl.isBlank()) return Component.empty();
        return mm.deserialize(tpl, resolversOrEmpty(resolvers));
    }

    /** Baut eine Nachricht mit Prefix (prefix + msg). */
    public Component renderWithPrefix(String key, TagResolver... resolvers) {
        Component pfx = prefix();
        Component msg = render(key, resolvers);
        if (pfx == Component.empty()) return msg;
        return pfx.append(Component.text(" ")).append(msg);
    }

    /** Sendet eine Nachricht (ohne Prefix) an Audience. */
    public void send(Audience audience, String key, TagResolver... resolvers) {
        if (audience == null) return;
        audience.sendMessage(render(key, resolvers));
    }

    /** Sendet eine Nachricht (mit Prefix) an Audience. */
    public void sendWithPrefix(Audience audience, String key, TagResolver... resolvers) {
        if (audience == null) return;
        audience.sendMessage(renderWithPrefix(key, resolvers));
    }

    /** Hilfsmethode: schnell Platzhalter aus Map bauen. */
    public static TagResolver placeholders(Map<String, String> map) {
        if (map == null || map.isEmpty()) return TagResolver.empty();
        TagResolver.Builder b = TagResolver.builder();
        map.forEach((k, v) -> b.resolver(Placeholder.unparsed(k, v == null ? "" : v)));
        return b.build();
    }

    /* ------------------------- intern ----------------------------- */

    private void load() throws IOException {
        json = new JsonFileBuilder();
        json.loadFromFile(file);
    }

    /**
     * Erstellt Defaults oder merged fehlende Keys in bestehende Datei.
     */
    private void ensureDefaults() throws IOException {
        // 1) Defaults definieren
        JsonFileBuilder defaults = new JsonFileBuilder()
                .add(MessageKeys.PREFIX.key(), "<gray>[<gradient:#00E5FF:#7C4DFF>Sentinel</gradient>]</gray>")
                .add(MessageKeys.NO_PERMISSION.key(), "<red>Du hast keine Berechtigung.</red>")

                // Reasons
                .add(MessageKeys.REASONS_ADDED.key(), "<green>Reason <yellow><name></yellow> <gray>(</gray><type><gray>)</gray> gespeichert. Dauer: <gold><duration></gold></green>")
                .add(MessageKeys.REASONS_REMOVED.key(), "<green>Reason <yellow><name></yellow> <gray>(</gray><type><gray>)</gray> entfernt.</green>")
                .add(MessageKeys.REASONS_HEADER.key(), "<aqua>--- Reasons<gray> [</gray><type><gray>]</gray> ---</aqua>")
                .add(MessageKeys.REASONS_LINE.key(), "<yellow><name></yellow> <gray>[</gray><type><gray>]</gray> <white><duration></white>")

                // Reload
                .add(MessageKeys.RELOAD_DONE.key(), "<green>Konfiguration neu geladen.</green>")

                // Ban Command
                .add(MessageKeys.BAN_USAGE.key(), "<gray>Verwendung:</gray> <white>/<label> <target> <reason1,reason2,...> [Notiz]</white>")
                .add(MessageKeys.BAN_SUCCESS.key(), "<green><operator></green> hat <yellow><target></yellow> gebannt. Gründe: <gold><reasons></gold> <gray>(</gray><duration><gray>)</gray><#9aa><notice></#9aa>")
                .add(MessageKeys.BAN_ERROR.key(), "<red>Konnte Ban nicht ausführen:</red> <white><error></white>")
                .add(MessageKeys.BAN_SQL_ERROR.key(), "<red>SQL-Fehler:</red> <white><error></white>")

                // Kick beim Bann
                .add(MessageKeys.BAN_KICK.key(), "<red>Du bist vom Server gebannt.</red><newline><gray>Gründe:</gray> <gold><reasons></gold><newline><gray>Verbleibend:</gray> <white><duration></white><newline><gray>Von:</gray> <white><operator></white><newline><gray><notice></gray>")

                // Ban-Liste
                .add(MessageKeys.BAN_LIST_USAGE.key(), "<gray>Verwendung:</gray> <white>/<label> list <target|all></white>")
                .add(MessageKeys.BAN_LIST_HEADER.key(), "<aqua>— Bans für <yellow><target></yellow> —</aqua>")
                .add(MessageKeys.BAN_LIST_LINE.key(), "<yellow><player></yellow> <gray>(</gray><white><operator></white><gray>)</gray> <gray>[</gray><reasons><gray>]</gray> <white><duration></white> <gray>active=</gray><white><active></white>")
                .add(MessageKeys.BAN_LIST_EMPTY.key(), "<gray>Keine Einträge gefunden für <white><target></white>.</gray>");

        // 2) Wenn Datei noch nicht existiert -> komplett schreiben
        if (!file.exists()) {
            defaults.build(file.getAbsolutePath());
            return;
        }

        // 3) Bestehende Datei laden und fehlende Keys ergänzen
        JsonFileBuilder current = new JsonFileBuilder();
        current.loadFromFile(file);

        boolean changed = false;
        Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> it = defaults.entries();
        while (it.hasNext()) {
            Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> e = it.next();
            String k = e.getKey();
            String v = (e.getValue() == null ? "" : e.getValue().asText());
            if (!current.contains(k)) {
                current.add(k, v);
                changed = true;
            }
        }
        if (changed) {
            current.build(file.getAbsolutePath());
        }
    }

    private static TagResolver resolversOrEmpty(TagResolver... resolvers) {
        if (resolvers == null || resolvers.length == 0) return TagResolver.empty();
        return TagResolver.resolver(resolvers);
    }
}
