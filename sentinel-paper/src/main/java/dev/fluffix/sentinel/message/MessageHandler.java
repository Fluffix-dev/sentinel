package dev.fluffix.sentinel.message;

import dev.fluffix.sentinel.configuration.JsonFileBuilder;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MessageHandler lädt/verwaltet MiniMessage-Templates aus messages.json.
 * - Erstellt bei Bedarf Default-Datei
 * - Prefix-Unterstützung (Key "prefix")
 * - Platzhalter via TagResolver/Placeholder
 * - Reload zur Laufzeit
 *
 * Nutzung:
 *   MessageHandler messages = new MessageHandler(getDataFolder());
 *   messages.sendWithPrefix(player, "reasons_added",
 *       Placeholder.unparsed("name","Spam"),
 *       Placeholder.unparsed("type","MUTE"),
 *       Placeholder.unparsed("duration","3600s"));
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

        ensureDefaults();
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
        // Java 21 kompatibel: kein "_" als Parametername
        return cache.computeIfAbsent(k, ignored -> json.getString(k));
    }

    /** Prefix als Component (falls vorhanden, sonst leeres Component). */
    public Component prefix() {
        String p = raw("prefix");
        return p == null ? Component.empty() : mm.deserialize(p);
    }

    /** Prefix als roher MiniMessage-String (z. B. für Logger). */
    public String prefixRaw() {
        return raw("prefix");
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

    private void ensureDefaults() throws IOException {
        if (file.exists()) return;

        new JsonFileBuilder()
                .add("prefix", "<gray>[<gradient:#00E5FF:#7C4DFF>Sentinel</gradient>]</gray>")
                .add("no_permission", "<red>Du hast keine Berechtigung.</red>")

                // Reasons
                .add("reasons_added", "<green>Reason <yellow><name></yellow> <gray>(</gray><type><gray>)</gray> gespeichert. Dauer: <gold><duration></gold></green>")
                .add("reasons_removed", "<green>Reason <yellow><name></yellow> <gray>(</gray><type><gray>)</gray> entfernt.</green>")
                .add("reasons_header", "<aqua>--- Reasons<gray> [</gray><type><gray>]</gray> ---</aqua>")
                .add("reasons_line", "<yellow><name></yellow> <gray>[</gray><type><gray>]</gray> <white><duration></white>")

                // Reload
                .add("reload_done", "<green>Konfiguration neu geladen.</green>")

                // BanCommand
                .add("ban_usage", "<gray>Verwendung:</gray> <white>/<label> <target> <reason1,reason2,...> [Notiz]</white>")
                .add("ban_success", "<green><operator></green> hat <yellow><target></yellow> gebannt. Gründe: <gold><reasons></gold> <gray>(</gray><duration><gray>)</gray><#9aa><notice></#9aa>")
                .add("ban_error", "<red>Konnte Ban nicht ausführen:</red> <white><error></white>")
                .add("ban_sql_error", "<red>SQL-Fehler:</red> <white><error></white>")

                // Kick-Nachricht beim Login-Bann
                .add("ban_kick", "<red>Du bist vom Server gebannt.</red><newline><gray>Gründe:</gray> <gold><reasons></gold><newline><gray>Verbleibend:</gray> <white><duration></white><newline><gray>Von:</gray> <white><operator></white><newline><gray><notice></gray>")

                .build(file.getAbsolutePath());
    }

    private static TagResolver resolversOrEmpty(TagResolver... resolvers) {
        if (resolvers == null || resolvers.length == 0) return TagResolver.empty();
        return TagResolver.resolver(resolvers);
    }
}
