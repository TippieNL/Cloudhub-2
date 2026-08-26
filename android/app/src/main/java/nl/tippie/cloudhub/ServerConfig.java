package nl.tippie.cloudhub;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Where the user's CloudHub lives, and which certificate they have accepted
 * for it.
 *
 * The address is not baked into the APK: one build has to work for any
 * deployment, and a self-hosted server moves. It is asked for once and kept
 * here.
 */
public final class ServerConfig {

    private static final String PREFS = "cloudhub";
    private static final String KEY_URL = "server_url";
    private static final String KEY_PINNED_CERT = "pinned_cert_";

    private final SharedPreferences prefs;

    public ServerConfig(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String url() {
        return prefs.getString(KEY_URL, null);
    }

    public boolean isConfigured() {
        return url() != null;
    }

    public void setUrl(String url) {
        prefs.edit().putString(KEY_URL, url).apply();
    }

    public void clear() {
        prefs.edit().clear().apply();
    }

    /**
     * Remember that the user accepted one specific certificate for one host.
     *
     * Pinned by fingerprint rather than by host alone: "this user said yes to a
     * certificate warning once" must not become "trust anything this host ever
     * presents", which is what a blanket proceed() in onReceivedSslError does.
     */
    public void pinCertificate(String host, String sha256) {
        prefs.edit().putString(KEY_PINNED_CERT + host, sha256).apply();
    }

    public boolean isPinned(String host, String sha256) {
        String stored = prefs.getString(KEY_PINNED_CERT + host, null);
        return stored != null && stored.equals(sha256);
    }

    /**
     * Trim a typed address into an origin.
     *
     * People type "files.example.com", "https://files.example.com/" and
     * "https://files.example.com/index.php" and mean the same thing. Returns
     * null when nothing usable is left.
     */
    public static String normalise(String input) {
        if (input == null) return null;
        String url = input.trim();
        if (url.isEmpty()) return null;
        if (!url.matches("(?i)^https?://.*")) url = "https://" + url;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.toLowerCase().endsWith("/index.php")) url = url.substring(0, url.length() - 10);
        return url.isEmpty() ? null : url;
    }
}
