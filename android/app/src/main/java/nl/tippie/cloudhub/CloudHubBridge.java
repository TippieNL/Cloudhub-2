package nl.tippie.cloudhub;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The native half of the shims the web app needs inside a WebView.
 *
 * A WebView is not Chrome. Three things CloudHub already relies on are simply
 * absent or inert here, and rather than change the web application -- which
 * must keep working in an ordinary browser -- the shell supplies them:
 *
 *   saveBlob      a download. The page builds a blob: URL and clicks a
 *                 synthetic anchor; WebView's DownloadListener never fires for
 *                 blob:, so without this every download and the ZIP export
 *                 silently does nothing at all.
 *   copyText      navigator.clipboard, which does not exist outside a secure
 *                 context, and which the share dialog uses for its one useful
 *                 action.
 *   takeShared*   files handed in by the Android share sheet, passed to the
 *                 page's existing durable upload queue instead of a second
 *                 uploader written here.
 *
 * Every method is reachable from page JavaScript, so each one validates its
 * input and none of them accept a filesystem path.
 */
public class CloudHubBridge {

    /** Base64 inflates by 4/3 and this crosses the JNI boundary as one string. */
    private static final int MAX_BLOB_BYTES = 256 * 1024 * 1024;

    private final Context context;
    private final Map<String, List<byte[]>> pendingBlobs = new HashMap<>();

    /** Files handed over by the share sheet, waiting for the page to collect them. */
    private final List<SharedFile> shared = new ArrayList<>();

    public static final class SharedFile {
        public final String name;
        public final String mime;
        public final byte[] bytes;
        SharedFile(String name, String mime, byte[] bytes) {
            this.name = name; this.mime = mime; this.bytes = bytes;
        }
    }

    public CloudHubBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    /* ---- downloads ------------------------------------------------------ */

    /**
     * Collect one chunk of a download.
     *
     * The blob arrives in pieces because a large video as a single base64
     * string is a multi-hundred-megabyte allocation on both sides of the
     * bridge at once.
     */
    @JavascriptInterface
    public boolean appendBlobChunk(String token, String base64) {
        if (token == null || base64 == null) return false;
        synchronized (pendingBlobs) {
            List<byte[]> parts = pendingBlobs.get(token);
            if (parts == null) {
                parts = new ArrayList<>();
                pendingBlobs.put(token, parts);
            }
            byte[] decoded;
            try {
                decoded = Base64.decode(base64, Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                pendingBlobs.remove(token);
                return false;
            }
            int total = decoded.length;
            for (byte[] part : parts) total += part.length;
            if (total > MAX_BLOB_BYTES) {
                pendingBlobs.remove(token);
                return false;
            }
            parts.add(decoded);
            return true;
        }
    }

    /** Write the collected chunks into the device's Downloads folder. */
    @JavascriptInterface
    public String saveBlob(String token, String fileName, String mimeType) {
        List<byte[]> parts;
        synchronized (pendingBlobs) {
            parts = pendingBlobs.remove(token);
        }
        if (parts == null) return "Nothing was received to save";

        String safe = safeFileName(fileName);
        String mime = (mimeType == null || mimeType.isEmpty()) ? "application/octet-stream" : mimeType;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = context.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                values.put(MediaStore.Downloads.MIME_TYPE, mime);
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (item == null) return "The Downloads folder refused the file";
                try (OutputStream out = resolver.openOutputStream(item)) {
                    if (out == null) return "The Downloads folder could not be opened";
                    for (byte[] part : parts) out.write(part);
                }
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(item, values, null, null);
            } else {
                // Before scoped storage, MediaStore.Downloads does not exist.
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists() && !dir.mkdirs()) return "The Downloads folder is unavailable";
                File out = uniqueFile(dir, safe);
                try (FileOutputStream stream = new FileOutputStream(out)) {
                    for (byte[] part : parts) stream.write(part);
                }
            }
            toast(context.getString(R.string.saved_to_downloads, safe));
            return "";
        } catch (Exception e) {
            String message = e.getMessage();
            return message == null ? e.getClass().getSimpleName() : message;
        }
    }

    /* ---- clipboard ------------------------------------------------------ */

    @JavascriptInterface
    public boolean copyText(String text) {
        if (text == null) return false;
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return false;
        clipboard.setPrimaryClip(ClipData.newPlainText("CloudHub", text));
        return true;
    }

    /* ---- the Android share sheet ---------------------------------------- */

    void offerShared(List<SharedFile> files) {
        synchronized (shared) {
            shared.addAll(files);
        }
    }

    boolean hasShared() {
        synchronized (shared) {
            return !shared.isEmpty();
        }
    }

    /** How many files the share sheet handed over, without consuming them. */
    @JavascriptInterface
    public int sharedCount() {
        synchronized (shared) {
            return shared.size();
        }
    }

    @JavascriptInterface
    public String sharedName(int index) {
        synchronized (shared) {
            return index >= 0 && index < shared.size() ? shared.get(index).name : "";
        }
    }

    @JavascriptInterface
    public String sharedMime(int index) {
        synchronized (shared) {
            return index >= 0 && index < shared.size() ? shared.get(index).mime : "";
        }
    }

    @JavascriptInterface
    public int sharedSize(int index) {
        synchronized (shared) {
            return index >= 0 && index < shared.size() ? shared.get(index).bytes.length : 0;
        }
    }

    /** One slice of a shared file, base64 encoded, for the page to rebuild. */
    @JavascriptInterface
    public String sharedChunk(int index, int offset, int length) {
        synchronized (shared) {
            if (index < 0 || index >= shared.size()) return "";
            byte[] bytes = shared.get(index).bytes;
            if (offset < 0 || offset >= bytes.length || length <= 0) return "";
            int end = Math.min(bytes.length, offset + length);
            byte[] slice = new byte[end - offset];
            System.arraycopy(bytes, offset, slice, 0, slice.length);
            return Base64.encodeToString(slice, Base64.NO_WRAP);
        }
    }

    /** Called once the page has the bytes, so they are not queued twice. */
    @JavascriptInterface
    public void clearShared() {
        synchronized (shared) {
            shared.clear();
        }
    }

    /* ---- helpers -------------------------------------------------------- */

    static byte[] readAll(InputStream in, int limit) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int read;
        int total = 0;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IllegalStateException("File is too large to share");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * Strip anything that could escape the Downloads folder.
     *
     * The name comes from page JavaScript, so it is untrusted: a name of
     * "../../evil" must not become a path.
     */
    static String safeFileName(String name) {
        if (name == null || name.trim().isEmpty()) return "download";
        String cleaned = name.replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) cleaned = cleaned.substring(slash + 1);
        cleaned = cleaned.replaceAll("[\\x00-\\x1F\\x7F]", "").trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) return "download";
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }

    private static File uniqueFile(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) return candidate;
        String stem = name;
        String extension = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            stem = name.substring(0, dot);
            extension = name.substring(dot);
        }
        for (int i = 2; i < 1000; i++) {
            candidate = new File(dir, stem + " (" + i + ")" + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(dir, System.currentTimeMillis() + extension);
    }

    private void toast(String message) {
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
}
