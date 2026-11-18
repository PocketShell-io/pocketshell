package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.pocketshell.app.SharedShellLaunch;
import com.pocketshell.app.ime.ScriptedIme;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Mobile layout class proof for #3120 (S1 of #3118), on the real packaged app.
 *
 * <p>At the width the runner selects ({@code wm density}; 360x800dp or 412x915dp, label in
 * {@code layoutLabel}) it walks hosts -> session tree -> workspace (and the workspace with the
 * IME up) and measures production elements through {@code getBoundingClientRect()} in the live
 * WebView:
 * <ul>
 *   <li>every visible {@code .icon-btn}, {@code .btn-ghost}, host row and tree folder row is at
 *       least 48x48 CSS px under the real {@code pointer: coarse};</li>
 *   <li>the workspace root's bottom edge is at or above {@code innerHeight - --ps-inset-bottom}
 *       while the native navigation and status bars report non-zero insets and the CSS inset
 *       variables carry them.</li>
 * </ul>
 * Every violation is collected with its measured values and reported in one failure, after the
 * JSON evidence ({@code ps2952-s1-layout-<label>.json}) and the screenshots are saved.
 * Counts are asserted non-zero so an empty screen cannot pass.
 *
 * <p>Terminal density and glyphs (#3157), on the maintainer's exact state: the app starts with
 * the settings blob a pre-#3157 build persisted ({@code terminalFontSize: 16}, written whole on
 * any settings change). In the workspace the Docker host's own PTY reports its width
 * ({@code stty size}), which must equal the grid the WebView laid out; the side gutters are
 * at most 2 CSS px; at the 412 class the columns with the left rail removed are 55-60
 * (Termius on the same phone: ~57; 12px JetBrains Mono gives 56), and both counts — with and without the rail — are
 * recorded. A Claude Code screen is then {@code cat} from the host and every symbol it draws
 * (⏵ ✻ ⎿ ❯ ● ○ ⧉, box drawing) is rendered in the terminal's own font stack on a canvas and
 * compared with the .notdef of an unassigned code point: none may be tofu.
 */
public class J26S1LayoutJourney extends SharedAppDockerJourneyTest {
    private static final double MIN_HIT = 48.0;
    /** Sub-pixel slack for rounding of fractional layout, nothing more. */
    private static final double EPS = 0.5;

    /** #3157: the side gutter budget, and the column target at 411dp with the rail gone. */
    private static final double MAX_GUTTER = 2.0;
    private static final int MIN_COLUMNS = 55;
    private static final int MAX_COLUMNS = 60;
    private static final String SHARED_SETTINGS_KEY = "pocketshell.settings.v1";
    /** What a pre-#3157 build left behind: the settings store persisted its whole object, desktop 16px included. */
    private static final String PRE_3157_SETTINGS = "{\"terminalFontSize\":16,\"theme\":\"dark\"}";
    /** Claude Code's symbols the maintainer saw as tofu, plus the box drawing around them (core TERMINAL_SYMBOL_PROBE). */
    private static final String SYMBOL_PROBE = "\u23f5\u23f8\u23fa\u273b\u2733\u2736\u2722\u00b7\u23bf\u276f\u25cf"
            + "\u25cb\u25ef\u25c9\u2193\u2191\u2190\u2192\u2713\u2714\u2717\u2718\u29c9\u2026\u2502\u2500\u256d"
            + "\u256e\u256f\u2570\u251c\u2524\u2590\u259b\u259c\u258c";
    private static final Pattern HOST_SIZE = Pattern.compile("PSCOLS=(\\d+)x(\\d+)");

    private static final String VISIBLE_XTERM = "[...document.querySelectorAll('.xterm-screen')].find((n)=>n.offsetParent!==null)";

    /** The laid-out grid: cell width from xterm's own measure element, gutters, rail, scrollbar reservation. */
    private static final String TERMINAL_GEOMETRY_JS = "JSON.stringify((() => {"
            + "const scr=" + VISIBLE_XTERM + ";if(!scr)return null;"
            + "const xt=scr.closest('.xterm');const pane=scr.closest('.terminal');"
            + "const meas=xt.querySelector('.xterm-char-measure-element');"
            + "const rows=xt.querySelector('.xterm-rows');const rcs=getComputedStyle(rows);const xcs=getComputedStyle(xt);"
            + "const sb=xt.querySelector('.xterm-scrollable-element > .scrollbar.vertical');"
            + "const s=scr.getBoundingClientRect();const p=pane.getBoundingClientRect();"
            + "const cell=meas&&meas.textContent.length?meas.getBoundingClientRect().width/meas.textContent.length:0;"
            + "return {innerWidth:innerWidth,dpr:devicePixelRatio,fontSize:parseFloat(rcs.fontSize),fontFamily:rcs.fontFamily,cell:cell,"
            + "paneLeft:p.left,paneRight:p.right,paneWidth:p.width,screenLeft:s.left,screenRight:s.right,screenWidth:s.width,"
            + "padLeft:parseFloat(xcs.paddingLeft),padRight:parseFloat(xcs.paddingRight),"
            + "scrollbar:sb?sb.getBoundingClientRect().width:null,domCols:cell>0?Math.round(s.width/cell):0};})())";

    private String label;
    private final List<String> violations = new ArrayList<>();
    private final JSONObject evidence = new JSONObject();

