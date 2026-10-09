package com.pocketshell.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** One-turn adapter for Android SpeechRecognizer. Dictation policy stays in TypeScript. */
@CapacitorPlugin(
        name = "SpeechRecognition",
        permissions = {@Permission(alias = "microphone", strings = {Manifest.permission.RECORD_AUDIO})})
public final class SpeechRecognitionPlugin extends Plugin {
    private static final long STOP_TIMEOUT_MS = 2_000;
    static final long DEFAULT_SILENCE_WINDOW_MS = SpeechRecognitionOptions.DEFAULT_SILENCE_WINDOW_MS;
    static final long MIN_SILENCE_WINDOW_MS = SpeechRecognitionOptions.MIN_SILENCE_WINDOW_MS;
    static final long MAX_SILENCE_WINDOW_MS = SpeechRecognitionOptions.MAX_SILENCE_WINDOW_MS;
    static final long MINIMUM_RECOGNITION_LENGTH_MS = SpeechRecognitionOptions.MINIMUM_SPEECH_LENGTH_MS;
    private static final Pattern LANGUAGE_TAG_PATTERN = Pattern.compile("^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private String activeRequestId;
    private String lastRequestIdForTest;
    private String activeLanguageTag;
    private long activeSilenceWindowMs = SpeechRecognitionOptions.DEFAULT_SILENCE_WINDOW_MS;
    private boolean activeTestMode;
    private boolean stopRequested;
    private Runnable stopTimeout;

    @PluginMethod
    public void getCapabilities(PluginCall call) {
        boolean microphonePermission = getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        call.resolve(new JSObject()
                .put("speechRecognitionAvailable", SpeechRecognizer.isRecognitionAvailable(getContext()))
                .put("microphonePermissionGranted", microphonePermission));
    }

    /** Starts exactly one recognizer turn. A later turn gets a new request ID and native call. */
    @PluginMethod
    public void startDictation(PluginCall call) {
        String requestId = call.getString("requestId");
        if (requestId == null || requestId.trim().isEmpty()) {
            call.reject("Recognition request ID is required.", "DICTATION_INVALID_REQUEST");
            return;
        }
        String languageTag = call.getString("languageTag");
        long silenceWindowMs = call.getData() == null
                ? SpeechRecognitionOptions.DEFAULT_SILENCE_WINDOW_MS
                : call.getData().optLong("silenceWindowMs", SpeechRecognitionOptions.DEFAULT_SILENCE_WINDOW_MS);
        boolean testMode = call.getBoolean("testMode", false) && isDebuggableBuild();
        mainHandler.post(() -> beginRecognitionTurn(call, requestId, languageTag, silenceWindowMs, testMode));
    }

    /** Ends the active turn and waits for SpeechRecognizer's final callback. */
    @PluginMethod
    public void stopDictation(PluginCall call) {
        String requestId = call.getString("requestId");
        mainHandler.post(() -> {
            if (activeRequestId == null || !activeRequestId.equals(requestId)) {
                call.resolve(new JSObject().put("requestId", requestId).put("stopped", false));
                return;
            }
            stopRequested = true;
            if (activeTestMode) {
                call.resolve(new JSObject().put("requestId", requestId).put("stopped", true));
                return;
            }
            if (recognizer == null) {
                emit("error", requestId, null, "recognizer-stop-unavailable");
                finishTurn(requestId, true);
            } else {
                try {
                    recognizer.stopListening();
                    scheduleStopTimeout(requestId);
                } catch (RuntimeException error) {
                    emit("error", requestId, null, "recognizer-stop-failed");
                    finishTurn(requestId, true);
                }
            }
            call.resolve(new JSObject().put("requestId", requestId).put("stopped", true));
        });
    }

    /** Abandons one turn immediately; JavaScript invalidates the matching request ID first. */
    @PluginMethod
    public void cancelDictation(PluginCall call) {
        String requestId = call.getString("requestId");
        if (requestId == null || requestId.trim().isEmpty()) {
            call.reject("Recognition request ID is required.", "DICTATION_INVALID_REQUEST");
            return;
        }
        mainHandler.post(() -> {
            boolean cancelled = requestId.equals(activeRequestId);
            if (cancelled) finishTurn(requestId, true);
            call.resolve(new JSObject().put("requestId", requestId).put("cancelled", cancelled));
        });
    }

    /** Deterministic event injection for the packaged debug journey only. */
    @PluginMethod
    public void injectTestDictationEvent(PluginCall call) {
        if ((getContext().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            call.reject("Test dictation events are available only in debug builds.", "DICTATION_TEST_ONLY");
            return;
        }
        String type = call.getString("type");
        String text = call.getString("text");
        String requestedCode = call.getString("code");
        String requestId = call.getString("requestId");
        mainHandler.post(() -> {
            String eventRequestId = requestId == null || requestId.trim().isEmpty()
                    ? (activeRequestId == null ? lastRequestIdForTest : activeRequestId)
                    : requestId;
            if (eventRequestId == null || eventRequestId.trim().isEmpty()) {
                call.reject("There is no recognition request to inject an event for.", "DICTATION_TEST_NO_REQUEST");
                return;
            }
            if ("partial".equals(type)) {
                emit("partial", eventRequestId, text, null);
            } else if ("result".equals(type)) {
                emit("result", eventRequestId, text, null);
                if (eventRequestId.equals(activeRequestId)) finishTurn(eventRequestId, false);
            } else if ("recoverable".equals(type)) {
                emit("recoverable", eventRequestId, null, requestedCode == null ? "no-match" : requestedCode);
                if (eventRequestId.equals(activeRequestId)) finishTurn(eventRequestId, false);
            } else if ("audio".equals(type)) {
                if (!"sound".equals(requestedCode) && !"silence".equals(requestedCode)) {
                    call.reject("Test audio events need code sound or silence.", "DICTATION_TEST_INVALID_EVENT");
                    return;
                }
                emit("audio", eventRequestId, null, requestedCode);
            } else if ("error".equals(type)) {
                emit("error", eventRequestId, null,
                        requestedCode == null ? (text == null ? "test-recognition-error" : text) : requestedCode);
                if (eventRequestId.equals(activeRequestId)) finishTurn(eventRequestId, false);
            } else {
                call.reject("Unsupported test recognition event.", "DICTATION_TEST_INVALID_EVENT");
                return;
            }
            JSObject result = new JSObject().put("requestId", eventRequestId).put("emitted", true);
            if (activeTestMode && eventRequestId.equals(activeRequestId)) {
                result.put("silenceWindowMs", activeSilenceWindowMs);
                if (activeLanguageTag != null) result.put("languageTag", activeLanguageTag);
            }
            call.resolve(result);
        });
    }

    @PermissionCallback
    private void microphonePermissionResult(PluginCall call) {
        String requestId = call == null ? null : call.getString("requestId");
        boolean testMode = call != null && call.getBoolean("testMode", false) && isDebuggableBuild();
        if (call == null || requestId == null || !requestId.equals(activeRequestId) || stopRequested) {
            if (call != null) call.reject("Recognition was cancelled before microphone access was granted.", "DICTATION_CANCELLED");
            if (requestId != null) finishTurn(requestId, true);
            return;
        }
        if (getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            emit("error", requestId, null, "permission-denied");
            call.reject("Microphone permission was denied.", "MICROPHONE_PERMISSION_DENIED");
            finishTurn(requestId, true);
            return;
        }
        startRecognizer(call, requestId, activeLanguageTag, activeSilenceWindowMs, testMode);
    }

    @Override
    protected void handleOnDestroy() {
        mainHandler.post(() -> {
            if (activeRequestId != null) finishTurn(activeRequestId, true);
        });
    }

    private void beginRecognitionTurn(PluginCall call, String requestId, String languageTag,
            long silenceWindowMs, boolean testMode) {
        if (activeRequestId != null) {
            call.reject("Another recognition turn is already active.", "DICTATION_ALREADY_ACTIVE");
            return;
        }
        if (!testMode && !SpeechRecognizer.isRecognitionAvailable(getContext())) {
            call.reject("Android speech recognition is not available on this device.", "SPEECH_RECOGNIZER_UNAVAILABLE");
            return;
        }
        activeRequestId = requestId;
        lastRequestIdForTest = requestId;
        activeLanguageTag = normalizeLanguageTag(languageTag, Locale.getDefault());
        activeSilenceWindowMs = SpeechRecognitionOptions.clampSilenceWindowMs(silenceWindowMs);
        activeTestMode = testMode;
        stopRequested = false;
        if (getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecognizer(call, requestId, activeLanguageTag, activeSilenceWindowMs, testMode);
            return;
        }
        requestPermissionForAlias("microphone", call, "microphonePermissionResult");
    }

    private void startRecognizer(PluginCall call, String requestId, String languageTag,
            long silenceWindowMs, boolean testMode) {
        if (!requestId.equals(activeRequestId) || stopRequested) {
            call.reject("Recognition was cancelled.", "DICTATION_CANCELLED");
            finishTurn(requestId, true);
            return;
        }
        if (testMode) {
            call.resolve(new JSObject().put("requestId", requestId).put("started", true));
            return;
        }
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(getContext());
            recognizer.setRecognitionListener(new DictationListener(requestId));
            recognizer.startListening(recognizerIntent(languageTag, silenceWindowMs));
            call.resolve(new JSObject().put("requestId", requestId).put("started", true));
        } catch (RuntimeException error) {
            emit("error", requestId, null, "recognizer-start-failed");
            call.reject("Android speech recognition could not start.", "SPEECH_RECOGNIZER_START_FAILED", error);
            finishTurn(requestId, true);
        }
    }

    private Intent recognizerIntent(String languageTag, long silenceWindowMs) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        for (Map.Entry<String, Object> extra : recognizerExtras(languageTag, silenceWindowMs).entrySet()) {
            Object value = extra.getValue();
            if (value instanceof String) intent.putExtra(extra.getKey(), (String) value);
            else if (value instanceof Boolean) intent.putExtra(extra.getKey(), (Boolean) value);
            else if (value instanceof Integer) intent.putExtra(extra.getKey(), (Integer) value);
            else if (value instanceof Long) intent.putExtra(extra.getKey(), (Long) value);
        }
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getContext().getPackageName());
        return intent;
    }

    /** Build recognizer extras as plain values so Android-specific settings stay testable. */
    static Map<String, Object> recognizerExtras(String languageTag, Object rawSilenceWindowMs) {
        long silenceWindowMs = clampSilenceWindowMs(rawSilenceWindowMs);
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        String resolvedLanguage = languageTag == null ? null : normalizeLanguageTag(languageTag, Locale.getDefault());
        if (resolvedLanguage != null) extras.put(RecognizerIntent.EXTRA_LANGUAGE, resolvedLanguage);
        extras.put(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        extras.put(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        extras.put(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceWindowMs);
        extras.put(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceWindowMs);
        extras.put(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MINIMUM_RECOGNITION_LENGTH_MS);
        return Collections.unmodifiableMap(extras);
    }

    static long clampSilenceWindowMs(Object value) {
        if (!(value instanceof Number)) return DEFAULT_SILENCE_WINDOW_MS;
        double requested = ((Number) value).doubleValue();
        if (Double.isNaN(requested) || Double.isInfinite(requested)) return DEFAULT_SILENCE_WINDOW_MS;
        return SpeechRecognitionOptions.clampSilenceWindowMs(Math.round(requested));
    }

    static String normalizeLanguageTag(String value, Locale fallback) {
        if (value == null) return fallback.toLanguageTag();
        String trimmed = value.trim();
        if ("auto".equalsIgnoreCase(trimmed)) return null;
        if (trimmed.isEmpty() || trimmed.length() > 64 || !LANGUAGE_TAG_PATTERN.matcher(trimmed).matches()) {
            return fallback.toLanguageTag();
        }
        Locale parsed = Locale.forLanguageTag(trimmed);
        if (parsed.getLanguage().isEmpty() || "und".equalsIgnoreCase(parsed.toLanguageTag())) {
            return fallback.toLanguageTag();
        }
        return parsed.toLanguageTag();
    }

    private final class DictationListener implements RecognitionListener {
        private final String requestId;
        private final SpeechAudioPresence audioPresence = new SpeechAudioPresence();

        DictationListener(String requestId) {
            this.requestId = requestId;
        }

        @Override public void onReadyForSpeech(Bundle params) {}
        @Override public void onBeginningOfSpeech() {}

        /** #3062: forward only a coarse, rate-limited sound/silence flag, never the level itself. */
        @Override
        public void onRmsChanged(float rmsdB) {
            if (!requestId.equals(activeRequestId)) return;
            Boolean sound = audioPresence.onRms(rmsdB, SystemClock.uptimeMillis());
            if (sound != null) emit("audio", requestId, null, sound ? "sound" : "silence");
        }
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}

        @Override
        public void onError(int error) {
            if (!requestId.equals(activeRequestId)) return;
            if (isEndpointingError(error)) emit("recoverable", requestId, null, recoverableCode(error));
            else emit("error", requestId, null, errorCode(error));
            finishTurn(requestId, false);
        }

        @Override
        public void onResults(Bundle results) {
            if (!requestId.equals(activeRequestId)) return;
            String text = firstResult(results);
            if (text.trim().isEmpty()) emit("recoverable", requestId, null, "no-match");
            else emit("result", requestId, text, null);
            finishTurn(requestId, false);
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            if (!requestId.equals(activeRequestId)) return;
            String text = firstResult(partialResults);
            if (!text.isEmpty()) emit("partial", requestId, text, null);
        }

        @Override public void onEvent(int eventType, Bundle params) {}
    }

    private String firstResult(Bundle results) {
        if (results == null) return "";
        ArrayList<String> candidates = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return candidates == null || candidates.isEmpty() || candidates.get(0) == null ? "" : candidates.get(0);
    }

    private boolean isEndpointingError(int error) {
        return error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY;
    }

    private String recoverableCode(int error) {
        if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) return "speech-timeout";
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) return "recognizer-busy";
        return "no-match";
    }

    private String errorCode(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "audio-error";
            case SpeechRecognizer.ERROR_CLIENT: return "recognizer-client-error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "permission-denied";
            case SpeechRecognizer.ERROR_NETWORK: return "network-error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "network-timeout";
            case SpeechRecognizer.ERROR_SERVER: return "recognizer-service-error";
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED: return "recognizer-service-disconnected";
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS: return "recognizer-rate-limited";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED: return "language-not-supported";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: return "language-unavailable";
            default: return "recognizer-error-" + error;
        }
    }

    private void scheduleStopTimeout(String requestId) {
        if (stopTimeout != null) mainHandler.removeCallbacks(stopTimeout);
        stopTimeout = () -> {
            if (!requestId.equals(activeRequestId)) return;
            stopTimeout = null;
            emit("error", requestId, null, "recognizer-stop-timeout");
            finishTurn(requestId, true);
        };
        mainHandler.postDelayed(stopTimeout, STOP_TIMEOUT_MS);
    }

    private void finishTurn(String requestId, boolean cancelRecognizer) {
        if (requestId == null || !requestId.equals(activeRequestId)) return;
        if (stopTimeout != null) mainHandler.removeCallbacks(stopTimeout);
        stopTimeout = null;
        SpeechRecognizer current = recognizer;
        recognizer = null;
        activeRequestId = null;
        activeLanguageTag = null;
        activeSilenceWindowMs = SpeechRecognitionOptions.DEFAULT_SILENCE_WINDOW_MS;
        activeTestMode = false;
        stopRequested = false;
        if (current != null) {
            if (cancelRecognizer) {
                try {
                    current.cancel();
                } catch (RuntimeException ignored) {}
            }
            current.destroy();
        }
    }

    private void emit(String type, String requestId, String text, String code) {
        if (requestId == null) return;
        JSObject event = new JSObject().put("requestId", requestId).put("type", type);
        if (text != null) event.put("text", text);
        if (code != null) event.put("code", code);
        notifyListeners("dictationEvent", event);
    }

    private boolean isDebuggableBuild() {
        return (getContext().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }
}
