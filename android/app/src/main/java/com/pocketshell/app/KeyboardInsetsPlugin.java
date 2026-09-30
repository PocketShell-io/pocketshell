package com.pocketshell.app;

import android.os.Build;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewTreeObserver;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
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
        if (hasLastState && imeVisible == lastImeVisible && safeBottomDp == lastSafeBottomDp) return;

        hasLastState = true;
        lastImeVisible = imeVisible;
        lastSafeBottomDp = safeBottomDp;
        notifyListeners("imeInsetsChanged", state);
    }

    private JSObject state(WindowInsetsCompat insets) {
        JSObject state = new JSObject();
        boolean available = insets != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
        boolean imeVisible = available && insets.isVisible(IME_TYPE);
        int safeBottomDp = 0;
        if (available && !imeVisible) {
            Insets safeArea = insets.getInsets(SAFE_AREA_TYPES);
            DisplayMetrics metrics = getActivity().getResources().getDisplayMetrics();
            safeBottomDp = metrics.density <= 0 ? 0 : Math.round(safeArea.bottom / metrics.density);
        }
        state.put("supported", available);
        state.put("imeVisible", imeVisible);
        state.put("safeBottomDp", safeBottomDp);
        return state;
    }
}