    private static final String HIT_JS = "JSON.stringify((() => {"
            + "const sels={'icon-btn':'.icon-btn','btn-ghost':'.btn-ghost','host-row':'.host-row','dir-header':'.dir-header',"
            + "'session-leaf':'.session-leaf','folder-header':'.folder-header','vault-btn':'.vault-btn',"
            + "'vault-row-btn':'[data-testid=vault-key-list] .row .row-actions button','diag-action':'.actions .btn-ghost',"
            + "'rip-icon-btn':'.rip .icon-btn'};"
            + "const out={coarse:matchMedia('(pointer: coarse)').matches,fine:matchMedia('(pointer: fine)').matches,"
            + "innerWidth:innerWidth,innerHeight:innerHeight,dpr:devicePixelRatio,counts:{},small:[]};"
            + "for(const [k,s] of Object.entries(sels)){let n=0;"
            + "for(const e of document.querySelectorAll(s)){const b=e.getBoundingClientRect();"
            + "const cs=getComputedStyle(e);"
            + "if(b.width<=0||b.height<=0||cs.visibility==='hidden'||cs.display==='none'||e.closest('[hidden],[inert]'))continue;"
            + "n++;if(b.width<" + MIN_HIT + "-" + EPS + "||b.height<" + MIN_HIT + "-" + EPS + ")"
            + "out.small.push({kind:k,cls:(e.className&&e.className.baseVal!==undefined?e.className.baseVal:String(e.className)).slice(0,80),"
            + "label:(e.getAttribute('aria-label')||e.getAttribute('title')||(e.textContent||'').trim()).slice(0,40),"
            + "w:Math.round(b.width*10)/10,h:Math.round(b.height*10)/10});}"
            + "out.counts[k]=n;}"
            + "return out;})())";

    private static final String LAYOUT_JS = "JSON.stringify((() => {"
            // Resolve the custom properties through a probe's computed padding: the variables may hold env(...)
            // or calc(...), which parseFloat on the raw property text cannot read.
            + "const probe=(decl)=>{const e=document.createElement('div');"
            + "e.style.cssText='position:fixed;left:0;top:0;width:0;height:0;visibility:hidden;pointer-events:none;box-sizing:content-box;'+decl;"
            + "document.body.appendChild(e);const cs=getComputedStyle(e);"
            + "const r={top:parseFloat(cs.paddingTop),bottom:parseFloat(cs.paddingBottom)};e.remove();return r;};"
            + "const root=getComputedStyle(document.documentElement);"
            + "const vars=probe('padding-top:var(--ps-inset-top);padding-bottom:var(--ps-inset-bottom)');"
            + "const env=probe('padding-top:env(safe-area-inset-top);padding-bottom:env(safe-area-inset-bottom)');"
            + "const kb=probe('padding-bottom:var(--ps-inset-keyboard)');"
            + "const ws=document.querySelector('.workspace');const b=ws?ws.getBoundingClientRect():null;"
            + "const vis=(e)=>{const r=e.getBoundingClientRect();const cs=getComputedStyle(e);"
            + "return r.width>=8&&r.height>=8&&cs.visibility!=='hidden'&&cs.display!=='none'&&!e.closest('[hidden],[inert],.xterm-helper-textarea');};"
            + "let last=null;"
            + "if(ws){for(const e of ws.querySelectorAll('button,textarea,input,select,[role=button],[contenteditable=true]')){"
            + "if(e.classList.contains('xterm-helper-textarea')||!vis(e))continue;const r=e.getBoundingClientRect();"
            + "let clipped=false;let eb=r.bottom;for(let p=e.parentElement;p&&p!==ws;p=p.parentElement){const o=getComputedStyle(p).overflowY;"
            + "if(o==='visible')continue;const pr=p.getBoundingClientRect();if(r.bottom<=pr.top||r.top>=pr.bottom){clipped=true;break;}eb=Math.min(eb,pr.bottom);}"
            + "if(clipped)continue;"
            + "if(!last||eb>last.bottom){const hit=document.elementFromPoint(r.left+r.width/2,Math.min(r.top+r.height/2,innerHeight-1));"
            + "last={bottom:eb,top:r.top,desc:(e.tagName+'.'+String(e.className&&e.className.baseVal!==undefined?e.className.baseVal:e.className)).slice(0,80),"
            + "label:(e.getAttribute('aria-label')||e.getAttribute('title')||e.getAttribute('data-testid')||'').slice(0,40),"
            + "covered:!(hit&&(hit===e||e.contains(hit)||hit.contains(e))),"
            + "coveredBy:hit?(hit.tagName+'.'+String(hit.className&&hit.className.baseVal!==undefined?hit.className.baseVal:hit.className)).slice(0,60):null};}}}"
            + "const vv=window.visualViewport;"
            + "return {innerHeight:innerHeight,innerWidth:innerWidth,dpr:devicePixelRatio,"
            + "vvHeight:vv?vv.height:null,vvOffsetTop:vv?vv.offsetTop:null,"
            + "insetBottomRaw:root.getPropertyValue('--ps-inset-bottom').trim(),insetTopRaw:root.getPropertyValue('--ps-inset-top').trim(),"
            + "safeAreaBottomRaw:root.getPropertyValue('--safe-area-inset-bottom').trim(),safeAreaTopRaw:root.getPropertyValue('--safe-area-inset-top').trim(),"
            + "insetBottom:vars.bottom,insetTop:vars.top,envBottom:env.bottom,envTop:env.top,insetKeyboard:kb.bottom,"
            + "workspace:b?{top:b.top,bottom:b.bottom,height:b.height,width:b.width}:null,lastControl:last};})())";

