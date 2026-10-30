package com.pocketshell.app.smoke;

import org.json.JSONException;
import org.json.JSONObject;

/** Replays #3101's old viewport extent while the real Android IME is shown. */
final class AndroidViewportResizeProbe {
    private AndroidViewportResizeProbe() {}

    static String installScript() {
        return "(() => {"
                + "const cap=window.Capacitor;const original=cap.nativePromise;"
                + "let viewportRule;for(const sheet of document.styleSheets){for(const rule of sheet.cssRules){"
                + "if(rule.selectorText==='.app-shell'&&rule.style.height==='100dvh')viewportRule=rule;}}"
                + "if(!viewportRule)throw new Error('generic dynamic viewport declaration unavailable');"
                + "const oldHeight=viewportRule.style.height;"
                // Model only the generic viewport-unit resolution. Android's
                // normal cascade must contain the shell in its resized window.
                + "viewportRule.style.height=screen.height+'px';"
                + "cap.nativePromise=(plugin,method,options)=>plugin==='KeyboardInsets'&&method==='getState'"
                + "?Promise.resolve({supported:true,imeVisible:false,safeBottomDp:24})"
                + ":original.call(cap,plugin,method,options);"
                + "window.__ps3101RestoreViewport=()=>{viewportRule.style.height=oldHeight;"
                + "cap.nativePromise=original;delete window.__ps3101RestoreViewport;"
                + "window.dispatchEvent(new Event('resize'));};"
                + "window.dispatchEvent(new Event('resize'));return 'controlled stale viewport installed';})()";
    }

    static JSONObject compactGeometry(JSONObject frozen) throws JSONException {
        return new JSONObject().put("controlledViewport", true)
                .put("nativeIme", frozen.getJSONObject("androidIme"))
                .put("keyboardVisible", frozen.getBoolean("keyboardVisible"))
                .put("innerHeight", frozen.getInt("innerHeight"))
                .put("imeEdgeCssY", frozen.getDouble("imeEdgeCssY"))
                .put("mic", frozen.getJSONObject("inlineDictationMic"))
                .put("terminalPanel", frozen.getJSONObject("terminalPanel"));
    }

    static String restoreScript() {
        return "window.__ps3101RestoreViewport?.();'viewport and native bridge restored'";
    }
}
