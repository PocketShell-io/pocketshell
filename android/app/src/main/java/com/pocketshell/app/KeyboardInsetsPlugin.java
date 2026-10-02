package com.pocketshell.app;

import android.os.Build;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewTreeObserver;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Exposes Android's IME visibility and remaining safe bottom inset to the JS shell. */
@CapacitorPlugin(name = "KeyboardInsets")
public final class KeyboardInsetsPlugin extends Plugin {
    private static final int SAFE_AREA_TYPES =
            WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout();
    private static final int IME_TYPE = WindowInsetsCompat.Type.ime();

    private View decorView;
    private ViewTreeObserver.OnGlobalLayoutListener globalLayoutListener;
    private boolean hasLastState;
    private boolean lastImeVisible;
    private int lastSafeBottomDp;
    private int lastImeOverlapDp;

    @Override
    public void load() {
        super.load();
        getActivity().runOnUiThread(() -> {
            decorView = getActivity().getWindow().getDecorView();
            // Read root insets after layout instead of intercepting dispatch.
            // Capacitor SystemBars and DecorView retain their own inset listeners.
            globalLayoutListener = () -> publishIfChanged(ViewCompat.getRootWindowInsets(decorView));
            decorView.getViewTreeObserver().addOnGlobalLayoutListener(globalLayoutListener);
            decorView.post(() -> publishIfChanged(ViewCompat.getRootWindowInsets(decorView)));
        });
    }

    @PluginMethod
    public void getState(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            View view = decorView != null ? decorView : getActivity().getWindow().getDecorView();
            call.resolve(state(ViewCompat.getRootWindowInsets(view)));
        });
    }

    @PluginMethod
    public void hideIme(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            View view = decorView != null ? decorView : getActivity().getWindow().getDecorView();
            WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(
                    getActivity().getWindow(), view);
            controller.hide(WindowInsetsCompat.Type.ime());
            call.resolve();
        });
    }

    @Override
    protected void handleOnDestroy() {
        View view = decorView;
        if (view != null) {
            ViewTreeObserver observer = view.getViewTreeObserver();
            if (observer.isAlive() && globalLayoutListener != null) {
                observer.removeOnGlobalLayoutListener(globalLayoutListener);
            }
            globalLayoutListener = null;
            decorView = null;
        }
    }

    private void publishIfChanged(WindowInsetsCompat insets) {
        JSObject state = state(insets);
        boolean imeVisible = Boolean.TRUE.equals(state.getBool("imeVisible"));
        Integer safeBottom = state.getInteger("safeBottomDp", 0);
        int safeBottomDp = safeBottom == null ? 0 : safeBottom;
        Integer overlap = state.getInteger("imeOverlapDp", 0);
        int imeOverlapDp = overlap == null ? 0 : overlap;
        if (hasLastState && imeVisible == lastImeVisible && safeBottomDp == lastSafeBottomDp
                && imeOverlapDp == lastImeOverlapDp) return;

        hasLastState = true;
        lastImeVisible = imeVisible;
        lastSafeBottomDp = safeBottomDp;
        lastImeOverlapDp = imeOverlapDp;
        notifyListeners("imeInsetsChanged", state);
    }

    /**
     * How far the WebView still reaches under the IME, in screen pixels.
     * adjustResize normally shrinks the WebView so this is 0, but on starved
     * hosted emulators the IME inset has been applied to the window without
     * the WebView being re-laid out (#2884, run 36938038761): the WebView
     * stayed full height and the dock sat under the keyboard. JS reserves this
     * overlap at the bottom of the shell so the dock stays above the IME.
     */
    private int imeOverlapPx(int imeBottomPx) {
        View root = decorView != null ? decorView : getActivity().getWindow().getDecorView();
        View webView = getBridge() == null ? null : getBridge().getWebView();
        if (root == null || webView == null || root.getHeight() <= 0) return 0;
        int[] rootLocation = new int[2];
        int[] webLocation = new int[2];
        root.getLocationOnScreen(rootLocation);
        webView.getLocationOnScreen(webLocation);
        int imeTopPx = rootLocation[1] + root.getHeight() - imeBottomPx;
        int webViewBottomPx = webLocation[1] + webView.getHeight();
        return Math.max(0, webViewBottomPx - imeTopPx);
    }

    private JSObject state(WindowInsetsCompat insets) {
        JSObject state = new JSObject();
        boolean available = insets != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
        boolean imeVisible = available && insets.isVisible(IME_TYPE);
        int safeBottomDp = 0;
        int imeOverlapDp = 0;
        DisplayMetrics metrics = getActivity().getResources().getDisplayMetrics();
        if (available && !imeVisible) {
            Insets safeArea = insets.getInsets(SAFE_AREA_TYPES);
            safeBottomDp = metrics.density <= 0 ? 0 : Math.round(safeArea.bottom / metrics.density);
        }
        if (available && imeVisible && metrics.density > 0) {
            imeOverlapDp = Math.round(imeOverlapPx(insets.getInsets(IME_TYPE).bottom) / metrics.density);
        }
        state.put("supported", available);
        state.put("imeVisible", imeVisible);
        state.put("safeBottomDp", safeBottomDp);
        state.put("imeOverlapDp", imeOverlapDp);
        return state;
    }
}
