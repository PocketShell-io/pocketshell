package com.pocketshell.app;

import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.WebView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.CapConfig;
import com.getcapacitor.WebViewListener;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends BridgeActivity {
    private static final String TAG = "MainActivity";
    private static final int MAX_RESUME_LAYOUT_ATTEMPTS = 30;
    private static final int WINDOW_HEIGHT_TOLERANCE_PX = 120;
    private static final long RESUME_LAYOUT_RETRY_DELAY_MS = 50L;
    /**
     * Launch extra opting into the shared PocketShell app (#2936). The
     * pre-#2936 phone screens stay the default until the shared app reaches
     * parity (#2941) and the maintainer signs it off; then the default flips
     * and this extra is deleted with the last legacy screen. Read in
     * {@link #load()}, before the WebView loads anything, so a launch boots
     * exactly one shell (no load-then-reload).
     */
    public static final String EXTRA_SHELL = "pocketshell.shell";
    public static final String SHELL_SHARED = "shared";
    /** The start path the web entry reads (src/shellSelection.ts). */
    static final String SHARED_SHELL_START_PATH = "/?shell=shared";

    private Runnable resumeWebViewLayoutRefresh;
    /** Main-frame page loads of this activity's WebView; one per launch (#2936). */
    private final AtomicInteger pageStarts = new AtomicInteger();

    /** How many pages this activity's WebView has started loading. A launch loads exactly one. */
    public int pageStartCount() {
        return pageStarts.get();
    }
    private int resumeWebViewLayoutAttempts;

    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        // Registered first: its JavaScript interface must exist before the page loads (#2993).
        registerPlugin(DurableStoragePlugin.class);
        registerPlugin(SshCapabilityPlugin.class);
        registerPlugin(SshKeyVaultPlugin.class);
        registerPlugin(KeyboardInsetsPlugin.class);
        registerPlugin(InstalledDataMigrationPlugin.class);
        registerPlugin(DocumentContentPlugin.class);
        registerPlugin(SpeechRecognitionPlugin.class);
        super.onCreate(savedInstanceState);
    }

    /**
     * Pick the shell before the bridge is built: Capacitor's first and only
     * page load is {@code appUrl + server.appStartPath}, so the shared app's
     * opt-in becomes that start path rather than a reload of a page that has
     * already booted the default shell.
     */
    @Override
    protected void load() {
        bridgeBuilder.addWebViewListener(new WebViewListener() {
            @Override
            public void onPageStarted(WebView webView) {
                pageStarts.incrementAndGet();
            }
        });
        if (SHELL_SHARED.equals(getIntent().getStringExtra(EXTRA_SHELL))) {
            config = sharedShellConfig();
        }
        super.load();
    }

    private CapConfig sharedShellConfig() {
        CapConfig defaults = CapConfig.loadDefault(this);
        try {
            JSONObject json = new JSONObject(readAsset("capacitor.config.json"));
            JSONObject server = json.optJSONObject("server");
            if (server == null) server = new JSONObject();
            server.put("appStartPath", SHARED_SHELL_START_PATH);
            json.put("server", server);
            // The JSON constructor cannot see the APK's debuggable flag; carry
            // the two values loadDefault derived from it.
            JSONObject android = json.optJSONObject("android");
            if (android == null) android = new JSONObject();
            if (!android.has("webContentsDebuggingEnabled")) {
                android.put("webContentsDebuggingEnabled", defaults.isWebContentsDebuggingEnabled());
            }
            if (!android.has("loggingBehavior")) {
                android.put("loggingBehavior", defaults.isLoggingEnabled() ? "production" : "none");
            }
            json.put("android", android);
            @SuppressWarnings("deprecation")
            CapConfig shared = new CapConfig(getAssets(), json);
            return shared;
        } catch (IOException | JSONException error) {
            Log.e(TAG, "Shared shell requested but the Capacitor config could not be read", error);
            return defaults;
        }
    }

    private String readAsset(String name) throws IOException {
        try (InputStream input = getAssets().open(name)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        scheduleWebViewLayoutRefresh();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) scheduleWebViewLayoutRefresh();
    }

    @Override
    public void onPause() {
        cancelWebViewLayoutRefresh();
        super.onPause();
    }

    private void scheduleWebViewLayoutRefresh() {
        if (resumeWebViewLayoutRefresh != null || getBridge() == null) return;
        View decor = getWindow().getDecorView();
        resumeWebViewLayoutAttempts = 0;
        resumeWebViewLayoutRefresh = new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed() || !hasWindowFocus() || getBridge() == null) {
                    resumeWebViewLayoutRefresh = null;
                    return;
                }

                WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(decor);
                if (insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())) {
                    retryOrStop("IME remained visible after resume");
                    return;
                }

                WebView webView = getBridge().getWebView();
                if (webView == null) {
                    resumeWebViewLayoutRefresh = null;
                    return;
                }
                Rect visibleFrame = new Rect();
                decor.getWindowVisibleDisplayFrame(visibleFrame);
                int minimumWindowContentHeight = visibleFrame.height();
                if (minimumWindowContentHeight <= 0
                        || webView.getHeight() >= minimumWindowContentHeight - WINDOW_HEIGHT_TOLERANCE_PX) {
                    resumeWebViewLayoutRefresh = null;
                    return;
                }

                requestLayout(decor);
                requestLayout(findViewById(android.R.id.content));
                ViewParent parent = webView.getParent();
                if (parent instanceof View parentView) requestLayout(parentView);
                ViewGroup.LayoutParams webViewLayoutParams = webView.getLayoutParams();
                if (webViewLayoutParams != null) webView.setLayoutParams(webViewLayoutParams);
                requestLayout(webView);

                resumeWebViewLayoutAttempts += 1;
                if (resumeWebViewLayoutAttempts >= MAX_RESUME_LAYOUT_ATTEMPTS) {
                    Log.w(TAG, "Capacitor WebView did not recover its full window height after resume: view="
                            + webView.getHeight() + "px, window=" + decor.getHeight() + "px");
                    resumeWebViewLayoutRefresh = null;
                    return;
                }
                decor.postDelayed(this, RESUME_LAYOUT_RETRY_DELAY_MS);
            }

            private void retryOrStop(String reason) {
                resumeWebViewLayoutAttempts += 1;
                if (resumeWebViewLayoutAttempts >= MAX_RESUME_LAYOUT_ATTEMPTS) {
                    Log.w(TAG, "Skipping WebView relayout after resume: " + reason);
                    resumeWebViewLayoutRefresh = null;
                    return;
                }
                decor.postDelayed(this, RESUME_LAYOUT_RETRY_DELAY_MS);
            }
        };
        decor.postOnAnimation(resumeWebViewLayoutRefresh);
    }

    private void cancelWebViewLayoutRefresh() {
        if (resumeWebViewLayoutRefresh == null) return;
        getWindow().getDecorView().removeCallbacks(resumeWebViewLayoutRefresh);
        resumeWebViewLayoutRefresh = null;
    }

    private void requestLayout(View view) {
        if (view == null) return;
        view.requestApplyInsets();
        view.forceLayout();
        view.requestLayout();
    }
}
