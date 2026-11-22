package com.pocketshell.app.smoke;

import org.json.JSONObject;

/**
 * #3154: JS snippets that make a journey act on, and wait for, UI a user can actually
 * see and tap. A bare {@code node.click()} works on a {@code display:none} element and a
 * presence-only wait passes on a 0x0 node, so a journey could go green while the screen
 * showed something else (J27 opened the New session dialog inside the collapsed phone
 * session panel). Journeys click through {@link #click} and wait through {@link #visible}.
 */
public final class JsVisibility {
    private JsVisibility() {}

    /**
     * JS statements that make the enclosing function return 'FAILED: <reason>' unless {@code nodeVar} (a JS variable holding an element) is rendered with a
     * non-zero box and the topmost element at its centre is inside it. Scrolls it into view first, as a user would.
     */
    private static String guard(String nodeVar, String what) {
        return "if(!" + nodeVar + ")return 'FAILED: missing '+" + what + ";"
                + "if(" + nodeVar + ".scrollIntoView)" + nodeVar + ".scrollIntoView({block:'center',inline:'center'});"
                + "{const r=" + nodeVar + ".getBoundingClientRect();"
                + "if(getComputedStyle(" + nodeVar + ").display==='none'||r.width<=0||r.height<=0)"
                + "return 'FAILED: hidden (0x0 or display:none), not tappable: '+" + what + ";"
                + "const h=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);"
                + "if(!h||!" + nodeVar + ".contains(h))"
                + "return 'FAILED: covered, not tappable: '+" + what
                + "+' (top element: '+(h?h.tagName+'.'+h.className:'none')+'; rect='+[r.left,r.top,r.width,r.height].map(Math.round)"
                + "+'; viewport='+innerWidth+'x'+innerHeight+'; ancestors='+(()=>{const o=[];for(let a=" + nodeVar + ".parentElement;a&&o.length<8;a=a.parentElement){"
                + "const c=getComputedStyle(a),q=a.getBoundingClientRect();o.push(a.tagName.toLowerCase()+'.'+String(a.className).split(' ')[0]+'['+c.display+'|'+c.position+'|'+c.transform+'|'+Math.round(q.left)+','+Math.round(q.width)+']');}return o.join('>');})()+')';}";
    }

    /** JS statements that guard then click the element held in {@code nodeVar}. */
    public static String clickNode(String nodeVar, String what) {
        return guard(nodeVar, JSONObject.quote(what)) + nodeVar + ".click();";
    }

    /** JS expression (an IIFE returning 'clicked') that guards then clicks {@code nodeExpr}; a missing node throws. */
    public static String click(String nodeExpr) {
        return "(()=>{try{const __n=" + nodeExpr + ";" + clickNode("__n", nodeExpr)
                + "return 'clicked';}catch(e){return 'FAILED: '+e.message;}})()";
    }

    /** Like {@link #click} but a missing node is a no-op ('absent'); a present-but-hidden node still throws. */
    public static String clickIfPresent(String nodeExpr) {
        return "(()=>{try{const __n=" + nodeExpr + ";if(!__n)return 'absent';" + clickNode("__n", nodeExpr)
                + "return 'clicked';}catch(e){return 'FAILED: '+e.message;}})()";
    }

    /** Throws when a raw evaluateJavascript result is a guard failure ('FAILED: ...'), keeping the page-side reason. */
    public static String failOnGuard(String raw) {
        if (raw != null && raw.startsWith("\"FAILED: ")) throw new AssertionError(raw);
        return raw;
    }

    /** Fails the journey with the page-side reason when a {@link #click} result is not a click. */
    public static String expectClicked(String result) {
        if (result != null && result.startsWith("FAILED: ")) throw new AssertionError(result);
        return result;
    }

    /** JS boolean expression: {@code selector} matches an element that is rendered, non-zero sized and topmost at its centre. */
    public static String visible(String selector) {
        return visibleNode("document.querySelector(" + JSONObject.quote(selector) + ")");
    }

    /** JS boolean expression for an arbitrary node expression (laid out, centre inside the viewport, topmost there). */
    public static String visibleNode(String nodeExpr) {
        return "(()=>{const __n=" + nodeExpr + ";if(!__n)return false;"
                + "const r=__n.getBoundingClientRect();if(r.width<=0||r.height<=0)return false;"
                + "if(getComputedStyle(__n).display==='none')return false;"
                + "const x=r.left+r.width/2,y=r.top+r.height/2;"
                + "if(x<0||y<0||x>innerWidth||y>innerHeight)return false;"
                + "const h=document.elementFromPoint(x,y);return !!h&&__n.contains(h);})()";
    }

    /** Evaluates JavaScript in the journey's WebView and returns the raw result (a journey's own evalRaw). */
    public interface Eval {
        String eval(String js) throws Exception;
    }

    private static final String[] NAVIGATION_MARKERS = {
        "open-sessions", "open-hosts", "open-terminal-settings", "Settings", "Back to hosts", "button.tab", "button.rail",
    };

    /** True for app-bar / navigation targets, which a modal sheet's scrim covers. */
    public static boolean isNavigationTarget(String selector) {
        for (String marker : NAVIGATION_MARKERS) if (selector.contains(marker)) return true;
        return false;
    }

