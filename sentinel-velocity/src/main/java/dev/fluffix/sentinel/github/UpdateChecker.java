package dev.fluffix.sentinel.github;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Plattformunabhängiger UpdateChecker für GitHub-Releases.
 * Keine Bukkit-/Velocity-Abhängigkeiten.
 */
public class UpdateChecker {

    private final HttpClient client;

    // Repo-Daten (ggf. public machen oder via Konstruktor übergeben)
    private final String owner = "Fluffix-dev";
    private final String repo  = "sentinel";
    private final boolean includePrereleases = false;

    private final String currentVersion;
    private final String userAgent;

    /**
     * Nutze einen sinnvollen User-Agent (z. B. "Sentinel-UpdateChecker/1.0").
     */
    public UpdateChecker(String currentVersion, String userAgent) {
        this.currentVersion = normalize(currentVersion);
        this.userAgent = (userAgent == null || userAgent.isBlank())
                ? "Sentinel-UpdateChecker"
                : userAgent;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Komfort-Konstruktor: setzt einen Default-User-Agent.
     */
    public UpdateChecker(String currentVersion) {
        this(currentVersion, "Sentinel-UpdateChecker");
    }

    public record UpdateResult(boolean newer, String latestTag, String htmlUrl, String releaseName, String error) {
        public boolean isNewerAvailable() { return newer && error == null; }
        public String error() { return error; }
        public String latestTag() { return latestTag; }
        public String htmlUrl() { return htmlUrl; }
        public String releaseName() { return releaseName; }
    }

    public UpdateResult checkNow() {
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/releases/latest";

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", userAgent)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        try {
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return new UpdateResult(false, "", "", "", "HTTP " + resp.statusCode());
            }
            String body = resp.body();

            String tag   = jsonString(body, "tag_name");
            String html  = jsonString(body, "html_url");
            String name  = jsonString(body, "name");
            boolean pre  = jsonBoolean(body, "prerelease");

            if (tag == null || tag.isBlank()) {
                return new UpdateResult(false, "", "", "", "Kein tag_name gefunden");
            }

            if (!includePrereleases && pre) {
                // neueste Release ist ein Pre-Release, aber wir ignorieren es -> kein Update signalisieren
                return new UpdateResult(false, tag, html, name, null);
            }

            String latest = normalize(tag);
            int cmp = compare(latest, currentVersion);
            return (cmp > 0)
                    ? new UpdateResult(true, tag, html, name, null)
                    : new UpdateResult(false, tag, html, name, null);

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt(); // sicherheitshalber falls interrupted
            return new UpdateResult(false, "", "", "", e.getMessage());
        }
    }

    // --- Minimaler JSON-Parser (wie im Original) ---

    private static String jsonString(String json, String key) {
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return null;
        int colon = json.indexOf(':', k);
        if (colon < 0) return null;

        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;

        if (i >= json.length() || json.charAt(i) != '"') return null;
        i++;

        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        while (i < json.length()) {
            char c = json.charAt(i++);
            if (esc) {
                switch (c) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    default -> sb.append(c);
                }
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean jsonBoolean(String json, String key) {
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return false;
        int colon = json.indexOf(':', k);
        if (colon < 0) return false;

        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;

        int start = i;
        while (i < json.length() && "truefalsenull,}] \n\r\t".indexOf(json.charAt(i)) == -1) i++;
        String token = json.substring(start, i).trim();
        return "true".equalsIgnoreCase(token);
    }

    private static String normalize(String v) {
        if (v == null) return "0";
        v = v.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        v = v.replaceAll("[^0-9.]", ".");
        v = v.replaceAll("\\.+", ".");
        if (v.startsWith(".")) v = v.substring(1);
        if (v.endsWith(".")) v = v.substring(0, v.length() - 1);
        if (v.isEmpty()) v = "0";
        return v;
    }

    private static int compare(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int max = Math.max(pa.length, pb.length);
        for (int i = 0; i < max; i++) {
            int ai = i < pa.length ? parse(pa[i]) : 0;
            int bi = i < pb.length ? parse(pb[i]) : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }
}
