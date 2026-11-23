package com.pocketshell.app.smoke;

import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * The post-Send terminal as one settled state, waited for before a journey measures it.
 *
 * <p>Send dismisses the keyboard, and the terminal is refitted through several grids on the
 * way down: the keyboard-up grid, a taller one for each frame in which the still-docked
 * composer leaves the retreating IME's pixels to the terminal, and only then the
 * inline-composer grid. The buffer takes every fit at once. xterm's renderer sizes its
 * screen box and trims its row container in a resize it defers while its screen is out of
 * view, so after the layout, the rows and the accepted size are final the {@code
 * .xterm-screen} box can still carry the previous grid (run 45170544379 on the SHA that
 * also passed in run 45029832584: a 448px, 21-row box in a 289px viewport whose 12 rows
 * were already rendered). A viewport read in that window measures the transition; in the
 * passing run the read came 335 ms later and found the 12-row box.
 */
final class PostSendTerminalSettle {
    private PostSendTerminalSettle() {}

    /** Reads whether the Android IME is on screen; false only once the keyboard is gone. */
    interface ImeProbe {
        boolean visible() throws Exception;
    }

    private static final long TIMEOUT_MILLIS = 5_000;
    private static final int STABLE_POLLS = 3;

    /**
     * JS expression returning the settle state as JSON: {@code settled} plus every reason
     * it is not, and the geometry and fits that explain it.
     *
     * <p>Settled means the keyboard is down, the fitted grid is the size SSH accepted, the
     * rendered row count is that grid (xterm keeps one spare row element after a growth),
     * the screen box is exactly that many row heights, and the box is inside the viewport.
     */
    static String expression() {
        return "(() => {const shell=document.querySelector('.app-shell');"
                + "const viewport=document.querySelector('.terminal-viewport');"
                + "const screen=viewport?.querySelector('.xterm-screen');"
                + "const rows=Array.from(viewport?.querySelectorAll('.xterm-rows > div') ?? []);"
                + "let state=null;try{state=JSON.parse(window.__ps2857TerminalBufferState||'null');}catch(error){state=null;}"
                + "const box=node=>{const r=node?.getBoundingClientRect();"
                + "return r?{top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height}:null;};"
                + "const fitted=typeof state?.cols==='number'&&typeof state?.rows==='number'?state:null;"
                + "const status=document.querySelector('[data-testid=terminal-resize-status]')?.dataset.status??'';"
                + "const accepted=/^(\\d+) \\u00d7 (\\d+) accepted by SSH$/.exec(status);"
                + "const view=box(viewport),screenBox=box(screen),cell=rows[0]?.getBoundingClientRect().height??0;"
                + "const unsettled=[];"
                + "if(!viewport||!screen||rows.length===0)unsettled.push('terminal grid is not mounted');"
                + "if(!fitted)unsettled.push('terminal buffer state is not recorded');"
                + "if(shell?.dataset.keyboardVisible!=='false')unsettled.push('keyboard is still visible');"
                + "if(!fitted||!accepted||Number(accepted[1])!==fitted.cols||Number(accepted[2])!==fitted.rows)"
                + "unsettled.push('fitted grid is not the size SSH accepted');"
                + "if(fitted&&rows.length>0&&rows.length!==fitted.rows&&rows.length!==fitted.rows+1)"
                + "unsettled.push('rendered row count differs from the fitted grid');"
                + "if(fitted&&cell>0&&screenBox&&Math.abs(screenBox.height-fitted.rows*cell)>0.5)"
                + "unsettled.push('xterm screen box differs from the fitted grid');"
                + "if(view&&screenBox&&(screenBox.top<view.top-0.5||screenBox.bottom>view.bottom+0.5"
                + "||screenBox.left<view.left-0.5||screenBox.right>view.right+0.5))"
                + "unsettled.push('xterm screen is outside the terminal viewport');"
                + "return JSON.stringify({settled:unsettled.length===0,unsettled,"
                + "keyboardVisible:shell?.dataset.keyboardVisible??null,"
                + "keyboardComposerMode:shell?.dataset.keyboardComposerMode??null,"
                + "resizeStatus:status,cols:fitted?.cols??null,rows:fitted?.rows??null,"
                + "domRowCount:rows.length,rowHeight:cell,screenStyleHeight:screen?.style.height??null,"
                + "terminalViewport:view,terminalScreen:screenBox,"
                + "visualViewportHeight:window.visualViewport?.height??innerHeight,"
                + "resizeFitEvents:window.__ps2884ResizeFitEvents??[]});})()";
    }

    /**
     * One read of {@link #expression()}. The page hands its JSON back as a JS string, so
     * the evaluator's JSON text decodes to a String, which is the state to parse. A page
     * that returned nothing readable yields an unsettled state saying so, never a pass.
     */
    static JSONObject read(JsVisibility.Eval eval) throws Exception {
        String raw = JsVisibility.pollRaw(eval, expression());
        Object decoded = null;
        boolean readable = raw != null;
        if (readable) {
            try {
                decoded = new JSONTokener(raw).nextValue();
            } catch (JSONException unreadable) {
                readable = false;
            }
        }
        String state = decoded instanceof String ? (String) decoded : null;
        try {
            return state != null ? new JSONObject(state)
                    : new JSONObject().put("settled", false).put("unreadable",
                            raw == null ? "null" : raw.substring(0, Math.min(raw.length(), 400)));
        } catch (JSONException invalidState) {
            throw new IllegalStateException("post-Send settle state is not a JSON object: " + raw, invalidState);
        }
    }

    /**
     * Block until the terminal is settled, without touching it, and return how long that
     * took with the settled state. The state must hold for {@link #STABLE_POLLS} consecutive
     * polls with the IME gone, so a grid that is still moving is not captured. A terminal
     * that never settles fails with its geometry and every fit since Send.
     */
    static JSONObject await(JsVisibility.Eval eval, ImeProbe ime) throws Exception {
        long startedUptimeMs = SystemClock.uptimeMillis();
        long deadline = startedUptimeMs + TIMEOUT_MILLIS;
        int stablePolls = 0;
        JSONObject last = null;
        while (SystemClock.uptimeMillis() < deadline) {
            last = read(eval);
            stablePolls = last != null && last.optBoolean("settled", false) && !ime.visible()
                    ? stablePolls + 1 : 0;
            if (stablePolls >= STABLE_POLLS) {
                JSONObject outcome = new JSONObject()
                        .put("waitedMs", SystemClock.uptimeMillis() - startedUptimeMs)
                        .put("stablePolls", stablePolls)
                        .put("state", last);
                Log.i("PS2857Settle", "POST_SEND_SETTLED|" + outcome);
                return outcome;
            }
            Thread.sleep(60);
        }
        throw new AssertionError("the post-Send terminal did not settle on the SSH-accepted grid inside the visible "
                + "terminal viewport: " + last);
    }
}