    /**
     * #3154: before tapping app-bar / navigation, dismiss the transient modal the way a user does. The composer is a
     * modal bottom sheet whose scrim covers the whole app bar (and a sheet persisted open comes back after a restart),
     * so a navigation tap under it hits the scrim. Taps the scrim above the sheet (the point must hit the scrim), then
     * waits for the sheet to go. The draft persists, so reopening the composer restores it. No-op when no sheet is open.
     * Every journey's click helper calls this, so no nav call site needs its own dismissal.
     */
    public static void prepareForNavigation(String selector, Eval eval) throws Exception {
        if (!isNavigationTarget(selector)) return;
        if (!"true".equals(eval.eval("!!document.querySelector('[data-testid=prompt-composer-scrim]')"))) return;
        android.util.Log.i("PocketshellImeJourney", "NAV_AUTO_DISMISS composer sheet open before navigating to " + selector
                + "; tapping its scrim (composer=" + eval.eval("(()=>{const c=document.querySelector('[data-testid=prompt-composer]');"
                + "return c?JSON.stringify({target:c.dataset.targetKey||null,draftLength:(document.querySelector('#prompt-draft')||{value:''}).value.length}):'none';})()") + ")");
        String dismiss = clickComposerScrimAboveSheet();
        long tapDeadline = System.currentTimeMillis() + 8_000;
        while (true) {
            String last = stripQuotes(eval.eval(dismiss));
            if (last != null && !last.startsWith("FAILED: ")) break;
            if (System.currentTimeMillis() > tapDeadline) throw new AssertionError(last);
            Thread.sleep(250);
        }
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if ("false".equals(eval.eval("!!document.querySelector('[data-testid=prompt-composer-scrim]')"))) return;
            Thread.sleep(150);
        }
        throw new AssertionError("the composer sheet did not close after its scrim was tapped, so navigation to "
                + selector + " is still covered");
    }

    /**
     * JS expression (an IIFE) that clicks the composer scrim where a user taps it: in the strip ABOVE the sheet. The
     * scrim is a full-viewport layer under the sheet, so its geometric centre is covered by the sheet whenever the sheet
     * is taller than half the viewport (the keyboard shrinks the viewport, and the sheet may reach 86dvh); a centre
     * guard then fails "covered, not tappable" on the sheet handle (run 45009416259). The sheet's max-height leaves at
     * least 14dvh of scrim above it, so the midpoint of that strip is the scrim. Returns 'clicked', 'absent' (sheet
     * already gone) or 'FAILED: <reason>' with geometry; clicks nothing on failure, so a retry cannot double-click.
     */
    static String clickComposerScrimAboveSheet() {
        return "(()=>{try{const s=document.querySelector('[data-testid=prompt-composer-scrim]');if(!s)return 'absent';"
                + "const p=document.querySelector('[data-testid=prompt-composer]');"
                + "const r=s.getBoundingClientRect();"
                + "if(getComputedStyle(s).display==='none'||r.width<=0||r.height<=0)return 'FAILED: composer scrim hidden (0x0 or display:none)';"
                + "const top=Math.min(r.bottom,p?p.getBoundingClientRect().top:r.bottom);"
                + "const x=r.left+r.width/2,y=Math.max(r.top,0)+(top-Math.max(r.top,0))/2;"
                + "const h=document.elementFromPoint(x,y);"
                + "if(top-Math.max(r.top,0)<2||!h||!s.contains(h))return 'FAILED: composer scrim not tappable above the sheet (top element: '"
                + "+(h?h.tagName+'.'+h.className:'none')+'; point='+Math.round(x)+','+Math.round(y)+'; sheetTop='+Math.round(top)"
                + "+'; scrim='+[r.left,r.top,r.width,r.height].map(Math.round)+'; viewport='+innerWidth+'x'+innerHeight+')';"
                + "s.click();return 'clicked';}catch(e){return 'FAILED: '+e.message;}})()";
    }

    /**
     * The one way a journey taps a selector: dismisses a covering sheet for navigation targets, then clicks, retrying
     * for up to 8 s while a route or sheet transition still has the target missing, hidden or covered. The guard
     * clicks nothing on failure, so a retry cannot double-click. When it never lands, throws with the page-side reason.
     */
    public static void tap(String selector, Eval eval) throws Exception {
        prepareForNavigation(selector, eval);
        String script = click("document.querySelector(" + JSONObject.quote(selector) + ")");
        long deadline = System.currentTimeMillis() + 8_000;
        while (true) {
            String last;
            try {
                last = stripQuotes(eval.eval(script));
            } catch (AssertionError guardFailure) {
                last = stripQuotes(String.valueOf(guardFailure.getMessage()));
            }
            if (last != null && !last.startsWith("FAILED: ")) return;
            if (System.currentTimeMillis() > deadline) throw new AssertionError(last);
            Thread.sleep(250);
        }
    }

    private static String stripQuotes(String raw) {
        return raw != null && raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"") ? raw.substring(1, raw.length() - 1) : raw;
    }

    /**
     * One poll of a wait condition: the raw result, or a "<no result: ...>" marker when the page returned null or the
     * script threw (a cold or navigating page: body not there yet, bridge not ready). A poll loop treats that as "not
     * ready, keep polling" and reports the last marker on timeout, instead of the first null aborting the wait.
     */
    public static String pollRaw(Eval eval, String expression) throws Exception {
        try {
            return eval.eval(expression);
        } catch (org.json.JSONException nullResult) {
            return "<no result: " + nullResult.getMessage() + ">";
        }
    }

    /** Wraps a boolean condition so a throwing page (body or storage not there yet) reads as false, never null. */
    public static String nullSafe(String condition) {
        return "(()=>{try{return !!(" + condition + ");}catch(e){return false;}})()";
    }

    /** Page text for a wait's timeout message; a cold page (null body) reads as a marker instead of masking the timeout. */
    public static String pageText(Eval eval) {
        try {
            String raw = eval.eval("(()=>{try{return document.body.innerText;}catch(e){return '<no body: '+e.message+'>';}})()");
            return raw;
        } catch (Exception error) {
            return "<page unavailable: " + error + ">";
        }
    }
}
