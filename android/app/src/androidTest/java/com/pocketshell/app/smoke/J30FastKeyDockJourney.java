package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import androidx.lifecycle.Lifecycle;
import androidx.test.platform.app.InstrumentationRegistry;
import com.pocketshell.app.ime.ScriptedIme;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * #3158 (S6 of #3118): a Claude-style approval prompt ("Do you want to
 * proceed? 1. Yes 2. No · Esc to cancel · Tab to amend") is answerable from a
 * phone with no hardware keyboard, on the shell the app actually boots.
 *
 * <p>The maintainer's phone showed no key row at all: the shared shell is the
 * default since #3063, and the fast-key dock existed only in the legacy shell
 * (J23 covers that shell). This journey drives the shared shell's
 * {@code terminal.dock} contribution on a real Docker host:
 * <ul>
 *   <li>a host fixture ({@code ps3158-approval.py}, staged by
 *       scripts/connected-js-hotkeys-docker.sh) prints the numbered menu, puts
 *       its PTY in raw mode and records every byte until Ctrl+D;</li>
 *   <li>with the soft keyboard CLOSED, then again OPEN, the dock's 2, Esc and
 *       Shift+Tab are tapped with real touchscreen events ({@link PhysicalTap})
 *       after a hit-test proves each key's centre is the key, then the dock's
 *       Ctrl page ends the reader with ^D. The host must record exactly
 *       {@code 32 1b 1b5b5a}; the runner re-reads the host's own copy over an
 *       independent SSH connection;</li>
 *   <li>with the keyboard closed, EVERY key on EVERY page is tapped once, in
 *       page order (^D last, as the terminator), and the host must record
 *       exactly {@link #EXPECTED_ALL_KEYS_HEX}: each page's bytes reach a real
 *       PTY through the multiplexer;</li>
 *   <li>at the device's width (411x914dp on the Pixel 7a AVD; 360dp in the
 *       review's narrow run) the dock is inside the viewport, above the
 *       keyboard when it is up and above the navigation bar / gesture handle
 *       when it is down, every key cell on every page is at least 48x48 CSS px
 *       (= dp) with a 44x44 keycap, the row cannot scroll sideways (review
 *       round 6: six fixed cells, the overflow on pages), and the terminal ends
 *       at the dock's top edge;</li>
 *   <li>the keyboard state does not change under the taps: closed stays
 *       closed (and no dock key keeps focus, so a hardware Enter cannot
 *       re-fire it), open stays open with the terminal still focused, and the
 *       host PTY is never resized by the dock;</li>
 *   <li>under gesture navigation with the Pixel launcher (the device's own
 *       state: J30 switches nothing), no dock tap takes the launcher out of
 *       its Normal state (no quick-switch / Home / recents) and the app stays
 *       the top resumed activity -- checked after every page against the
 *       launcher's own StateManager log, with a control drag in its bottom
 *       band first that must leave Normal, so the oracle is known to be live.
 *       The launcher merely starting to track a tap is logged, not failed:
 *       every bottom-bar tap on gesture navigation does that (review round 7).
 *       On three-button navigation, or with the launcher disabled (the lane),
 *       the check still runs but there is no live control.</li>
 *   <li>#3201: with the keyboard up on the focused terminal, the app is sent
 *       to the background and brought back {@link #RESUME_CYCLES} times; the
 *       keyboard must stay down after every return (#3126).</li>
 * </ul>
 */
public class J30FastKeyDockJourney extends SharedAppDockerJourneyTest {
    /** 2, Esc, Shift+Tab: the bytes a hardware keyboard sends for the same answer. */
    static final String EXPECTED_APPROVAL_HEX = "321b1b5b5a";
    /**
     * Every byte-sending key on every page, in page order, ^D excluded (it
     * ends the reader): keys (Esc Tab Up Down Enter), more (Shift+Tab Left
     * Right), digits 1-5, digits 6-0, then the Ctrl pages (C Z R L, A E U K W,
     * B Q T Y I, O P S F G, H J X V N, M backslash). Kept in step with
     * scripts/connected-js-hotkeys-docker.sh's host oracle.
     */
    static final String EXPECTED_ALL_KEYS_HEX = "1b" + "09" + "1b5b41" + "1b5b42" + "0d"
            + "1b5b5a" + "1b5b44" + "1b5b43"
            + "3132333435" + "3637383930"
            + "031a120c" + "0105150b17" + "0211141909" + "0f10130607" + "080a18160e" + "0d1c";
    /** The dock's pages in model order (fastKeysDockModel.ts), each with its keys in cell order. */
    static final Map<String, List<String>> PAGES = new LinkedHashMap<>();
    static {
        PAGES.put("keys", Arrays.asList("escape", "tab", "arrow-up", "arrow-down", "enter", "page-more"));
        PAGES.put("more", Arrays.asList("shift-tab", "arrow-left", "arrow-right", "page-ctrl", "page-digits", "page-keys"));
        PAGES.put("digits", Arrays.asList("digit-1", "digit-2", "digit-3", "digit-4", "digit-5", "page-digits-2"));
        PAGES.put("digits-2", Arrays.asList("digit-6", "digit-7", "digit-8", "digit-9", "digit-0", "page-keys"));
        PAGES.put("ctrl", Arrays.asList("ctrl-c", "ctrl-d", "ctrl-z", "ctrl-r", "ctrl-l", "page-ctrl-2"));
        PAGES.put("ctrl-2", Arrays.asList("ctrl-a", "ctrl-e", "ctrl-u", "ctrl-k", "ctrl-w", "page-ctrl-3"));
        PAGES.put("ctrl-3", Arrays.asList("ctrl-b", "ctrl-q", "ctrl-t", "ctrl-y", "ctrl-i", "page-ctrl-4"));
        PAGES.put("ctrl-4", Arrays.asList("ctrl-o", "ctrl-p", "ctrl-s", "ctrl-f", "ctrl-g", "page-ctrl-5"));
        PAGES.put("ctrl-5", Arrays.asList("ctrl-h", "ctrl-j", "ctrl-x", "ctrl-v", "ctrl-n", "page-ctrl-6"));
        PAGES.put("ctrl-6", Arrays.asList("ctrl-m", "ctrl-backslash", "page-keys"));
    }
    /** How to reach each page from the terminal keys, one page key per tap. */
    static final Map<String, List<String>> ROUTES = new LinkedHashMap<>();
    static {
        ROUTES.put("keys", Arrays.asList());
        ROUTES.put("more", Arrays.asList("page-more"));
        ROUTES.put("digits", Arrays.asList("page-more", "page-digits"));
        ROUTES.put("digits-2", Arrays.asList("page-more", "page-digits", "page-digits-2"));
        ROUTES.put("ctrl", Arrays.asList("page-more", "page-ctrl"));
        for (int n = 2; n <= 6; n++) {
            List<String> route = new ArrayList<>(ROUTES.get(n == 2 ? "ctrl" : "ctrl-" + (n - 1)));
            route.add("page-ctrl-" + n);
            ROUTES.put("ctrl-" + n, route);
        }
    }
    /** #3127: 48dp hit cells, 44dp visible keycaps. */
    private static final double MIN_TARGET_PX = 48.0;
    private static final double MIN_CAP_PX = 44.0;
    /** Sub-pixel slack for fractional layout, nothing more. */
    private static final double EPS = 0.5;
    private static final String DOCK = "[data-testid=fast-keys-dock]";
    private static final String PIXEL_LAUNCHER = "com.google.android.apps.nexuslauncher";

    private final JSONObject evidence = new JSONObject();
    /** Whether this device can show a launcher gesture at all: gesture navigation with the Pixel launcher enabled. */
    private boolean launcherOracleLive;

    /** J23 runs first in the hotkeys lane and consumes its own staged key; J30 imports its own copy. */
    @Override
    protected String fixtureKeyArgument() {
        return "dockSshPrivateKeyPath";
    }

    @Test
    public void dockAnswersApprovalPromptWithExactBytesImeClosedAndOpen() throws Exception {
        try {
            runJourney();
        } catch (Throwable failure) {
            // Evidence for a red run: what was on screen and what the IME service reported.
            screenshot("j30-failure");
            Log.e(TAG, "J30 failed; " + ScriptedIme.inputMethodState() + " evidence=" + evidence, failure);
            throw failure;
        }
    }

    private void runJourney() throws Exception {
        String run = sessionRun();
        String folder = run + "-dock";
        openFixtureHost(run);
        awaitFolder(folder);
        openFolder(folder);
        awaitJsTrue(VISIBLE_TERMINAL + ".includes('$')");
        // Setup only: the floating prompt card is not under test and may open
        // over the terminal; its toggle stays.
        evalString("(() => {document.querySelector('button[aria-label=\"Close the prompt panel\"]')?.click();return 'ok';})()");

        // RED on a shell without a contributed dock: nothing is drawn.
        awaitJsTrue("!!document.querySelector(" + JSONObject.quote(DOCK) + ")", 20_000);

        JSONObject device = nativeState();
        evidence.put("device", device);
        // The device's own state; J30 changes neither (review round 6).
        String navigationMode = ScriptedIme.shell("settings get secure navigation_mode").trim();
        boolean launcherEnabled = ScriptedIme.shell("pm list packages -e " + PIXEL_LAUNCHER).contains("package:" + PIXEL_LAUNCHER);
        launcherOracleLive = "2".equals(navigationMode) && launcherEnabled;
        evidence.put("navigationMode", navigationMode);
        evidence.put("pixelLauncherEnabled", launcherEnabled);
        Log.i(TAG, "J30 navigation_mode=" + navigationMode + " pixelLauncherEnabled=" + launcherEnabled
                + " launcherOracleLive=" + launcherOracleLive);
        Log.i(TAG, "J30 device " + device);

        // ---- phase 1: soft keyboard closed --------------------------------
        // Pasting the reader's command focuses the terminal, which can bring
        // the keyboard up; it is closed again before the dock's Enter starts
        // the reader, so the reader's size baseline is the keyboard-closed one.
        startApprovalReader(folder, "ime-closed", true);
        assertTrue("phase 1 needs the soft keyboard closed: " + nativeState(), nativeState().getBoolean("imeClosed"));
        JSONObject closedGeometry = assertDockGeometry("ime-closed", false);
        evidence.put("imeClosed", closedGeometry);
        committedScreenshot("j30-approval-dock-ime-closed");
        if (launcherOracleLive) assertLauncherOracleIsLive();
        long phaseStart = System.currentTimeMillis();
        answerApprovalAndEnd();
        assertLauncherQuiet("the ime-closed answer taps", phaseStart);
        String closedHex = awaitReaderHex("ime-closed");
        assertNoRemoteResizeDuringPhase("ime-closed");
        assertEquals("IME closed: the host must receive exactly 2, Esc, Shift+Tab (decoded: "
                + JSONObject.quote(decodeHex(closedHex)) + ")", EXPECTED_APPROVAL_HEX, closedHex);
        assertTrue("tapping the dock must not open the soft keyboard: " + nativeState(),
                nativeState().getBoolean("imeClosed"));
        // A hardware keyboard's Enter must not re-fire the last dock key: with
        // the soft keyboard down, a dock key gives focus up after its tap.
        assertEquals("no dock key may keep focus after a tap with the soft keyboard down", "false",
                evalString("String(!!document.activeElement?.closest?.(" + JSONObject.quote(DOCK) + "))"));

        // ---- every key on every page, keyboard closed ----------------------
        startApprovalReader(folder, "all-keys", true);
        tapEveryKeyOnEveryPage();
        String allHex = awaitReaderHex("all-keys");
        assertNoRemoteResizeDuringPhase("all-keys");
        assertEquals("every dock key must reach the host PTY as its exact bytes, page by page (decoded: "
                + JSONObject.quote(decodeHex(allHex)) + ")", EXPECTED_ALL_KEYS_HEX, allHex);
        assertTrue("tapping the dock must not open the soft keyboard: " + nativeState(),
                nativeState().getBoolean("imeClosed"));

        // ---- phase 2: soft keyboard open ----------------------------------
        focusTerminalForIme();
        awaitImeVisible(true);
        startApprovalReader(folder, "ime-open", false);
        JSONObject openGeometry = assertDockGeometry("ime-open", true);
        evidence.put("imeOpen", openGeometry);
        committedScreenshot("j30-approval-dock-ime-open");
        phaseStart = System.currentTimeMillis();
        answerApprovalAndEnd();
        assertLauncherQuiet("the ime-open answer taps", phaseStart);
        String openHex = awaitReaderHex("ime-open");
        assertNoRemoteResizeDuringPhase("ime-open");
        // The dock is reserved once: the same height whichever state the
        // keyboard is in, so it never adds a resize of its own to a toggle.
        assertEquals("the dock must keep one fixed height across a keyboard toggle",
                closedGeometry.getJSONObject("dock").getDouble("height"),
                openGeometry.getJSONObject("dock").getDouble("height"), 0.5);
        assertEquals("IME open: the host must receive exactly 2, Esc, Shift+Tab (decoded: "
                + JSONObject.quote(decodeHex(openHex)) + ")", EXPECTED_APPROVAL_HEX, openHex);
        assertTrue("tapping the dock must not close the soft keyboard: " + nativeState(),
                nativeState().getBoolean("imeVisible"));
        assertTerminalKeepsFocus("after the dock answered the prompt with the keyboard open");
        assertAppResumed("after every dock tap");
        committedScreenshot("j30-approval-answered-ime-open");

        // ---- phase 3 (#3201): leaving and coming back never raises the keyboard
        evidence.put("resumeCycles", resumeNeverRaisesTheKeyboard());
        Log.i(TAG, "J30 evidence " + evidence);
    }

    /** Background/resume cycles; each one starts with the keyboard up on the focused terminal. */
    static final int RESUME_CYCLES = 8;
    /** How long after the window is focused again a server-side IME restore is watched for. */
    private static final long RESUME_IME_WATCH_MS = 3_000;

    /**
     * #3201: with the soft keyboard up on the focused terminal, the app goes to
     * the background and comes back. The keyboard must stay down after every
     * return (maintainer rule, #3126: resume must not raise the keyboard).
     *
     * <p>Android decides this in the system server when the window gets focus
     * back: with {@code SOFT_INPUT_STATE_UNSPECIFIED} it re-shows the IME
     * ({@code ImeTracker ... ORIGIN_SERVER reason SHOW_RESTORE_IME_VISIBILITY})
     * whenever the page's editor is already attached at that moment, and hides
     * it when it is not -- a race the app does not control, red ~2 in 7 in J23.
     * One cycle passes most of the time on a broken app; {@link #RESUME_CYCLES}
     * cycles make the race show. Each cycle watches the window insets and the
     * input method service for {@link #RESUME_IME_WATCH_MS} after the window
     * has focus again, so a restore that lands late still fails.
     */
    private JSONArray resumeNeverRaisesTheKeyboard() throws Exception {
        JSONArray cycles = new JSONArray();
        for (int cycle = 1; cycle <= RESUME_CYCLES; cycle++) {
            if (!nativeState().getBoolean("imeVisible")) {
                focusTerminalForIme();
                awaitImeVisible(true);
            }
            assertTerminalKeepsFocus("before background/resume cycle " + cycle);
            scenario.moveToState(Lifecycle.State.CREATED);
            awaitBackgroundAndImeHidden("background cycle " + cycle);
            scenario.moveToState(Lifecycle.State.RESUMED);
            awaitWindowFocus("resume cycle " + cycle);
            long watchUntil = SystemClock.uptimeMillis() + RESUME_IME_WATCH_MS;
            boolean insetsShown = false;
            while (SystemClock.uptimeMillis() < watchUntil) {
                if (imeInsetsVisible()) {
                    insetsShown = true;
                    break;
                }
                SystemClock.sleep(100);
            }
            JSONObject after = nativeState();
            String activeElement = evalString("document.activeElement?.tagName + '.' + String(document.activeElement?.className ?? '')");
            JSONObject record = new JSONObject().put("cycle", cycle).put("insetsShownAfterResume", insetsShown)
                    .put("imeServiceShown", after.getBoolean("imeServiceShown"))
                    .put("keyboardCss", after.getDouble("keyboardCss")).put("activeElement", activeElement);
            cycles.put(record);
            Log.i(TAG, "PS3201Resume " + record);
            if (insetsShown || !after.getBoolean("imeClosed")) {
                screenshot("j30-resume-raised-keyboard");
                throw new AssertionError("resume cycle " + cycle + " of " + RESUME_CYCLES
                        + " brought the soft keyboard back (#3201/#3126): " + record + " native=" + after
                        + " " + ScriptedIme.inputMethodState());
            }
        }
        return cycles;
    }

    private boolean imeInsetsVisible() {
        boolean[] visible = new boolean[1];
        scenario.onActivity(activity -> {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            visible[0] = insets != null && insets.isVisible(WindowInsets.Type.ime());
        });
        return visible[0];
    }

    private void awaitBackgroundAndImeHidden(String when) {
        long deadline = SystemClock.uptimeMillis() + 15_000;
        boolean[] focused = new boolean[1];
        boolean insetsVisible = true;
        boolean inputShown = true;
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> focused[0] = activity.hasWindowFocus());
            insetsVisible = imeInsetsVisible();
            inputShown = ScriptedIme.shell("dumpsys input_method").contains("mInputShown=true");
            if (!focused[0] && !insetsVisible && !inputShown) {
                return;
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("the app window and IME did not settle in background after " + when
                + " (hasWindowFocus=" + focused[0]
                + ", imeInsetsVisible=" + insetsVisible
                + ", mInputShown=" + inputShown + ")");
    }

    private void awaitWindowFocus(String when) {
        long deadline = SystemClock.uptimeMillis() + 15_000;
        boolean[] focused = new boolean[1];
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> focused[0] = activity.hasWindowFocus());
            if (focused[0]) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("the app window never got focus back after " + when);
    }

    /** 2 (More, 123), Esc (home), Shift+Tab (More), then ^D (More, Ctrl) ends the reader. */
    private void answerApprovalAndEnd() throws Exception {
        showPage("digits");
        tapDockKey("digit-2");
        showPage("keys");
        tapDockKey("escape");
        showPage("more");
        tapDockKey("shift-tab");
        showPage("ctrl");
        tapDockKey("ctrl-d");
        showPage("keys");
    }

    /**
     * Every page in model order, from the terminal keys: the page's cells are
     * exactly the expected keys, each at least 48x48 with a 44x44 cap and the
     * row unscrollable (assertPageCells), then every byte-sending key is
     * tapped once -- ^D, the reader's terminator, last of all. The launcher's
     * log is checked after each page. The two maintainer-facing pages are
     * screenshotted for the issue.
     */
    private void tapEveryKeyOnEveryPage() throws Exception {
        List<String> tapped = new ArrayList<>();
        for (Map.Entry<String, List<String>> page : PAGES.entrySet()) {
            long pageStart = System.currentTimeMillis();
            showPage(page.getKey());
            assertPageCells(page.getKey(), page.getValue());
            if ("keys".equals(page.getKey()) || "more".equals(page.getKey())) committedScreenshot("j30-dock-page-" + page.getKey());
            for (String key : page.getValue()) {
                if (key.startsWith("page-") || "ctrl-d".equals(key)) continue;
                tapDockKey(key);
                tapped.add(key);
            }
            assertLauncherQuiet("the taps on page " + page.getKey(), pageStart);
        }
        showPage("ctrl");
        tapDockKey("ctrl-d");
        showPage("keys");
        evidence.put("allKeysTapped", tapped.size());
        Log.i(TAG, "J30 tapped every key on every page: " + tapped.size() + " keys " + tapped);
    }

    /** The page's cells are exactly {@code expected}, each a full 48px cell on screen, and the row cannot scroll. */
    private void assertPageCells(String page, List<String> expected) throws Exception {
        JSONObject g = new JSONObject(evalString("JSON.stringify((() => {"
                + "const dock=document.querySelector(" + JSONObject.quote(DOCK) + ");const d=dock.getBoundingClientRect();"
                + "const keys=[...dock.querySelectorAll('button')].map((b)=>{const r=b.getBoundingClientRect();"
                + "const c=b.querySelector('.fast-keys-dock__cap').getBoundingClientRect();"
                + "const hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);"
                + "return {id:b.dataset.keyId,left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,height:r.height,"
                + "capWidth:c.width,capHeight:c.height,hit:!!hit&&(hit===b||b.contains(hit))};});"
                + "return {page:dock.dataset.page,dock:{left:d.left,right:d.right,top:d.top,bottom:d.bottom},keys};})())"));
        assertEquals("dock page", page, g.getString("page"));
        JSONArray keys = g.getJSONArray("keys");
        List<String> ids = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        JSONObject dock = g.getJSONObject("dock");
        for (int i = 0; i < keys.length(); i++) {
            JSONObject key = keys.getJSONObject(i);
            ids.add(key.getString("id"));
            if (key.getDouble("width") < MIN_TARGET_PX - EPS || key.getDouble("height") < MIN_TARGET_PX - EPS) {
                violations.add(key.getString("id") + " cell is " + key.getDouble("width") + "x" + key.getDouble("height"));
            }
            if (key.getDouble("capWidth") < MIN_CAP_PX - EPS || key.getDouble("capHeight") < MIN_CAP_PX - EPS) {
                violations.add(key.getString("id") + " keycap is " + key.getDouble("capWidth") + "x" + key.getDouble("capHeight"));
            }
            if (key.getDouble("left") < dock.getDouble("left") - EPS || key.getDouble("right") > dock.getDouble("right") + EPS) {
                violations.add(key.getString("id") + " is outside the dock: " + key);
            }
            if (!key.getBoolean("hit")) violations.add(key.getString("id") + " is covered at its centre");
        }
        assertEquals("page " + page + " cells", expected, ids);
        assertDockNotScrollable("page " + page);
        Log.i(TAG, "J30 page " + page + " cells " + keys);
        assertTrue("page " + page + " cell violations:\n - " + String.join("\n - ", violations), violations.isEmpty());
    }

    /**
     * Review round 6: the row has no sideways scroll at all. Its content fits
     * its box, nothing inside it is a horizontal scroller, and a programmatic
     * scroll does not move it.
     */
    private void assertDockNotScrollable(String when) throws Exception {
        JSONObject s = new JSONObject(evalString("JSON.stringify((() => {"
                + "const dock=document.querySelector(" + JSONObject.quote(DOCK) + ");"
                + "const scrollers=[dock,...dock.querySelectorAll('*')].filter((e)=>{const o=getComputedStyle(e).overflowX;"
                + "return (o==='auto'||o==='scroll')||e.scrollWidth>e.clientWidth+1;}).map((e)=>e.className||e.tagName);"
                + "dock.scrollLeft=100;const moved=dock.scrollLeft;dock.scrollLeft=0;"
                + "return {scrollWidth:dock.scrollWidth,clientWidth:dock.clientWidth,overflowX:getComputedStyle(dock).overflowX,moved,scrollers};})())"));
        assertTrue("the dock row must not be horizontally scrollable (" + when + "): " + s,
                s.getDouble("scrollWidth") <= s.getDouble("clientWidth") + 1
                        && s.getDouble("moved") == 0
                        && s.getJSONArray("scrollers").length() == 0
                        && !"auto".equals(s.getString("overflowX")) && !"scroll".equals(s.getString("overflowX")));
    }

    /**
     * Gesture navigation with the Pixel launcher: a touch that MOVES from the
     * launcher's bottom band takes the launcher out of its Normal state (the
     * start of quick-switch / Home). One such drag under the dock must show
     * that transition, or "no transition" after the dock taps proves nothing.
     * J30 then waits for the launcher to settle back to Normal, so the dock's
     * checks start from the state a user is in.
     */
    private void assertLauncherOracleIsLive() throws Exception {
        JSONObject nat = nativeState();
        double dpr = nat.getDouble("density");
        double x = nat.getInt("decorWidthPx") / dpr / 2;
        double y = (nat.getInt("decorHeightPx") - 10 - nat.getInt("webViewTopPx")) / dpr;
        long start = System.currentTimeMillis();
        dragInBandCss(x, y, dpr);
        String transitions = awaitLauncherTransitions(start, 3_000);
        Log.i(TAG, "J30 launcher control drag below the dock: " + (transitions.isEmpty() ? "no transition" : "transition\n" + transitions));
        evidence.put("launcherControl", transitions.isEmpty() ? "none" : "transition");
        assertFalse("control: a drag starting in the launcher's bottom band must take the launcher out of Normal"
                + " (else the per-page checks are blind on this device)", transitions.isEmpty());
        awaitLauncherBackToNormal(start, 10_000);
        assertAppResumed("after the control drag below the dock");
    }

    /** A short sideways drag (8 device px over ~100ms) from a point given in CSS px: what starts the launcher's switch. */
    private void dragInBandCss(double x, double y, double dpr) {
        int[] origin = webViewOrigin();
        android.app.UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        float sx = (float) (origin[0] + x * dpr);
        float sy = (float) (origin[1] + y * dpr);
        long down = SystemClock.uptimeMillis();
        inject(automation, down, down, MotionEvent.ACTION_DOWN, sx, sy);
        for (int i = 1; i <= 4; i++) inject(automation, down, down + i * 20L, MotionEvent.ACTION_MOVE, sx + i * 2, sy);
        inject(automation, down, down + 100L, MotionEvent.ACTION_UP, sx + 8, sy);
    }

    /** After the control's transition, the launcher's last state change since {@code sinceMillis} must be back to Normal. */
    private void awaitLauncherBackToNormal(long sinceMillis, long timeoutMillis) {
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        String last = "";
        while (SystemClock.uptimeMillis() < deadline) {
            for (String line : launcherLinesSince(sinceMillis).split("\n")) if (line.contains("goToState")) last = line;
            if (last.contains("toState: Normal")) {
                SystemClock.sleep(500);
                return;
            }
            SystemClock.sleep(200);
        }
        throw new AssertionError("the launcher did not settle back to Normal after the control drag; last state change: " + last);
    }

    /**
     * What a user would see after {@code what} (review round 7): the
     * launcher never left its Normal state (no quick-switch, Home or recents
     * transition) and the app is still the top resumed activity; the host
     * bytes are checked by the caller. The launcher merely starting to TRACK
     * a touch is not a failure: on gesture navigation every tap in its bottom
     * band does that and then cancels, as for any app's bottom bar. Those
     * lines are counted as evidence only.
     */
    private void assertLauncherQuiet(String what, long sinceMillis) {
        String lines = launcherLinesSince(sinceMillis);
        String transitions = transitionsIn(lines);
        int tracked = 0;
        for (String line : lines.split("\n")) if (line.contains("startTouchTrackingForWindowAnimation")) tracked++;
        if (launcherOracleLive) {
            Log.i(TAG, "J30 launcher after " + what + ": tracking=" + tracked + " transitions="
                    + (transitions.isEmpty() ? "none" : "\n" + transitions));
        }
        assertTrue(what + " must not take the launcher out of Normal (Home / quick-switch / recents):\n" + transitions,
                transitions.isEmpty());
        assertAppResumed("after " + what);
    }


    private void showPage(String page) throws Exception {
        // Home first: every page's last cell leads on, and every chain ends at Keys.
        for (int i = 0; i < 8 && !"keys".equals(currentPage()); i++) {
            String current = currentPage();
            List<String> cells = PAGES.get(current);
            assertTrue("unknown dock page " + current, cells != null);
            String last = cells.get(cells.size() - 1);
            tapDockKey(cells.contains("page-keys") ? "page-keys" : last);
            awaitPageChange(current);
        }
        assertEquals("the dock must come home to the terminal keys", "keys", currentPage());
        for (String key : ROUTES.get(page)) {
            String current = currentPage();
            tapDockKey(key);
            awaitPageChange(current);
        }
        awaitPage(page);
    }

    private String currentPage() throws Exception {
        return evalString("document.querySelector(" + JSONObject.quote(DOCK) + ")?.dataset.page ?? ''");
    }

    private void awaitPageChange(String from) throws Exception {
        awaitJsTrue("document.querySelector(" + JSONObject.quote(DOCK) + ")?.dataset.page !== " + JSONObject.quote(from), 10_000);
    }

    /**
     * One real touchscreen tap on a dock key of the current page. Its centre
     * is hit-tested first (the topmost element there must be the key -- not
     * the composer toggle, the terminal or anything else); the dock never
     * scrolls, so a key not on the page is a failure, not something to swipe to.
     */
    private void tapDockKey(String keyId) throws Exception {
        String selector = DOCK + " [data-key-id=" + JSONObject.quote(keyId) + "]";
        JSONObject probe = new JSONObject(evalString("JSON.stringify((() => {"
                + "const b=document.querySelector(" + JSONObject.quote(selector) + ");if(!b) return {missing:true};"
                + "const r=b.getBoundingClientRect();const cx=r.left+r.width/2,cy=r.top+r.height/2;"
                + "const hit=document.elementFromPoint(cx,cy);"
                + "return {missing:false,left:r.left,right:r.right,top:r.top,width:r.width,height:r.height,cx,cy,innerWidth,"
                + "hit:!!hit&&(hit===b||b.contains(hit)),hitBy:hit?(hit.tagName+'.'+String(hit.className&&hit.className.baseVal!==undefined?hit.className.baseVal:hit.className)).slice(0,60):null,"
                + "dpr:devicePixelRatio};})())"));
        assertFalse("dock key " + keyId + " is not on page " + currentPage(), probe.getBoolean("missing"));
        assertTrue("dock key " + keyId + " is not fully on screen: " + probe,
                probe.getDouble("left") >= -EPS && probe.getDouble("right") <= probe.getDouble("innerWidth") + EPS);
        assertTrue("dock key " + keyId + " is covered at its centre (topmost: " + probe.optString("hitBy") + "): " + probe,
                probe.getBoolean("hit"));
        int clicksBefore = dockClicks(keyId);
        tapCss(probe.getDouble("cx"), probe.getDouble("cy"), probe.getDouble("dpr"));
        // The tap must reach the page as exactly one click on this key.
        long deadline = SystemClock.uptimeMillis() + 3_000;
        while (SystemClock.uptimeMillis() < deadline && dockClicks(keyId) == clicksBefore) Thread.sleep(50);
        int clicks = dockClicks(keyId) - clicksBefore;
        assertEquals("one real tap on dock key " + keyId + " must produce exactly one click; trace="
                + evalString("JSON.stringify(window.__ps3158DockTrace.slice(-20))") + " probe=" + probe, 1, clicks);
    }

    /**
     * Starts the host's approval-menu reader for this phase. The command is
     * pasted (setup, not the path under test) and submitted with the DOCK's
     * Enter key, so Enter is exercised through the same path as the answer.
     */
    private void startApprovalReader(String folder, String phase, boolean keyboardClosed) throws Exception {
        showPage("keys");
        pasteIntoTerminal("python3 ~/" + folder + "/ps3158-approval.py ~/" + folder + "/ps3158-" + phase + ".hex " + phase);
        if (keyboardClosed) hideKeyboard();
        tapDockKey("enter");
        awaitJsTrue(VISIBLE_TERMINAL + ".replace(/\\n/g, '').includes('PS3158_READY_' + " + JSONObject.quote(phase) + ")");
        awaitJsTrue(VISIBLE_TERMINAL + ".includes('Do you want to proceed?')");
    }

    /**
     * Maintainer decision (#3158/#3126): the remote terminal must not resize
     * for UI that comes and goes. The host reader reports its own PTY size
     * (what `stty size` prints) when the prompt appears and again after the
     * dock's taps and page switches; the dock must
     * not have changed it. The per-phase sizes are kept as evidence: a
     * keyboard toggle itself still refits the terminal on main today, which
     * is S5 #3126's to remove, not this dock's.
     */
    private void assertNoRemoteResizeDuringPhase(String phase) throws Exception {
        String text = evalString(VISIBLE_TERMINAL + ".replace(/\\n/g, '')");
        java.util.regex.Matcher start = java.util.regex.Pattern.compile(
                "PS3158_READY_" + java.util.regex.Pattern.quote(phase) + " size=(\\d+x\\d+)").matcher(text);
        java.util.regex.Matcher end = java.util.regex.Pattern.compile(
                "PS3158_HEX_" + java.util.regex.Pattern.quote(phase) + ":[0-9a-f]*:END size=(\\d+x\\d+)").matcher(text);
        assertTrue("the host reader must report its PTY size at the prompt (" + phase + "): " + text, start.find());
        assertTrue("the host reader must report its PTY size at the end (" + phase + "): " + text, end.find());
        evidence.put("hostPtySize-" + phase, start.group(1) + " -> " + end.group(1));
        Log.i(TAG, "J30 host PTY size " + phase + ": " + start.group(1) + " -> " + end.group(1));
        assertEquals("the dock must not resize the remote terminal (" + phase + "; host stty size before -> after)",
                start.group(1), end.group(1));
    }

    /** The reader's own report of what it received, tagged with this phase so the other phase's cannot stand in. */
    private String awaitReaderHex(String phase) throws Exception {
        java.util.regex.Pattern captured = java.util.regex.Pattern.compile(
                "PS3158_HEX_" + java.util.regex.Pattern.quote(phase) + ":([0-9a-f]*):END");
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        String text = "";
        while (SystemClock.uptimeMillis() < deadline) {
            text = evalString(VISIBLE_TERMINAL + ".replace(/\\n/g, '')");
            java.util.regex.Matcher match = captured.matcher(text);
            if (match.find()) return match.group(1);
            Thread.sleep(150);
        }
        throw new AssertionError("the host reader never reported its bytes; terminal=" + text);
    }

    private void awaitPage(String page) throws Exception {
        awaitJsTrue("document.querySelector(" + JSONObject.quote(DOCK) + ")?.dataset.page === " + JSONObject.quote(page), 10_000);
    }

    /** Page-side record of dock pointer/click events (diagnostics, and the per-tap click count). */
    private int dockClicks(String keyId) throws Exception {
        return Integer.parseInt(evalRaw("(() => {if(!window.__ps3158DockTrace){const t=window.__ps3158DockTrace=[];"
                + "for(const type of ['pointerdown','pointerup','pointercancel','click']){document.addEventListener(type,(e)=>{"
                + "const k=e.target?.closest?.('[data-key-id]');if(k&&k.closest('" + DOCK + "'))t.push([type,k.dataset.keyId,e.detail??'',Math.round(e.timeStamp)].join('|'));},true);}}"
                + "return window.__ps3158DockTrace.filter((x)=>x.startsWith('click|'+" + JSONObject.quote(keyId) + "+'|')).length;})()"));
    }

    /** The launcher's tracking and state-change log lines since {@code sinceMillis} (wall clock, the device's own). */
    private static String launcherLinesSince(long sinceMillis) {
        String since = String.format(java.util.Locale.ROOT, "%d.%03d", sinceMillis / 1000, sinceMillis % 1000);
        StringBuilder out = new StringBuilder();
        for (String line : ScriptedIme.shell("logcat -d -v epoch -T " + since + " -s ActivityTracker:D RecentsView:D StateManager:D").split("\n")) {
            if (line.contains("startTouchTrackingForWindowAnimation") || line.contains("goToState")) {
                out.append(line.trim()).append('\n');
            }
        }
        return out.toString();
    }

    /** The state changes that leave Normal: {@code goToState - fromState: X, toState: <not Normal>}. */
    private static String transitionsIn(String lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines.split("\n")) {
            if (line.contains("goToState") && line.contains("toState:") && !line.contains("toState: Normal")) out.append(line).append('\n');
        }
        return out.toString();
    }


    private static String awaitLauncherTransitions(long sinceMillis, long timeoutMillis) {
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        String transitions = "";
        while (SystemClock.uptimeMillis() < deadline) {
            transitions = transitionsIn(launcherLinesSince(sinceMillis));
            if (!transitions.isEmpty()) return transitions;
            SystemClock.sleep(200);
        }
        return transitions;
    }


    /** The app's activity is the top resumed one (the launcher did not take over). */
    private void assertAppResumed(String when) {
        String app = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
        String top = "";
        long deadline = SystemClock.uptimeMillis() + 3_000;
        while (SystemClock.uptimeMillis() < deadline) {
            for (String line : ScriptedIme.shell("dumpsys activity activities").split("\n")) {
                if (line.contains("topResumedActivity=")) top = line.trim();
            }
            if (top.contains(app + "/")) return;
            SystemClock.sleep(200);
        }
        throw new AssertionError("the app must stay the top resumed activity " + when + ": " + top);
    }

    private int[] webViewOrigin() {
        AtomicReference<int[]> origin = new AtomicReference<>();
        scenario.onActivity(activity -> {
            int[] loc = new int[2];
            activity.getBridge().getWebView().getLocationOnScreen(loc);
            origin.set(loc);
        });
        return origin.get();
    }

    private void tapCss(double x, double y, double dpr) {
        int[] origin = webViewOrigin();
        PhysicalTap.tap((float) (origin[0] + x * dpr), (float) (origin[1] + y * dpr));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(150);
    }

    private static void inject(android.app.UiAutomation automation, long downTime, long eventTime, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            assertTrue("touch event must be injected", automation.injectInputEvent(event, true));
        } finally {
            event.recycle();
        }
    }

    /**
     * Numeric containment at the device's real width: dock inside the
     * viewport, above the keyboard when it is up (the WebView is resized for
     * it), the terminal ending at the dock's top (no terminal row under the
     * dock), every key cell at least 48x48 and its keycap 44x44 CSS px, and,
     * with the keyboard down, the dock above the navigation bar or gesture handle.
     */
    private JSONObject assertDockGeometry(String phase, boolean imeUp) throws Exception {
        JSONObject nat = nativeState();
        JSONObject g = new JSONObject(evalString("JSON.stringify((() => {"
                + "const rect=(e)=>{const r=e.getBoundingClientRect();return {left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height};};"
                + "const dock=document.querySelector(" + JSONObject.quote(DOCK) + ");"
                + "const xterm=[...document.querySelectorAll('.xterm')].find((n)=>n.offsetParent!==null);"
                + "const screen=xterm?.querySelector('.xterm-screen');"
                + "const rail=document.querySelector('.composer-dock .rail');"
                + "const keys=[...dock.querySelectorAll('button')].map((b)=>({id:b.dataset.keyId,...rect(b),cap:rect(b.querySelector('.fast-keys-dock__cap'))}));"
                + "return {innerWidth,innerHeight,dpr:devicePixelRatio,coarse:matchMedia('(pointer: coarse)').matches,"
                + "dock:rect(dock),xterm:xterm?rect(xterm):null,screen:screen?rect(screen):null,rail:rail?rect(rail):null,"
                + "rows:xterm?xterm.querySelectorAll('.xterm-rows > div').length:0,keys};})())"));
        g.put("native", nat);
        g.put("phase", phase);
        List<String> violations = new ArrayList<>();
        JSONObject dock = g.getJSONObject("dock");
        double innerWidth = g.getDouble("innerWidth");
        double innerHeight = g.getDouble("innerHeight");
        if (dock.getDouble("height") < MIN_TARGET_PX - EPS) violations.add("dock row is " + dock.getDouble("height") + "px tall");
        if (dock.getDouble("top") < -EPS || dock.getDouble("bottom") > innerHeight + EPS
                || dock.getDouble("left") < -EPS || dock.getDouble("right") > innerWidth + EPS) {
            violations.add("dock is outside the viewport " + innerWidth + "x" + innerHeight + ": " + dock);
        }
        if (!imeUp) {
            // On the real screen the dock must end above the navigation bar
            // inset, which is where the three-button bar or the gesture handle
            // is drawn (the 24dp band under gesture navigation), never under it.
            // 3px: the viewport's CSS height rounds (915 vs 914.3dp).
            double dockBottomPx = nat.getInt("webViewTopPx") + dock.getDouble("bottom") * g.getDouble("dpr");
            double barTopPx = nat.getInt("decorHeightPx") - nat.getInt("navBottomPx");
            // 0 when no bar is drawn at all (a lane's preflight disables the
            // HOME launcher, and gesture navigation then draws no handle);
            // the dock must then simply end on the screen.
            g.put("navigationBarDrawn", nat.getInt("navBottomPx") > 0);
            if (dockBottomPx > barTopPx + 3) {
                violations.add("the dock (bottom " + dockBottomPx + "px) runs under the navigation bar / gesture handle (top " + barTopPx + "px): " + nat);
            }
        }
        if (!imeUp && !nat.getBoolean("imeClosed")) {
            violations.add("the keyboard is not closed: " + nat);
        }
        if (imeUp) {
            // The WebView ends at the keyboard's top edge; the dock is the
            // bottom of the visible page, i.e. directly on the keyboard.
            if (!nat.getBoolean("imeVisible")) violations.add("keyboard is not up: " + nat);
            // The page ends at the keyboard's top edge; the dock is the page's
            // bottom row, i.e. directly on the keyboard.
            if (Math.abs(dock.getDouble("bottom") - innerHeight) > 2) {
                violations.add("dock does not sit on the keyboard: dock.bottom=" + dock.getDouble("bottom") + " innerHeight=" + innerHeight);
            }
        }
        JSONObject xterm = g.optJSONObject("xterm");
        JSONObject screen = g.optJSONObject("screen");
        if (xterm == null || screen == null) {
            violations.add("no visible terminal");
        } else {
            if (xterm.getDouble("bottom") > dock.getDouble("top") + EPS) {
                violations.add("terminal (bottom " + xterm.getDouble("bottom") + ") runs under the dock (top " + dock.getDouble("top") + ")");
            }
            if (screen.getDouble("bottom") > dock.getDouble("top") + EPS) {
                violations.add("terminal rows (bottom " + screen.getDouble("bottom") + ") are under the dock (top " + dock.getDouble("top") + ")");
            }
        }
        JSONObject rail = g.optJSONObject("rail");
        if (rail != null && rail.getDouble("bottom") > dock.getDouble("top") + EPS
                && rail.getDouble("right") > dock.getDouble("left") && rail.getDouble("left") < dock.getDouble("right")) {
            violations.add("the composer toggle overlaps the dock: rail=" + rail + " dock=" + dock);
        }
        JSONArray keys = g.getJSONArray("keys");
        // Round 6: one row of six fixed cells on every page (FAST_KEYS_DOCK_CELLS).
        if (keys.length() != 6) violations.add("the dock row has " + keys.length() + " cells, not 6");
        for (int i = 0; i < keys.length(); i++) {
            JSONObject key = keys.getJSONObject(i);
            if (key.getDouble("width") < MIN_TARGET_PX - EPS || key.getDouble("height") < MIN_TARGET_PX - EPS) {
                violations.add("key " + key.optString("id") + " is " + key.getDouble("width") + "x" + key.getDouble("height") + "px");
            }
            JSONObject cap = key.getJSONObject("cap");
            if (cap.getDouble("width") < MIN_CAP_PX - EPS || cap.getDouble("height") < MIN_CAP_PX - EPS) {
                violations.add("keycap " + key.optString("id") + " is " + cap.getDouble("width") + "x" + cap.getDouble("height") + "px");
            }
        }
        if (!g.getBoolean("coarse")) violations.add("the WebView does not report pointer: coarse");
        assertDockNotScrollable(phase);
        Log.i(TAG, "J30 geometry " + g);
        assertTrue("J30 dock geometry (" + phase + ") violations:\n - " + String.join("\n - ", violations) + "\n" + g,
                violations.isEmpty());
        return g;
    }

    private JSONObject nativeState() throws Exception {
        JSONObject out = new JSONObject();
        CountDownLatch latch = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            try {
                float density = activity.getResources().getDisplayMetrics().density;
                out.put("density", density);
                out.put("widthDp", Math.round(activity.getResources().getDisplayMetrics().widthPixels / density));
                out.put("heightDp", Math.round(activity.getResources().getDisplayMetrics().heightPixels / density));
                WebView webView = activity.getBridge().getWebView();
                int[] loc = new int[2];
                webView.getLocationOnScreen(loc);
                out.put("webViewTopPx", loc[1]);
                out.put("webViewBottomPx", loc[1] + webView.getHeight());
                WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                out.put("insetsImeVisible", insets != null && insets.isVisible(WindowInsets.Type.ime()));
                out.put("navBottomPx", insets == null ? 0 : insets.getInsets(WindowInsets.Type.navigationBars()).bottom);
                out.put("mandatoryGestureBottomPx", insets == null ? 0 : insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom);
                out.put("decorHeightPx", activity.getWindow().getDecorView().getHeight());
                out.put("decorWidthPx", activity.getWindow().getDecorView().getWidth());
            } catch (Exception error) {
                try { out.put("error", String.valueOf(error)); } catch (Exception ignored) { }
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        // The input method service's own record of whether a keyboard is on
        // screen: on API 35 the scripted IME's panel can be showing while the
        // window insets report it hidden, so the insets are evidence only.
        String dump = ScriptedIme.shell("dumpsys input_method");
        boolean shown = dump.contains("mInputShown=true");
        out.put("imeServiceShown", shown);
        out.put("imeInputViewShown", dump.contains("mIsInputViewShown=true"));
        // What is on screen: the page's bottom edge on the display, wherever
        // the resize happened (the WebView view or only its viewport). With no
        // keyboard the edge-to-edge page reaches the screen's bottom.
        double innerHeight = Double.parseDouble(evalRaw("innerHeight"));
        double pageBottomPx = out.getInt("webViewTopPx") + innerHeight * out.getDouble("density");
        double keyboardCss = (out.getInt("decorHeightPx") - pageBottomPx) / out.getDouble("density");
        out.put("pageBottomPx", pageBottomPx);
        out.put("innerHeight", innerHeight);
        out.put("keyboardCss", keyboardCss);
        // Up: the service says so AND a keyboard-sized band (the scripted one
        // is 220dp) is gone from the page. Closed: neither.
        out.put("imeVisible", shown && keyboardCss > 150);
        // (< 60: the 48dp navigation bar may stay outside the page.)
        out.put("imeClosed", !shown && keyboardCss < 60);
        return out;
    }

    /**
     * Closes the soft keyboard the way a user does: hide it, and if the
     * system still reports it shown, press Back once (the IME consumes Back
     * while it is up, so this never navigates the app).
     */
    private void hideKeyboard() throws Exception {
        JSONObject state = nativeState();
        if (!state.getBoolean("imeServiceShown") && state.getDouble("keyboardCss") > 8) {
            // A keyboard panel is on screen that the input method service
            // does not count as shown (seen on API 35 right after attach): a
            // hide request is a no-op then. Show it properly first, so the
            // hide below really takes it down.
            focusTerminalForIme();
            awaitImeVisible(true);
        }
        scenario.onActivity(activity -> {
            WebView webView = activity.getBridge().getWebView();
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
        });
        awaitImeVisible(false);
    }

    private void awaitImeVisible(boolean visible) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 15_000;
        JSONObject state = nativeState();
        while (SystemClock.uptimeMillis() < deadline) {
            state = nativeState();
            if (state.getBoolean(visible ? "imeVisible" : "imeClosed")) {
                // Let the WebView resize and the terminal refit settle.
                SystemClock.sleep(1_200);
                return;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("soft keyboard never became " + (visible ? "visible" : "hidden") + ": " + state);
    }
}