    private JSONObject nativeInsets() throws Exception {
        final JSONObject out = new JSONObject();
        final CountDownLatch latch = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            try {
                float d = activity.getResources().getDisplayMetrics().density;
                out.put("density", d);
                out.put("widthDp", Math.round(activity.getResources().getDisplayMetrics().widthPixels / d));
                out.put("heightDp", Math.round(activity.getResources().getDisplayMetrics().heightPixels / d));
                android.webkit.WebView webView = activity.getBridge().getWebView();
                int[] loc = new int[2];
                webView.getLocationOnScreen(loc);
                out.put("webViewTopPx", loc[1]);
                out.put("webViewBottomPx", loc[1] + webView.getHeight());
                // OS-level truth: the activity decor view's root insets, not what any child consumed.
                // Both the visible and ignoring-visibility reads are recorded so a hidden or
                // transient bar is distinguishable from a bar the OS never reported.
                WindowInsets decorInsets = activity.getWindow().getDecorView().getRootWindowInsets();
                if (decorInsets != null) {
                    int types = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout();
                    out.put("decorNavBottomPx", decorInsets.getInsets(WindowInsets.Type.navigationBars()).bottom);
                    out.put("decorNavBottomIgnoringVisibilityPx",
                            decorInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom);
                    out.put("decorNavVisible", decorInsets.isVisible(WindowInsets.Type.navigationBars()));
                    out.put("decorStatusTopPx", decorInsets.getInsets(WindowInsets.Type.statusBars()).top);
                    out.put("decorStatusTopIgnoringVisibilityPx",
                            decorInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top);
                    out.put("decorSystemBarsBottomPx", decorInsets.getInsets(types).bottom);
                    out.put("decorTappableBottomPx", decorInsets.getInsets(WindowInsets.Type.tappableElement()).bottom);
                    out.put("decorStableBottomPx", decorInsets.getStableInsetBottom());
                }
                out.put("webViewPackage", String.valueOf(android.webkit.WebView.getCurrentWebViewPackage() == null
                        ? null : android.webkit.WebView.getCurrentWebViewPackage().versionName));
                WindowInsets insets = webView.getRootWindowInsets();
                if (insets != null) {
                    out.put("imeVisible", insets.isVisible(WindowInsets.Type.ime()));
                    out.put("imeBottomPx", insets.getInsets(WindowInsets.Type.ime()).bottom);
                    out.put("navBottomPx", insets.getInsets(WindowInsets.Type.navigationBars()).bottom);
                    out.put("statusTopPx", insets.getInsets(WindowInsets.Type.statusBars()).top);
                }
            } catch (Exception error) {
                try { out.put("error", String.valueOf(error)); } catch (Exception ignored) { }
            } finally {
                latch.countDown();
            }
        });
        assertTrue("native inset read timed out", latch.await(10, TimeUnit.SECONDS));
        return out;
    }

    private void fail(String message) {
        violations.add(message);
        Log.e(TAG, "S1_VIOLATION " + label + " " + message);
    }

    /** Hit-target measurement on whatever screen is showing; {@code requireRows} names a kind that must be present. */
    private void measureHits(String screen, String... requiredKinds) throws Exception {
        JSONObject hits = new JSONObject(evalString(HIT_JS));
        evidence.put(screen + ".hits", hits);
        if (!hits.getBoolean("coarse")) fail(screen + ": (pointer: coarse) does not match; hit-target rule is not under test");
        JSONObject counts = hits.getJSONObject("counts");
        int total = 0;
        for (java.util.Iterator<String> it = counts.keys(); it.hasNext(); ) total += counts.getInt(it.next());
        if (total == 0) fail(screen + ": measured zero hit-target elements (vacuous)");
        for (String requiredKind : requiredKinds) {
            if (requiredKind != null && counts.getInt(requiredKind) == 0) fail(screen + ": no visible " + requiredKind + " measured");
        }
        JSONArray small = hits.getJSONArray("small");
        for (int i = 0; i < small.length(); i++) {
            JSONObject s = small.getJSONObject(i);
            fail(screen + ": " + s.getString("kind") + " '" + s.getString("label") + "' (." + s.getString("cls")
                    + ") measures " + s.getDouble("w") + "x" + s.getDouble("h") + " CSS px, needs >= 48x48");
        }
    }

    private double baselineInnerHeight = -1;

    private static boolean finite(JSONObject o, String k) {
        return !o.isNull(k) && !Double.isNaN(o.optDouble(k, Double.NaN));
    }

    /** Workspace bottom containment with the native bar insets in effect. */
    private void measureWorkspace(String screen, boolean imeUp) throws Exception {
        JSONObject web = new JSONObject(evalString(LAYOUT_JS));
        JSONObject nat = nativeInsets();
        JSONObject entry = new JSONObject().put("web", web).put("native", nat);
        evidence.put(screen + ".layout", entry);
        double d = nat.optDouble("density", 0);
        if (web.isNull("workspace")) {
            fail(screen + ": no .workspace element in the DOM");
            return;
        }
        // Three distinct verdicts: (a) the OS reports no bar, (b) the OS reports a bar but the
        // probe's view did not see it, (c) the OS reports it but the app's CSS inset is 0.
        int osNav = Math.max(Math.max(nat.optInt("navBottomPx", 0), nat.optInt("decorNavBottomPx", 0)),
                nat.optInt("decorNavBottomIgnoringVisibilityPx", 0));
        int osStatus = Math.max(Math.max(nat.optInt("statusTopPx", 0), nat.optInt("decorStatusTopPx", 0)),
                nat.optInt("decorStatusTopIgnoringVisibilityPx", 0));
        nat.put("osNavBottomPx", osNav);
        nat.put("osStatusTopPx", osStatus);
        // The bottom (navigation-bar) inset is only asserted where the device has one. An AVD without a nav bar
        // reports 0 from every OS read; that is recorded explicitly (navInsetMeasured=false plus the raw OS values)
        // and the checker accepts only that marker with all-zero OS reads. Every other assertion stays strict.
        boolean navMeasured = osNav > 0;
        entry.put("navInsetMeasured", navMeasured);
        entry.put("navOsReads", new JSONObject().put("webViewPx", nat.optInt("navBottomPx", 0))
                .put("decorPx", nat.optInt("decorNavBottomPx", 0))
                .put("decorIgnoringVisibilityPx", nat.optInt("decorNavBottomIgnoringVisibilityPx", 0)));
        if (navMeasured && nat.optInt("navBottomPx", 0) <= 0) fail(screen + ": probe note: webView root insets report nav bottom 0 but decor reports "
                + osNav + "px (visible=" + nat.optBoolean("decorNavVisible", false) + ")");
        if (osStatus <= 0) fail(screen + ": OS INSET 0: no status-bar top inset from any OS read");
        double navCss = d > 0 ? osNav / d : 0;
        double statusCss = d > 0 ? osStatus / d : 0;
        double cssBottom = finite(web, "insetBottom") ? web.getDouble("insetBottom") : -1;
        if (!imeUp) {
            // Resolved through a probe element (computed padding), so env()/calc() values are read as px.
            if (navMeasured) {
                if (cssBottom <= 0) fail(screen + ": CSS INSET 0 (OS inset " + osNav + "px > 0, so the app host does not carry it to the web layer): resolved --ps-inset-bottom is " + cssBottom + "px (raw '" + web.optString("insetBottomRaw")
                        + "', env " + web.optDouble("envBottom") + ", capacitor --safe-area-inset-bottom '" + web.optString("safeAreaBottomRaw")
                        + "', WebView " + nat.optString("webViewPackage") + ") with OS nav bottom " + osNav + "px");
                else if (Math.abs(cssBottom - navCss) > 2.0) fail(screen + ": resolved --ps-inset-bottom " + cssBottom + "px != native nav bottom " + navCss + " CSS px");
            } else if (cssBottom != 0) {
                fail(screen + ": no OS nav inset but resolved --ps-inset-bottom is " + cssBottom + "px");
            }
            double top = finite(web, "insetTop") ? web.getDouble("insetTop") : -1;
            if (top <= 0) fail(screen + ": CSS INSET 0 (OS inset " + osStatus + "px > 0): resolved --ps-inset-top is " + top + "px (raw '" + web.optString("insetTopRaw") + "', env " + web.optDouble("envTop")
                    + ", capacitor --safe-area-inset-top '" + web.optString("safeAreaTopRaw") + "') with OS status top " + osStatus + "px");
            else if (Math.abs(top - statusCss) > 2.0) fail(screen + ": resolved --ps-inset-top " + top + "px != native status top " + statusCss + " CSS px");
        }
        double bottom = web.getJSONObject("workspace").getDouble("bottom");
        double inner = web.getDouble("innerHeight");
        double limit;
        if (!imeUp) {
            baselineInnerHeight = inner;
            limit = inner - Math.max(cssBottom, 0);
        } else {
            double imeCss = d > 0 ? nat.optInt("imeBottomPx", 0) / d : 0;
            double vvBottom = finite(web, "vvHeight") ? web.getDouble("vvOffsetTop") + web.getDouble("vvHeight") : inner;
            double viewportBottom = Math.min(inner, vvBottom);
            String behaviour;
            if (!nat.optBoolean("imeVisible", false) || imeCss <= 0) {
                fail(screen + ": IME not visible natively (imeVisible=" + nat.optBoolean("imeVisible", false) + ", imeBottomPx=" + nat.optInt("imeBottomPx", 0) + ")");
                behaviour = "ime-not-visible";
                limit = viewportBottom;
            } else if (baselineInnerHeight > 0 && inner < baselineInnerHeight - imeCss * 0.6) {
                behaviour = "layout-viewport-resized";
                limit = viewportBottom;
            } else if (viewportBottom < (baselineInnerHeight > 0 ? baselineInnerHeight : inner) - imeCss * 0.6) {
                behaviour = "visual-viewport-resized";
                limit = viewportBottom;
            } else {
                // The WebView did not resize at all: the IME covers the lower imeCss px, so that is the bound.
                behaviour = "no-resize-native-ime-bound";
                limit = Math.min(viewportBottom, inner - imeCss);
            }
            entry.put("imeBehaviour", behaviour).put("imeCssPx", imeCss).put("effectiveLimit", limit);
        }
        if (bottom > limit + EPS) {
            fail(screen + ": .workspace bottom " + bottom + " > limit " + limit + " (innerHeight " + inner
                    + (imeUp ? ", IME up" : " - --ps-inset-bottom " + cssBottom) + ")");
        }
        double wsHeight = web.getJSONObject("workspace").getDouble("height");
        if (wsHeight <= 0) fail(screen + ": .workspace has zero height");
        // The last control in the workspace (composer / key bar / button) must sit inside the bound and not be covered.
        JSONObject last = web.isNull("lastControl") ? null : web.getJSONObject("lastControl");
        if (last == null) {
            fail(screen + ": no visible control found inside .workspace (vacuous)");
        } else {
            if (last.getDouble("bottom") > limit + EPS) {
                fail(screen + ": last control " + last.getString("desc") + " '" + last.optString("label") + "' bottom " + last.getDouble("bottom")
                        + " > limit " + limit);
            }
            if (last.getBoolean("covered")) {
                fail(screen + ": last control " + last.getString("desc") + " '" + last.optString("label") + "' is covered by " + last.optString("coveredBy"));
            }
        }
    }

    private static final String SETTINGS_BUTTON = ".picker .header-actions button[title=Settings]";
    private static final String SETTINGS_DIALOG = "[role=dialog][aria-label=Settings]";

    /**
     * Settings (reachable from hosts without a connection): measure then come back to the hosts list.
     * On the real hosts screen (the shared HostPickerView) the gear is {@code button.icon-btn[title=Settings]}
     * with no aria-label, and it opens a Settings OverlayPanel dialog, not an app route.
     */
    private void measureSettings() throws Exception {
        awaitJsTrue("document.querySelector('" + SETTINGS_BUTTON + "') !== null");
        JsVisibility.expectClicked(evalString(JsVisibility.click("document.querySelector('" + SETTINGS_BUTTON + "')")));
        awaitJsTrue("document.querySelector('" + SETTINGS_DIALOG + "') !== null"
                + " && document.querySelector('" + SETTINGS_DIALOG + " [role=tablist][aria-label=\"Settings sections\"]') !== null");
        SystemClock.sleep(700);
        measureHits("5-settings");
        evidence.put("diagnosticsActionsExercised", new JSONObject(evalString(HIT_JS)).getJSONObject("counts").getInt("diag-action") > 0);
        shot("5-settings");
        // Vault list needs an unlocked vault and RemoteImagePicker needs an open picker: neither is reachable here,
        // so their kinds are recorded but only required where counted (see evidence 'unreachable').
        evidence.put("unreachable", new JSONArray().put("vault-key-list (needs unlocked vault)").put("rip (needs image picker)"));
        // Close with the dialog's own control, not the Android BACK key: OverlayPanel renders a Close button
        // on wide screens and a Back arrow on compact ones (S4a full-screen page).
        String close = SETTINGS_DIALOG + " button[title=Close], " + SETTINGS_DIALOG + " button[title=Back]";
        awaitJsTrue("document.querySelector('" + close + "') !== null");
        JsVisibility.expectClicked(evalString(JsVisibility.click("document.querySelector('" + close + "')")));
        awaitJsTrue("document.querySelector('" + SETTINGS_DIALOG + "') === null && !!document.querySelector('.host-list, .empty')");
        assertTrue("host list not reachable after closing the settings dialog with its Close control",
                new JSONObject(evalString("JSON.stringify({ok:!!document.querySelector('.host-list, .empty')})")).getBoolean("ok"));
        SystemClock.sleep(500);
    }

    /** On any failed wait, record the header markup and visible text (bounded) so a red run explains itself. */
    @Override
    protected void awaitJsTrue(String expression, long timeoutMillis) throws Exception {
        try {
            super.awaitJsTrue(expression, timeoutMillis);
        } catch (AssertionError failure) {
            try {
                String dom = evalString("JSON.stringify({"
                        + "headerHtml:Array.from(document.querySelectorAll('header')).map((h)=>h.outerHTML).join(' ').slice(0,2048),"
                        + "bodyText:(document.body.innerText||'').slice(0,4096),"
                        + "route:document.querySelector('.app-shell')?.dataset.route ?? null})");
                evidence.put("domAtFailure", new JSONObject(dom).put("waitExpression", expression));
            } catch (Exception diagnosticError) {
                try { evidence.put("domAtFailure", "unavailable: " + diagnosticError); } catch (Exception ignored) { }
            }
            throw failure;
        }
    }

    private void step(String name) throws Exception {
        evidence.put("lastStepReached", name);
        Log.i(TAG, "S1_STEP " + label + " " + name);
    }

    private void shot(String screen) {
        SystemClock.sleep(700);
        screenshot("s1-" + label + "-" + screen);
    }

    /**
     * The maintainer's state (#3157): settings stored by a pre-#3157 build. Written through the
     * durable localStorage (synchronous native write), then the activity is relaunched so the
     * shared settings store boots from it, exactly like an upgraded install.
     */
    private void seedPre3157SettingsAndRelaunch() throws Exception {
        awaitJsTrue("!!document.querySelector('.host-list, .empty')");
        evalString("(() => {localStorage.setItem('" + SHARED_SETTINGS_KEY + "', " + JSONObject.quote(PRE_3157_SETTINGS)
                + "); sessionStorage.clear(); return 'seeded';})()");
        scenario.close();
        scenario = ActivityScenario.launch(SharedShellLaunch.intent());
        awaitJsTrue("!!document.querySelector('.host-list, .empty')");
        evidence.put("seededSettings", evalString("localStorage.getItem('" + SHARED_SETTINGS_KEY + "') ?? 'absent'"));
        if (!evidence.getString("seededSettings").contains("\"terminalFontSize\":16")) {
            fail("the pre-#3157 settings blob did not survive the relaunch: " + evidence.getString("seededSettings"));
        }
    }

    private static String readAsset(String name) throws Exception {
        try (java.io.InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name)) {
            return new String(readAll(input), StandardCharsets.UTF_8);
        }
    }

    private void hideKeyboard() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(activity.getBridge().getWebView().getWindowToken(), 0);
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        SystemClock.sleep(1_500);
    }

    /**
     * #3157: columns (host PTY oracle vs the laid-out grid), gutters, the no-rail projection,
     * then a Claude Code screen from the host and a no-tofu check of its symbols.
     */
    private void measureTerminal(String folder, JSONObject transport) throws Exception {
        String screen = "3b-terminal";
        // The host's own PTY width, printed so that the echoed command never matches the pattern.
        typeLine("stty size | awk '{print \"PS\" \"COLS=\" $2 \"x\" $1}'");
        awaitJsTrue("/PSCOLS=\\d+x\\d+/.test(" + VISIBLE_TERMINAL + ")");
        Matcher size = HOST_SIZE.matcher(evalString(VISIBLE_TERMINAL));
        assertTrue("no PSCOLS line", size.find());
        int hostCols = Integer.parseInt(size.group(1));
        hideKeyboard();
        JSONObject geo = new JSONObject(evalString(TERMINAL_GEOMETRY_JS));
        double cell = geo.getDouble("cell");
        double padLeft = geo.getDouble("padLeft");
        double padRight = geo.getDouble("padRight");
        double scrollbar = geo.isNull("scrollbar") ? -1 : geo.getDouble("scrollbar");
        double inner = geo.getDouble("innerWidth");
        double rail = geo.getDouble("paneLeft") + (inner - geo.getDouble("paneRight"));
        int withRail = cell > 0 ? (int) Math.floor((geo.getDouble("paneWidth") - padLeft - padRight - scrollbar) / cell) : 0;
        int withoutRail = cell > 0 ? (int) Math.floor((inner - padLeft - padRight - scrollbar) / cell) : 0;
        JSONObject entry = new JSONObject().put("geometry", geo).put("hostCols", hostCols).put("hostRows", Integer.parseInt(size.group(2)))
                .put("railWidth", rail).put("columnsWithRail", withRail).put("columnsWithoutRail", withoutRail)
                .put("leftGutter", padLeft).put("rightGutter", padRight + scrollbar);
        evidence.put(screen + ".columns", entry);
        Log.i(TAG, "S1_COLUMNS " + label + " " + entry);
        if (cell <= 0) fail(screen + ": could not measure the terminal cell");
        if (scrollbar < 0) fail(screen + ": no xterm vertical scrollbar element measured");
        if (hostCols != geo.getInt("domCols")) fail(screen + ": host PTY has " + hostCols + " columns but the WebView laid out " + geo.getInt("domCols"));
        if (hostCols != withRail) fail(screen + ": host PTY has " + hostCols + " columns but the pane fits " + withRail + " (rail " + rail + "px)");
        if (padLeft > MAX_GUTTER + EPS) fail(screen + ": left gutter " + padLeft + "px > " + MAX_GUTTER + "px");
        if (padRight + scrollbar > MAX_GUTTER + EPS) fail(screen + ": right gutter " + (padRight + scrollbar) + "px (padding " + padRight
                + " + scrollbar reservation " + scrollbar + ") > " + MAX_GUTTER + "px");
        if (geo.getDouble("fontSize") < 11.0) fail(screen + ": terminal text " + geo.getDouble("fontSize") + "px is below the 11px readability floor");
        if (label.startsWith("412")) {
            if (withoutRail < MIN_COLUMNS || withoutRail > MAX_COLUMNS) fail(screen + ": " + withoutRail + " columns without the rail at "
                    + inner + "px (cell " + cell + "px, font " + geo.getDouble("fontSize") + "px), want " + MIN_COLUMNS + "-" + MAX_COLUMNS
                    + "; with the rail: " + withRail);
            if (rail < 1 && (hostCols < MIN_COLUMNS || hostCols > MAX_COLUMNS)) fail(screen + ": " + hostCols + " host columns, want " + MIN_COLUMNS + "-" + MAX_COLUMNS);
        }

        // A Claude Code screen, written on the host and drawn by the real terminal.
        String screenFile = "$HOME/" + folder + "/claude-code-screen.ansi";
        String encoded = Base64.encodeToString(readAsset("claude-code-screen.ansi").getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        JSONObject written = awaitNativeExec("claude-" + label, transport.getString("transportId"), transport.getString("generationId"),
                "set -eu\nprintf %s " + shellQuote(encoded) + " | base64 -d > \"" + screenFile + "\"");
        assertEquals(0, written.getInt("exitCode"));
        typeLine("clear; cat ~/" + folder + "/claude-code-screen.ansi");
        awaitJsTrue(VISIBLE_TERMINAL + ".includes('bypass permissions on')");
        hideKeyboard();
        shot("3b-claude");

        // No tofu: each symbol, in the terminal's own computed font stack, against the .notdef of an
        // unassigned code point (U+0378). Faces are loaded first; a canvas does not trigger loading.
        evalRaw("(() => {window.__ps3157Fonts=null;const rows=" + VISIBLE_XTERM + ".querySelector('.xterm-rows');"
                + "const f=getComputedStyle(rows).fontFamily;"
                + "document.fonts.load('48px '+f," + JSONObject.quote(SYMBOL_PROBE) + ").then(()=>document.fonts.ready)"
                + ".then(()=>{window.__ps3157Fonts='ok';},(e)=>{window.__ps3157Fonts='error: '+e;});return true;})()");
        awaitJsTrue("window.__ps3157Fonts !== null");
        JSONObject glyphs = new JSONObject(evalString("JSON.stringify((() => {"
                + "const rows=" + VISIBLE_XTERM + ".querySelector('.xterm-rows');const f=getComputedStyle(rows).fontFamily;"
                + "const draw=(ch)=>{const c=document.createElement('canvas');c.width=96;c.height=96;const x=c.getContext('2d');"
                + "x.font='48px '+f;x.fillStyle='#000';x.fillText(ch,16,64);const d=x.getImageData(0,0,96,96).data;"
                + "let ink=0,h=0;for(let i=3;i<d.length;i+=4){if(d[i]){ink++;h=(Math.imul(h,31)+i*7+d[i])|0;}}return {ink:ink,h:h};};"
                + "const notdef=draw('\\u0378');const tofu=[];let probed=0;"
                + "for(const ch of " + JSONObject.quote(SYMBOL_PROBE) + "){probed++;const r=draw(ch);"
                + "if(r.ink===0||(r.ink===notdef.ink&&r.h===notdef.h))tofu.push(ch+' U+'+ch.codePointAt(0).toString(16).toUpperCase());}"
                + "return {fontFamily:f,fontsLoad:window.__ps3157Fonts,notdefInk:notdef.ink,probed:probed,tofu:tofu,"
                + "screenText:" + VISIBLE_TERMINAL + ".slice(0,3000)};})())"));
        evidence.put(screen + ".glyphs", glyphs);
        Log.i(TAG, "S1_GLYPHS " + label + " " + glyphs.getJSONArray("tofu"));
        if (glyphs.getInt("probed") != SYMBOL_PROBE.codePointCount(0, SYMBOL_PROBE.length())) fail(screen + ": glyph probe ran on "
                + glyphs.getInt("probed") + " characters");
        if (glyphs.getJSONArray("tofu").length() > 0) fail(screen + ": symbols drawn as .notdef (tofu): " + glyphs.getJSONArray("tofu"));
        if (!glyphs.getString("screenText").contains("bypass permissions on")) fail(screen + ": the Claude Code screen is not on the terminal");
    }

    private static final String VISIBLE_SETTINGS_BUTTON =
            "[...document.querySelectorAll('button[title=Settings]')].find((b)=>b.offsetParent!==null)";
    private static final String SIZE_INPUT = SETTINGS_DIALOG + " input#terminal-size";

    /**
     * Opens Settings, selects Appearance, and waits for the terminal size control. From the host list the
     * picker's gear is the door; inside a workspace on a phone the gear is gone (S2, #3121) and Settings is
     * the overflow (⋮) menu's row, the same way a user reaches it.
     */
    private void openAppearanceSettings() throws Exception {
        if ("true".equals(evalString("String(!!" + VISIBLE_SETTINGS_BUTTON + ")"))) {
            JsVisibility.expectClicked(evalString(JsVisibility.click(VISIBLE_SETTINGS_BUTTON)));
        } else {
            openHostTool("settings");
        }
        String appearance = SETTINGS_DIALOG + " #settings-tab-appearance";
        awaitJsTrue("!!document.querySelector('" + appearance + "')");
        JsVisibility.expectClicked(evalString(JsVisibility.click("document.querySelector('" + appearance + "')")));
        awaitJsTrue("(() => {const i=document.querySelector('" + SIZE_INPUT + "');return !!i && i.offsetParent!==null;})()");
    }

    /** OverlayPanel renders Close on wide screens and a Back arrow on compact ones (core S4a), as in measureSettings. */
    private void closeSettings() throws Exception {
        String close = SETTINGS_DIALOG + " button[title=Close], " + SETTINGS_DIALOG + " button[title=Back]";
        awaitJsTrue("document.querySelector('" + close + "') !== null");
        JsVisibility.expectClicked(evalString(JsVisibility.click("document.querySelector('" + close + "')")));
        awaitJsTrue("document.querySelector('" + SETTINGS_DIALOG + "') === null");
    }

    private JSONObject storedSettings() throws Exception {
        return new JSONObject(evalString("localStorage.getItem('" + SHARED_SETTINGS_KEY + "') ?? '{}'"));
    }

    /**
     * #3157 AC "the size setting still works and persists", on the packaged app: the phone's
     * Settings shows the 12px phone size (not the seeded desktop 16) in half-pixel steps; setting
     * 11.5 re-sizes the live terminal and is written to storage; after an activity restart the
     * stored blob and the Settings control still say 11.5, and the desktop size is untouched.
     */
    private void persistPhoneSize() throws Exception {
        String screen = "6-phone-size";
        JSONObject entry = new JSONObject();
        evidence.put(screen + ".persist", entry);
        hideKeyboard();
        openAppearanceSettings();
        entry.put("shownBefore", evalString("document.querySelector('" + SIZE_INPUT + "').value"));
        entry.put("step", evalString("document.querySelector('" + SIZE_INPUT + "').getAttribute('step') ?? ''"));
        if (!"12".equals(entry.getString("shownBefore"))) fail(screen + ": phone Settings shows " + entry.getString("shownBefore") + "px, want the 12px phone default");
        if (!"0.5".equals(entry.getString("step"))) fail(screen + ": phone size step is " + entry.getString("step") + ", want 0.5");
        evalString("(() => {const i=document.querySelector('" + SIZE_INPUT + "');i.value='11.5';"
                + "i.dispatchEvent(new Event('change',{bubbles:true}));return 'set';})()");
        awaitJsTrue("JSON.parse(localStorage.getItem('" + SHARED_SETTINGS_KEY + "') ?? '{}').compactTerminalFontSize === 11.5");
        JSONObject afterChange = storedSettings();
        entry.put("storedAfterChange", afterChange.optDouble("compactTerminalFontSize", -1));
        entry.put("desktopSizeAfterChange", afterChange.optDouble("terminalFontSize", -1));
        closeSettings();
        awaitJsTrue("getComputedStyle(" + VISIBLE_XTERM + ".querySelector('.xterm-rows')).fontSize === '11.5px'");
        entry.put("liveFontAfterChange", evalString("getComputedStyle(" + VISIBLE_XTERM + ".querySelector('.xterm-rows')).fontSize"));
        shot("6-phone-size-11_5");

        // Restart: a new activity and WebView, booting from durable storage alone.
        scenario.close();
        scenario = ActivityScenario.launch(SharedShellLaunch.intent());
        awaitJsTrue("!!document.querySelector('.host-list, .empty')");
        JSONObject afterRestart = storedSettings();
        entry.put("storedAfterRestart", afterRestart.optDouble("compactTerminalFontSize", -1));
        entry.put("desktopSizeAfterRestart", afterRestart.optDouble("terminalFontSize", -1));
        openAppearanceSettings();
        entry.put("shownAfterRestart", evalString("document.querySelector('" + SIZE_INPUT + "').value"));
        closeSettings();
        Log.i(TAG, "S1_PHONE_SIZE " + label + " " + entry);
        if (entry.getDouble("storedAfterChange") != 11.5) fail(screen + ": Settings did not store compactTerminalFontSize 11.5: " + afterChange);
        if (!"11.5px".equals(entry.getString("liveFontAfterChange"))) fail(screen + ": live terminal font " + entry.getString("liveFontAfterChange"));
        if (entry.getDouble("storedAfterRestart") != 11.5) fail(screen + ": after restart the stored phone size is " + afterRestart);
        if (!"11.5".equals(entry.getString("shownAfterRestart"))) fail(screen + ": after restart Settings shows " + entry.getString("shownAfterRestart"));
        if (entry.getDouble("desktopSizeAfterRestart") != 16) fail(screen + ": the desktop size changed: " + afterRestart);
    }

    private void saveEvidence() throws Exception {
        String json = evidence.toString();
        // UiAutomation.executeShellCommand does not interpret quotes or pipes (it tokenizes on spaces),
        // so a shell-written evidence file was silently never created. The runner reassembles the JSON
        // from these base64 chunks (logcat truncates a single line at ~4000 chars).
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        final int chunk = 2400;
        int count = (bytes.length + chunk - 1) / chunk;
        for (int i = 0; i < count; i++) {
            String b64 = Base64.encodeToString(bytes, i * chunk, Math.min(chunk, bytes.length - i * chunk), Base64.NO_WRAP);
            Log.i(TAG, "S1_LAYOUT_CHUNK " + label + " " + i + "/" + count + " " + b64);
        }
    }

    @Test
    public void hitTargetsAndWorkspaceBottomHoldAtPhoneWidth() throws Exception {
        label = InstrumentationRegistry.getArguments().getString("layoutLabel", "");
        assertTrue("pass layoutLabel (412x915 or 360x800)", label.matches("[0-9]+x[0-9]+"));
        int expectedWidth = Integer.parseInt(label.substring(0, label.indexOf('x')));
        try {
            runJourney(expectedWidth);
        } catch (Throwable t) {
            String failureText = String.valueOf(t);
            evidence.put("failure", failureText.length() > 4096 ? failureText.substring(0, 4096) + "...[truncated]" : failureText);
            throw t;
        } finally {
            // Evidence is saved even when the journey fails or times out midway, so a red run is diagnosable.
            // The checker still fails closed on partial evidence (missing screens/kinds).
            try {
                evidence.put("violationCount", violations.size());
                saveEvidence();
            } catch (Exception saveError) {
                Log.e(TAG, "S1_EVIDENCE_SAVE_FAILED " + label, saveError);
            }
        }
        assertTrue("S1 layout violations at " + label + ":\n - " + String.join("\n - ", violations), violations.isEmpty());
    }

    private void runJourney(int expectedWidth) throws Exception {
        step("start");
        String run = sessionRun();
        step("seed-pre-3157-settings");
        seedPre3157SettingsAndRelaunch();
        ensureFixtureHost(run);

        JSONObject nat0 = nativeInsets();
        evidence.put("label", label);
        evidence.put("native0", nat0);
        // The emulator really is at the requested class (wm density), not the AVD default.
        assertTrue("device width " + nat0.optInt("widthDp") + "dp is not the requested " + expectedWidth + "dp class",
                Math.abs(nat0.optInt("widthDp") - expectedWidth) <= 2);

        step("hosts");
        measureHits("1-hosts", "host-row");
        shot("1-hosts");
        step("settings");
        measureSettings();

        step("connecting");
        tapHost("fixture-" + run);
        trustFirstContactIfAsked(hostKeys("agentsHostKeys"));
        awaitJsTrue("!!document.querySelector('.workspace')");

        step("workspace-connected");
        String tag = "s1-" + Long.toString(System.currentTimeMillis(), 36);
        String folder = "ps-s1-" + tag;
        // Not a superstring of `folder`: openFolder/awaitFolder match rows by substring.
        String pairFolder = "ps-s1-pair-" + Long.toString(System.nanoTime(), 36);
        awaitJsTrue("[...(window.__pocketshellConnectionJournal ?? [])].some((e)=>e.transportId && e.generationId)");
        JSONObject transport = new JSONObject(evalString("JSON.stringify([...(window.__pocketshellConnectionJournal ?? [])]"
                + ".reverse().find((e)=>e.transportId && e.generationId) ?? null)"));
        JSONObject created = awaitNativeExec("create-" + tag, transport.getString("transportId"),
                transport.getString("generationId"), "set -eu\nmkdir -p \"$HOME/" + folder + "\"\n"
                + "PATH=\"$HOME/.local/bin:$PATH\" pocketshell sessions create --json --cwd \"$HOME/" + folder + "\" -- "
                + shellQuote("shell-" + tag) + " >/dev/null\n"
                // A two-session folder too: the compact tree collapses a single-session folder into one row
                // (core #3145), so session leaves exist only where a folder holds more than one session. Without
                // it the leaf count depended on what earlier lanes left on the fixture.
                + "mkdir -p \"$HOME/" + pairFolder + "\"\n"
                + "for s in pair-a pair-b; do PATH=\"$HOME/.local/bin:$PATH\" pocketshell sessions create --json --cwd \"$HOME/"
                + pairFolder + "\" -- \"$s-" + tag + "\" >/dev/null; done");
        assertEquals(0, created.getInt("exitCode"));
        awaitFolder(folder);
        awaitFolder(pairFolder);
        SystemClock.sleep(500);
        step("session-tree");
        measureHits("2-session-tree", "dir-header", "session-leaf");
        measureWorkspace("2-session-tree", false);
        shot("2-session-tree");

        step("opening-session");
        openFolder(folder);
        awaitJsTrue(VISIBLE_TERMINAL + ".includes('$')");
        SystemClock.sleep(1_500);
        JsVisibility.expectClicked(evalString(JsVisibility.clickIfPresent("document.querySelector('button[aria-label=\"Close the prompt panel\"]')")));
        SystemClock.sleep(500);
        step("workspace");
        measureHits("3-workspace");
        measureWorkspace("3-workspace", false);
        shot("3-workspace");

        step("terminal-columns-and-glyphs");
        measureTerminal(folder, transport);

        // Keyboard-up variant: the terminal takes focus and the IME shows.
        step("ime-up");
        focusTerminalForIme();
        SystemClock.sleep(1_500);
        measureWorkspace("4-workspace-ime-up", true);
        shot("4-workspace-ime-up");

        step("phone-size-persists");
        persistPhoneSize();
        step("done");
    }
}
