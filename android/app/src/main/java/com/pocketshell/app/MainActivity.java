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

public class MainActivity extends BridgeActivity {
    private static final String TAG = "MainActivity";
    private static final int MAX_RESUME_LAYOUT_ATTEMPTS = 30;
    private static final int WINDOW_HEIGHT_TOLERANCE_PX = 120;
    private static final long RESUME_LAYOUT_RETRY_DELAY_MS = 50L;

    private Runnable resumeWebViewLayoutRefresh;
    private int resumeWebViewLayoutAttempts;

    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(SshCapabilityPlugin.class);
        registerPlugin(KeyboardInsetsPlugin.class);
        registerPlugin(InstalledDataMigrationPlugin.class);
        super.onCreate(savedInstanceState);
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
