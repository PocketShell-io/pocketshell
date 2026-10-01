package com.pocketshell.app.ime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * #2952: a real Android input method the journeys drive by script.
 *
 * <p>Gboard does not type into a WebView with key events. It edits the focused
 * editor through the framework {@link InputConnection}: it grows a composing
 * span letter by letter, commits the word when space is tapped, replaces a word
 * with an autocorrection, re-opens a committed word as composing text when the
 * user backspaces into it, deletes surrounding text, and sends a few real key
 * events (Enter, and Delete on an empty field). This service makes exactly those
 * calls, in the order a script lists them, against whatever editor the
 * framework has bound — so the page receives the same composition and input
 * events a phone keyboard produces, through the same Chromium IME adapter.
 *
 * <p>Protocol (test APK only; the service is never in the app APK):
 * <ul>
 *   <li>{@link #ACTION_RUN} with {@code token}, {@code replyTo} (a package)
 *       and {@code script} (a JSON array of ops) runs the ops on the main
 *       thread, {@code stepMillis} apart, then broadcasts {@link #ACTION_DONE}
 *       to {@code replyTo} with the same token, {@code ok}, {@code error},
 *       the bound editor's package and input type, and its text before the
 *       cursor (diagnostics only).</li>
 *   <li>Ops: {@code compose text} (setComposingText), {@code commit text}
 *       (commitText), {@code finish} (finishComposingText), {@code recompose n}
 *       (setComposingRegion over the n chars before the cursor),
 *       {@code delete before after} (deleteSurroundingText), {@code key code}
 *       (sendDownUpKeyEvents), {@code pause ms}.</li>
 * </ul>
 */
public class ScriptedTestIme extends InputMethodService {
    public static final String ACTION_RUN = "com.pocketshell.test.IME_SCRIPT_RUN";
    public static final String ACTION_DONE = "com.pocketshell.test.IME_SCRIPT_DONE";
    private static final long DEFAULT_STEP_MILLIS = 60;

    private final Handler main = new Handler(Looper.getMainLooper());
    private BroadcastReceiver receiver;

    @Override
    public void onCreate() {
        super.onCreate();
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                run(intent);
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_RUN);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    @Override
    public void onDestroy() {
        if (receiver != null) unregisterReceiver(receiver);
        receiver = null;
        super.onDestroy();
    }

    /** A keyboard-sized panel, so the WebView is laid out as it is under a real keyboard. */
    @Override
    public View onCreateInputView() {
        FrameLayout panel = new FrameLayout(this);
        panel.setBackgroundColor(Color.DKGRAY);
        int height = Math.round(220 * getResources().getDisplayMetrics().density);
        panel.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        panel.setMinimumHeight(height);
        return panel;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    private void run(Intent intent) {
        String token = intent.getStringExtra("token");
        String replyTo = intent.getStringExtra("replyTo");
        long step = intent.getLongExtra("stepMillis", DEFAULT_STEP_MILLIS);
        JSONArray script;
        try {
            script = new JSONArray(intent.getStringExtra("script"));
        } catch (JSONException | NullPointerException error) {
            reply(token, replyTo, "bad script: " + error);
            return;
        }
        runStep(script, 0, step, token, replyTo);
    }

    private void runStep(JSONArray script, int index, long step, String token, String replyTo) {
        if (index >= script.length()) {
            // Let the last edit settle in the editor before reporting.
            main.postDelayed(() -> reply(token, replyTo, null), step);
            return;
        }
        long delay = step;
        try {
            InputConnection connection = getCurrentInputConnection();
            if (connection == null) {
                reply(token, replyTo, "no input connection is bound (op " + index + ")");
                return;
            }
            JSONObject op = script.getJSONObject(index);
            String name = op.getString("op");
            switch (name) {
                case "compose":
                    connection.setComposingText(op.getString("text"), 1);
                    break;
                case "commit":
                    connection.commitText(op.getString("text"), 1);
                    break;
                case "finish":
                    connection.finishComposingText();
                    break;
                case "recompose": {
                    int length = op.getInt("n");
                    ExtractedText text = connection.getExtractedText(new ExtractedTextRequest(), 0);
                    if (text == null) {
                        reply(token, replyTo, "editor returned no extracted text for recompose (op " + index + ")");
                        return;
                    }
                    int cursor = text.startOffset + text.selectionStart;
                    connection.setComposingRegion(cursor - length, cursor);
                    break;
                }
                case "delete":
                    connection.deleteSurroundingText(op.getInt("before"), op.optInt("after", 0));
                    break;
                case "key":
                    sendDownUpKeyEvents(op.getInt("code"));
                    break;
                case "pause":
                    delay = op.getLong("ms");
                    break;
                default:
                    reply(token, replyTo, "unknown op " + name);
                    return;
            }
        } catch (JSONException error) {
            reply(token, replyTo, "bad op " + index + ": " + error);
            return;
        }
        main.postDelayed(() -> runStep(script, index + 1, step, token, replyTo), delay);
    }

    private void reply(String token, String replyTo, String error) {
        Intent done = new Intent(ACTION_DONE);
        if (replyTo != null) done.setPackage(replyTo);
        done.putExtra("token", token);
        done.putExtra("ok", error == null);
        if (error != null) done.putExtra("error", error);
        EditorInfo editor = getCurrentInputEditorInfo();
        if (editor != null) {
            done.putExtra("editorPackage", editor.packageName);
            done.putExtra("editorInputType", editor.inputType);
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) {
            CharSequence before = connection.getTextBeforeCursor(200, 0);
            done.putExtra("textBeforeCursor", before == null ? null : before.toString());
        }
        done.putExtra("inputViewShown", isInputViewShown());
        sendBroadcast(done);
    }
}
