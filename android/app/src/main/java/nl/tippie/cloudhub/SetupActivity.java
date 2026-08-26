package nl.tippie.cloudhub;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * First run: ask where the server is.
 *
 * The address is checked before it is accepted, because a typo here otherwise
 * shows up much later as a blank screen with no explanation. A certificate
 * error is not treated as failure -- a private CA behind a VPN is exactly the
 * arrangement this app is for -- so the probe reports it and lets the user
 * continue, where MainActivity handles the certificate properly.
 */
public class SetupActivity extends Activity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText input;
    private TextView status;
    private Button connect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        input = findViewById(R.id.server_url);
        status = findViewById(R.id.status);
        connect = findViewById(R.id.connect);

        ServerConfig config = new ServerConfig(this);
        if (config.url() != null) input.setText(config.url());

        connect.setOnClickListener(v -> attempt());
    }

    private void attempt() {
        final String url = ServerConfig.normalise(input.getText().toString());
        if (url == null) {
            status.setText(R.string.setup_enter_address);
            return;
        }

        connect.setEnabled(false);
        status.setText(R.string.setup_checking);

        io.execute(() -> {
            final Probe result = probe(url);
            ui.post(() -> {
                connect.setEnabled(true);
                if (result.reachable) {
                    // Plain HTTP is allowed -- a LAN server is a legitimate
                    // setup -- but it costs the offline features, so say so
                    // rather than letting them quietly not work.
                    new ServerConfig(SetupActivity.this).setUrl(url);
                    setResult(RESULT_OK);
                    finish();
                } else {
                    status.setText(getString(R.string.setup_failed, result.detail));
                }
            });
        });
    }

    private static final class Probe {
        final boolean reachable;
        final String detail;
        Probe(boolean reachable, String detail) { this.reachable = reachable; this.detail = detail; }
    }

    /**
     * Ask the server whether it is a CloudHub.
     *
     * /api/auth/status answers without a session, so it works before anyone has
     * signed in, and it is JSON rather than the HTML shell -- which means a
     * captive portal or an unrelated web server is not mistaken for a match.
     */
    private Probe probe(String base) {
        HttpURLConnection connection = null;
        try {
            URL target = new URL(base + "/?route=%2Fapi%2Fauth%2Fstatus");
            connection = (HttpURLConnection) target.openConnection();
            if (connection instanceof HttpsURLConnection) {
                // A private or self-signed certificate is expected here and is
                // resolved in MainActivity, where the user can see and accept
                // the fingerprint. Do not fail the probe over it.
                ((HttpsURLConnection) connection).setHostnameVerifier((h, s) -> true);
            }
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
            if (code >= 200 && code < 500) return new Probe(true, "");
            return new Probe(false, "HTTP " + code);
        } catch (javax.net.ssl.SSLException e) {
            // Reached the host; only the certificate is in question.
            return new Probe(true, "");
        } catch (Exception e) {
            String message = e.getMessage();
            return new Probe(false, message == null ? e.getClass().getSimpleName() : message);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
