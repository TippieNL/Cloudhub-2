package nl.tippie.cloudhub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * The application: a WebView pointed at the user's own CloudHub.
 *
 * A Trusted Web Activity would have been the natural home for the progressive
 * web app, but it fixes one origin at build time and requires Digital Asset
 * Links verification against a publicly trusted certificate -- which a private
 * or VPN-only domain cannot satisfy. So this is a WebView, and the shell has
 * to supply the browser behaviour the page assumes: see CloudHubBridge and
 * assets/bridge.js.
 */
public class MainActivity extends Activity {

    private static final String TAG = "CloudHub";
    private static final int MAX_SHARE_BYTES = 256 * 1024 * 1024;

    private WebView web;
    private ServerConfig config;
    private CloudHubBridge bridge;

    private ValueCallback<Uri[]> pendingFileChooser;
    private Uri pendingCameraOutput;

    private static final int REQ_SETUP = 1;
    private static final int REQ_FILE_CHOOSER = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        config = new ServerConfig(this);
        bridge = new CloudHubBridge(this);
        web = findViewById(R.id.web);

        configureWebView();
        takeSharedFiles(getIntent());

        if (!config.isConfigured()) {
            startActivityForResult(new Intent(this, SetupActivity.class), REQ_SETUP);
        } else {
            web.loadUrl(config.url() + "/");
        }
    }

    /* ---- the WebView ---------------------------------------------------- */

    private void configureWebView() {
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        // The page opens a public share link with target="_blank"; without this
        // onCreateWindow is never called and the link does nothing.
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);

        web.addJavascriptInterface(bridge, "CloudHubNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String server = config.url();
                // Anything outside the configured server is somebody else's
                // site and belongs in the browser, not inside this app.
                if (server != null && uri.toString().startsWith(server)) return false;
                openExternally(uri);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectBridge();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                Toast.makeText(MainActivity.this,
                        getString(R.string.load_failed, error.getDescription()), Toast.LENGTH_LONG).show();
            }

            /**
             * A certificate this device does not trust.
             *
             * Never proceed() unconditionally: that would turn every install
             * into a silent man-in-the-middle. The user is shown the host and
             * the certificate's fingerprint and, if they accept it, that exact
             * certificate is pinned -- so a later, different certificate for
             * the same host asks again instead of being waved through.
             */
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                String host = Uri.parse(error.getUrl()).getHost();
                String fingerprint = fingerprintOf(error.getCertificate());

                if (host != null && fingerprint != null && config.isPinned(host, fingerprint)) {
                    handler.proceed();
                    return;
                }

                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.cert_title)
                        .setMessage(getString(R.string.cert_message,
                                host == null ? "?" : host,
                                describe(error),
                                fingerprint == null ? "unavailable" : fingerprint))
                        .setPositiveButton(R.string.cert_trust, (d, w) -> {
                            if (host != null && fingerprint != null) config.pinCertificate(host, fingerprint);
                            handler.proceed();
                        })
                        .setNegativeButton(R.string.cert_cancel, (d, w) -> handler.cancel())
                        .setCancelable(false)
                        .show();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                return showFileChooser(callback, params);
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                // The href of a target="_blank" anchor is not on the message,
                // so a throwaway WebView is used to learn where it wanted to go.
                WebView probe = new WebView(MainActivity.this);
                probe.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        openExternally(request.getUrl());
                        v.destroy();
                        return true;
                    }
                });
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(probe);
                resultMsg.sendToTarget();
                return true;
            }
        });

        // Ordinary http(s) downloads. The page's own downloads are blob: URLs
        // and never reach here -- bridge.js handles those.
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                openExternally(Uri.parse(url));
            }
        });
    }

    private void injectBridge() {
        String script = readAsset("bridge.js");
        if (script != null) web.evaluateJavascript(script, null);
    }

    /* ---- the file chooser: camera and gallery ---------------------------- */

    private boolean showFileChooser(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (pendingFileChooser != null) pendingFileChooser.onReceiveValue(null);
        pendingFileChooser = callback;
        pendingCameraOutput = null;

        Intent content = new Intent(Intent.ACTION_GET_CONTENT);
        content.addCategory(Intent.CATEGORY_OPENABLE);
        content.setType(joinTypes(params.getAcceptTypes()));
        if (params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            content.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }

        Intent chooser = Intent.createChooser(content, getString(R.string.choose_file));

        // The web page marks its camera input with capture=; honour that by
        // offering the camera alongside the picker. No CAMERA permission is
        // requested because the app does not declare one -- the camera app
        // owns the capture.
        if (params.isCaptureEnabled()) {
            Intent capture = buildCaptureIntent(params.getAcceptTypes());
            if (capture != null) {
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{capture});
            }
        }

        try {
            startActivityForResult(chooser, REQ_FILE_CHOOSER);
            return true;
        } catch (ActivityNotFoundException e) {
            pendingFileChooser = null;
            callback.onReceiveValue(null);
            Toast.makeText(this, R.string.no_file_app, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private Intent buildCaptureIntent(String[] acceptTypes) {
        boolean wantsVideo = false;
        for (String type : acceptTypes) {
            if (type != null && type.startsWith("video/")) wantsVideo = true;
        }
        Intent capture = new Intent(wantsVideo
                ? android.provider.MediaStore.ACTION_VIDEO_CAPTURE
                : android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
        if (capture.resolveActivity(getPackageManager()) == null) return null;

        if (!wantsVideo) {
            try {
                java.io.File dir = new java.io.File(getCacheDir(), "captures");
                if (!dir.exists() && !dir.mkdirs()) return null;
                java.io.File photo = new java.io.File(dir, "capture-" + System.currentTimeMillis() + ".jpg");
                pendingCameraOutput = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", photo);
                capture.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, pendingCameraOutput);
                capture.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception e) {
                Log.w(TAG, "camera output unavailable: " + e.getMessage());
                pendingCameraOutput = null;
            }
        }
        return capture;
    }

    private static String joinTypes(String[] acceptTypes) {
        if (acceptTypes == null || acceptTypes.length == 0) return "*/*";
        for (String type : acceptTypes) {
            if (type != null && !type.isEmpty() && !type.startsWith(".")) return type;
        }
        return "*/*";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_SETUP) {
            if (config.isConfigured()) web.loadUrl(config.url() + "/");
            else finish();
            return;
        }

        if (requestCode == REQ_FILE_CHOOSER) {
            ValueCallback<Uri[]> callback = pendingFileChooser;
            pendingFileChooser = null;
            if (callback == null) return;

            if (resultCode != RESULT_OK) {
                callback.onReceiveValue(null);
                return;
            }
            callback.onReceiveValue(chosenUris(data));
        }
    }

    private Uri[] chosenUris(Intent data) {
        // A camera app returns no data at all: the photo went to the URI it
        // was handed, so an empty result means the capture succeeded.
        if (data == null || (data.getData() == null && data.getClipData() == null)) {
            return pendingCameraOutput != null ? new Uri[]{pendingCameraOutput} : null;
        }
        if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            Uri[] uris = new Uri[count];
            for (int i = 0; i < count; i++) uris[i] = data.getClipData().getItemAt(i).getUri();
            return uris;
        }
        return new Uri[]{data.getData()};
    }

    /* ---- the Android share sheet ----------------------------------------- */

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (takeSharedFiles(intent) && web != null) {
            web.evaluateJavascript("window.CloudHubAndroid && window.CloudHubAndroid.collectShared();", null);
        }
    }

    /**
     * Read what the share sheet handed over into memory, for the page to
     * collect and put in its own upload queue.
     */
    private boolean takeSharedFiles(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        List<Uri> uris = new ArrayList<>();

        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) uris.add(uri);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> extra = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (extra != null) uris.addAll(extra);
        }
        if (uris.isEmpty()) return false;

        List<CloudHubBridge.SharedFile> files = new ArrayList<>();
        for (Uri uri : uris) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) continue;
                byte[] bytes = CloudHubBridge.readAll(in, MAX_SHARE_BYTES);
                String name = displayName(uri);
                String mime = getContentResolver().getType(uri);
                files.add(new CloudHubBridge.SharedFile(
                        CloudHubBridge.safeFileName(name),
                        mime == null ? "application/octet-stream" : mime,
                        bytes));
            } catch (Exception e) {
                Log.w(TAG, "could not read a shared file: " + e.getMessage());
                Toast.makeText(this, R.string.share_read_failed, Toast.LENGTH_LONG).show();
            }
        }
        if (files.isEmpty()) return false;
        bridge.offerShared(files);
        return true;
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) { }
        String path = uri.getLastPathSegment();
        return path == null ? "shared" : path;
    }

    /* ---- navigation and menu --------------------------------------------- */

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.menu_reload);
        menu.add(0, 2, 1, R.string.menu_change_server);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            web.reload();
            return true;
        }
        if (item.getItemId() == 2) {
            startActivityForResult(new Intent(this, SetupActivity.class), REQ_SETUP);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /* ---- helpers ---------------------------------------------------------- */

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_LONG).show();
        }
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toString("UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "bridge.js is missing from the APK: " + e.getMessage());
            return null;
        }
    }

    private String describe(SslError error) {
        switch (error.getPrimaryError()) {
            case SslError.SSL_UNTRUSTED: return getString(R.string.cert_untrusted);
            case SslError.SSL_EXPIRED: return getString(R.string.cert_expired);
            case SslError.SSL_IDMISMATCH: return getString(R.string.cert_idmismatch);
            case SslError.SSL_NOTYETVALID: return getString(R.string.cert_notyetvalid);
            default: return getString(R.string.cert_invalid);
        }
    }

    /** SHA-256 of the presented certificate, so the user pins a fact, not a hope. */
    private String fingerprintOf(SslCertificate certificate) {
        try {
            X509Certificate x509 = certificate.getX509Certificate();
            if (x509 == null) return null;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(x509.getEncoded());
            StringBuilder out = new StringBuilder(digest.length * 3);
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) out.append(':');
                out.append(String.format("%02X", digest[i]));
            }
            return out.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
