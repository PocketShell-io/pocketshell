package com.pocketshell.app.ime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.KeyEvent;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * #2952: the journey side of {@link ScriptedTestIme}. Selects it as the
 * system input method for the duration of a test, runs op scripts against the
 * editor it has bound (the focused xterm helper textarea), and puts the
 * device's previous keyboard back afterwards.
 */
public final class ScriptedIme implements AutoCloseable {
    private static final long RUN_TIMEOUT_MILLIS = 60_000;

    private final String imeId;
    private final String previousIme;
    private int sequence;

    private ScriptedIme(String imeId, String previousIme) {
        this.imeId = imeId;
        this.previousIme = previousIme;
    }

    /** Enables and selects the scripted IME; fails unless the system reports it selected. */
    public static ScriptedIme select() {
        String testPackage = InstrumentationRegistry.getInstrumentation().getContext().getPackageName();
        String id = testPackage + "/" + ScriptedTestIme.class.getName();
        String previous = shell("settings get secure default_input_method").trim();
        String enabled = shell("ime enable " + id);
        String set = shell("ime set " + id);
        long deadline = SystemClock.uptimeMillis() + 10_000;
        String current = "";
        while (SystemClock.uptimeMillis() < deadline) {
            current = shell("settings get secure default_input_method").trim();
            if (id.equals(current)) return new ScriptedIme(id, previous);
            SystemClock.sleep(100);
        }
        throw new AssertionError("could not select the scripted test IME " + id + " (enable: " + enabled.trim()
                + "; set: " + set.trim() + "; current: " + current + ")");
    }

    public String id() {
        return imeId;
    }

    /** Builds ops the way a phone keyboard edits a field (see {@link Script}). */
    public static Script script() {
        return new Script();
    }

    /**
     * Runs the ops and waits for the IME's report. Fails when no editor is
     * bound or the bound editor is not the app's.
     */
    public Bundle run(Script script, String expectedEditorPackage) throws InterruptedException {
        return run(script.ops, expectedEditorPackage, 60);
    }

    public Bundle run(JSONArray ops, String expectedEditorPackage, long stepMillis) throws InterruptedException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String token = "ime-" + SystemClock.uptimeMillis() + "-" + (++sequence);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Bundle> reply = new AtomicReference<>();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent intent) {
                if (!token.equals(intent.getStringExtra("token"))) return;
                reply.set(intent.getExtras());
                done.countDown();
            }
        };
        IntentFilter filter = new IntentFilter(ScriptedTestIme.ACTION_DONE);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        try {
            Intent run = new Intent(ScriptedTestIme.ACTION_RUN)
                    .setPackage(InstrumentationRegistry.getInstrumentation().getContext().getPackageName())
                    .putExtra("token", token)
                    .putExtra("replyTo", context.getPackageName())
                    .putExtra("stepMillis", stepMillis)
                    .putExtra("script", ops.toString());
            context.sendBroadcast(run);
            if (!done.await(RUN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new AssertionError("the scripted IME did not report back within " + RUN_TIMEOUT_MILLIS
                        + " ms (is it bound? current IME: "
                        + shell("settings get secure default_input_method").trim() + ")");
            }
        } finally {
            context.unregisterReceiver(receiver);
        }
        Bundle result = reply.get();
        if (!result.getBoolean("ok")) {
            throw new AssertionError("scripted IME failed: " + result.getString("error") + " " + describe(result));
        }
        if (expectedEditorPackage != null && !expectedEditorPackage.equals(result.getString("editorPackage"))) {
            throw new AssertionError("scripted IME typed into " + result.getString("editorPackage")
                    + ", not " + expectedEditorPackage + " " + describe(result));
        }
        return result;
    }

    public static String describe(Bundle result) {
        return "{editorPackage=" + result.getString("editorPackage")
                + ", inputType=0x" + Integer.toHexString(result.getInt("editorInputType"))
                + ", inputViewShown=" + result.getBoolean("inputViewShown")
                + ", textBeforeCursor=" + JSONObject.quote(String.valueOf(result.getString("textBeforeCursor")))
                + "}";
    }

    @Override
    public void close() {
        if (previousIme != null && !previousIme.isEmpty() && !"null".equals(previousIme)) {
            shell("ime set " + previousIme);
        }
        shell("ime disable " + imeId);
    }

    public static String shell(String command) {
        try {
            ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .executeShellCommand(command);
            StringBuilder result = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new ParcelFileDescriptor.AutoCloseInputStream(descriptor), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) result.append(line).append('\n');
            }
            return result.toString();
        } catch (Exception error) {
            throw new AssertionError("shell '" + command + "' failed: " + error, error);
        }
    }

    /**
     * An op list. {@link #typeLikeLatinIme} reproduces how AOSP LatinIME (the
     * emulator's keyboard, where #2936's duplicated letters were captured)
     * types a line: letters grow a composing word, the word is committed before
     * anything that is not a letter, digits go out as KEYCODE_0..9 key events
     * (LatinIME's sendKeyCodePoint), other symbols and space are single-char
     * commits, and Enter in a multi-line field is a committed newline.
     */
    public static final class Script {
        final JSONArray ops = new JSONArray();

        public Script compose(String text) {
            return add("compose", "text", text);
        }

        public Script commit(String text) {
            return add("commit", "text", text);
        }

        public Script finish() {
            return add("finish", null, null);
        }

        /** setComposingRegion over the {@code n} characters before the cursor. */
        public Script recompose(int n) {
            try {
                ops.put(new JSONObject().put("op", "recompose").put("n", n));
            } catch (JSONException error) {
                throw new AssertionError(error);
            }
            return this;
        }

        public Script deleteBefore(int n) {
            try {
                ops.put(new JSONObject().put("op", "delete").put("before", n).put("after", 0));
            } catch (JSONException error) {
                throw new AssertionError(error);
            }
            return this;
        }

        /** A key event sent by the IME (InputMethodService.sendDownUpKeyEvents). */
        public Script key(int keyCode) {
            try {
                ops.put(new JSONObject().put("op", "key").put("code", keyCode));
            } catch (JSONException error) {
                throw new AssertionError(error);
            }
            return this;
        }

        public Script pause(long millis) {
            try {
                ops.put(new JSONObject().put("op", "pause").put("ms", millis));
            } catch (JSONException error) {
                throw new AssertionError(error);
            }
            return this;
        }

        /** Letters compose one at a time, as a keyboard grows its composing span. */
        public Script composeWord(String word) {
            for (int end = 1; end <= word.length(); end++) compose(word.substring(0, end));
            return this;
        }

        public Script typeLikeLatinIme(String text) {
            StringBuilder word = new StringBuilder();
            for (int index = 0; index < text.length(); index++) {
                char c = text.charAt(index);
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                    word.append(c);
                    compose(word.toString());
                    continue;
                }
                if (word.length() > 0) {
                    commit(word.toString());
                    word.setLength(0);
                }
                if (c >= '0' && c <= '9') {
                    key(KeyEvent.KEYCODE_0 + (c - '0'));
                } else {
                    commit(String.valueOf(c));
                }
            }
            if (word.length() > 0) commit(word.toString());
            return this;
        }

        public JSONArray ops() {
            return ops;
        }

        private Script add(String op, String key, String value) {
            try {
                JSONObject entry = new JSONObject().put("op", op);
                if (key != null) entry.put(key, value);
                ops.put(entry);
            } catch (JSONException error) {
                throw new AssertionError(error);
            }
            return this;
        }
    }
}
