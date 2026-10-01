package com.pocketshell.app;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Bounded file access to the reports {@link NativeCrashRecorder} wrote. JS
 * owns parsing, redaction, ordering and every delete decision.
 */
@CapacitorPlugin(name = "NativeCrashReports")
public final class NativeCrashReportsPlugin extends Plugin {

    @PluginMethod
    public void list(PluginCall call) {
        try {
            JSArray reports = new JSArray();
            for (File file : reportFiles()) {
                if (Files.isSymbolicLink(file.toPath()) || !file.isFile()) continue;
                byte[] bytes = Files.readAllBytes(file.toPath());
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (text.length() > NativeCrashRecorder.MAX_REPORT_CHARS) {
                    text = text.substring(0, NativeCrashRecorder.MAX_REPORT_CHARS) + "\n[report truncated]\n";
                }
                String name = file.getName();
                reports.put(new JSObject()
                        .put("id", name.substring(0, name.length() - 4))
                        .put("fileName", name)
                        .put("text", text));
            }
            call.resolve(new JSObject().put("reports", reports));
        } catch (IOException | RuntimeException error) {
            call.reject("Native crash reports could not be read.", "NATIVE_CRASH_READ_FAILED", error);
        }
    }

    @PluginMethod
    public void remove(PluginCall call) {
        String id = call.getString("id");
        if (id == null || !id.matches(NativeCrashRecorder.ID_PATTERN)) {
            call.reject("The crash report id is invalid.", "INVALID_ARGUMENT");
            return;
        }
        File file = new File(NativeCrashRecorder.directory(getContext()), id + ".txt");
        boolean removed = file.isFile() && !Files.isSymbolicLink(file.toPath()) && file.delete();
        call.resolve(new JSObject().put("id", id).put("removed", removed));
    }

    @PluginMethod
    public void clear(PluginCall call) {
        int removed = 0;
        for (File file : reportFiles()) {
            if (!Files.isSymbolicLink(file.toPath()) && file.delete()) removed += 1;
        }
        call.resolve(new JSObject().put("removed", removed));
    }

    private File[] reportFiles() {
        File[] files = NativeCrashRecorder.directory(getContext())
                .listFiles((candidate) -> NativeCrashRecorder.isReportFile(candidate.getName()));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        return files;
    }
}
