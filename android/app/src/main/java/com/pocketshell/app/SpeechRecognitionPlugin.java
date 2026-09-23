package com.pocketshell.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.Locale;

/** Direct adapter for Android SpeechRecognizer. Transcript and draft policy stay in TypeScript. */
@CapacitorPlugin(
        name = "SpeechRecognition",
        permissions = {@Permission(alias = "microphone", strings = {Manifest.permission.RECORD_AUDIO})})
public final class SpeechRecognitionPlugin extends Plugin {
    private static final long RESTART_DELAY_MS = 300;
    private static final long STOP_TIMEOUT_MS = 2_000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private String activeRequestId;
    private String activeLanguageTag;
    private boolean stopRequested;
    private Runnable scheduledRestart;
    private Runnable stopTimeout;

    @PluginMethod
    public void getCapabilities(PluginCall call) {
        boolean microphonePermission = getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        call.resolve(new JSObject()
                .put("speechRecognitionAvailable", SpeechRecognizer.isRecognitionAvailable(getContext()))
                .put("microphonePermissionGranted", microphonePermission));
    }

    @PluginMethod
    public void startDictation(PluginCall call) {
        String requestId = call.getString("requestId");
        if (requestId == null || requestId.trim().isEmpty()) {
            call.reject("Dictation request ID is required.", "DICTATION_INVALID_REQUEST");
            return;
        }
        String languageTag = call.getString("languageTag", Locale.getDefault().toLanguageTag());
        mainHandler.post(() -> beginDictation(call, requestId, languageTag));
    }

    @PluginMethod
    public void stopDictation(PluginCall call) {
        String requestId = call.getString("requestId");
        mainHandler.post(() -> {
            if (activeRequestId == null || !activeRequestId.equals(requestId)) {
                call.resolve(new JSObject().put("requestId", requestId).put("stopped", false));
                return;
            }
            stopRequested = true;
            cancelScheduledRestart();
            if (recognizer == null) {
                finishDictation();
            } else {
                try {
                    recognizer.stopListening();
                    scheduleStopTimeout();
                } catch (RuntimeException error) {
                    finishDictation();
                }
            }
            call.resolve(new JSObject().put("requestId", requestId).put("stopped", true));
        });
    }

    @PermissionCallback
    private void microphonePermissionResult(PluginCall call) {
        String requestId = call == null ? null : call.getString("requestId");
        String languageTag = call == null ? null : call.getString("languageTag", Locale.getDefault().toLanguageTag());
        if (call == null || requestId == null || !requestId.equals(activeRequestId) || stopRequested) {
            if (call != null) call.reject("Dictation was cancelled before microphone access was granted.", "DICTATION_CANCELLED");
            finishDictation();
            return;
        }
        if (getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            call.reject("Microphone permission was denied.", "MICROPHONE_PERMISSION_DENIED");
            emit("error", requestId, null, "permission-denied");
            finishDictation();
            return;
        }
        startRecognizer(call, requestId, languageTag);
    }

    @Override
    protected void handleOnDestroy() {
        mainHandler.post(this::finishDictation);
    }

    private void beginDictation(PluginCall call, String requestId, String languageTag) {
        if (activeRequestId != null) {
            call.reject("Another dictation request is already active.", "DICTATION_ALREADY_ACTIVE");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(getContext())) {
            call.reject("Android speech recognition is not available on this device.", "SPEECH_RECOGNIZER_UNAVAILABLE");
            return;
        }
        activeRequestId = requestId;
        activeLanguageTag = languageTag == null || languageTag.trim().isEmpty()
                ? Locale.getDefault().toLanguageTag()
                : languageTag;
        stopRequested = false;
        if (getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecognizer(call, requestId, activeLanguageTag);
            return;
        }
        requestPermissionForAlias("microphone", call, "microphonePermissionResult");
    }

    private void startRecognizer(PluginCall call, String requestId, String languageTag) {
        if (!requestId.equals(activeRequestId) || stopRequested) {
            call.reject("Dictation was cancelled.", "DICTATION_CANCELLED");
            finishDictation();
            return;
        }
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(getContext());
            recognizer.setRecognitionListener(new DictationListener(requestId));
            recognizer.startListening(recognizerIntent(languageTag));
            call.resolve(new JSObject().put("requestId", requestId).put("started", true));
            emit("started", requestId, null, null);
        } catch (RuntimeException error) {
            finishDictation();
            call.reject("Android speech recognition could not start.", "SPEECH_RECOGNIZER_START_FAILED", error);
        }
    }

    private Intent recognizerIntent(String languageTag) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        return intent;
    }

    private final class DictationListener implements RecognitionListener {
        private final String requestId;

        DictationListener(String requestId) {
            this.requestId = requestId;
        }

        @Override public void onReadyForSpeech(Bundle params) { emit("ready", requestId, null, null); }
        @Override public void onBeginningOfSpeech() { emit("listening", requestId, null, null); }
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() { emit("processing", requestId, null, null); }

        @Override
        public void onError(int error) {
            if (!requestId.equals(activeRequestId)) return;
            if (stopRequested) {
                finishDictation();
            } else if (isEndpointingError(error)) {
                scheduleRestart(requestId);
            } else {
                emit("error", requestId, null, errorCode(error));
                finishDictation();
            }
        }

        @Override
        public void onResults(Bundle results) {
            if (!requestId.equals(activeRequestId)) return;
            String text = firstResult(results);
            if (!text.isEmpty()) emit("result", requestId, text, null);
            if (stopRequested) finishDictation();
            else scheduleRestart(requestId);
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

    private void scheduleRestart(String requestId) {
        cancelScheduledRestart();
        scheduledRestart = () -> {
            scheduledRestart = null;
            if (!requestId.equals(activeRequestId) || stopRequested || recognizer == null) return;
            try {
                recognizer.startListening(recognizerIntent(activeLanguageTag));
            } catch (RuntimeException error) {
                emit("error", requestId, null, "recognizer-restart-failed");
                finishDictation();
            }
        };
        mainHandler.postDelayed(scheduledRestart, RESTART_DELAY_MS);
    }

    private void scheduleStopTimeout() {
        if (stopTimeout != null) mainHandler.removeCallbacks(stopTimeout);
        stopTimeout = this::finishDictation;
        mainHandler.postDelayed(stopTimeout, STOP_TIMEOUT_MS);
    }

    private void cancelScheduledRestart() {
        if (scheduledRestart != null) mainHandler.removeCallbacks(scheduledRestart);
        scheduledRestart = null;
    }

    private void finishDictation() {
        cancelScheduledRestart();
        if (stopTimeout != null) mainHandler.removeCallbacks(stopTimeout);
        stopTimeout = null;
        SpeechRecognizer current = recognizer;
        recognizer = null;
        String requestId = activeRequestId;
        activeRequestId = null;
        activeLanguageTag = null;
        stopRequested = false;
        if (current != null) {
            try {
                current.cancel();
            } catch (RuntimeException ignored) {}
            current.destroy();
        }
        if (requestId != null) emit("stopped", requestId, null, null);
    }

    private void emit(String type, String requestId, String text, String code) {
        if (requestId == null) return;
        JSObject event = new JSObject().put("requestId", requestId).put("type", type);
        if (text != null) event.put("text", text);
        if (code != null) event.put("code", code);
        notifyListeners("dictationEvent", event);
    }
}
