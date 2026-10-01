package com.pocketshell.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Records an uncaught Java/native-thread crash as a local text report, then
 * lets the platform's default handler end the process as before.
 *
 * Platform capability only: the report is written in the 0.5.x crash-report
 * text shape so the shared TypeScript policy (pocketshell-core
 * diagnosticReports.ts) parses it; listing, deleting and sharing are decided
 * in JS. Only exception class names and stack frames are written — never
 * exception messages or thread names, which can carry host names, paths or
 * credentials — so nothing sensitive crosses the bridge or reaches logcat. Reports live in a directory separate from
 * the 0.5.x `files/crash-reports/` source the installed-data import reads.
 */
public final class NativeCrashRecorder {
    public static final String DIRECTORY = "native-crash-reports";
    static final int MAX_REPORTS = 20;
    static final int MAX_REPORT_CHARS = 64 * 1024;
    static final String ID_PATTERN = "\\d{8}-\\d{6}-\\d{3}(-\\d+)?";

    private NativeCrashRecorder() {}

    /** Install once per process, chaining whatever handler was present before. */
    public static synchronized void install(Context context) {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof RecordingHandler) return;
        Context appContext = context.getApplicationContext();
        Thread.setDefaultUncaughtExceptionHandler(new RecordingHandler(appContext, current));
    }

    public static File directory(Context context) {
        return new File(context.getFilesDir(), DIRECTORY);
    }

    /** Write one report; returns its file, or null when the disk write failed. */
    public static File record(Context context, Thread thread, Throwable throwable) {
        try {
            File dir = directory(context);
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            Date now = new Date();
            SimpleDateFormat idFormat = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US);
            String base = idFormat.format(now);
            File file = new File(dir, base + ".txt");
            for (int suffix = 1; file.exists(); suffix += 1) file = new File(dir, base + "-" + suffix + ".txt");
            String text = format(context, thread, throwable, now);
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            prune(dir);
            return file;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    static String format(Context context, Thread thread, Throwable throwable, Date when) {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        String className = throwable.getClass().getSimpleName();
        StringBuilder out = new StringBuilder()
                .append("PocketShell crash report\n")
                .append("Generated: ").append(iso.format(when)).append('\n')
                .append("App version: ").append(versionName(context)).append('\n')
                .append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
                .append("Device: ").append(Build.MODEL).append('\n')
                .append("Thread: ").append(thread == null ? "unknown" : "main".equals(thread.getName()) ? "main" : "background").append('\n')
                .append('\n')
                .append("Exception summary: ").append(className.isEmpty() ? "Throwable" : className).append('\n')
                .append('\n')
                .append("Exception\n");
        appendStructure(out, throwable);
        return out.length() > MAX_REPORT_CHARS ? out.substring(0, MAX_REPORT_CHARS) + "\n[report truncated]\n" : out.toString();
    }

    /**
     * Exception class names and stack frames only, for the throwable and each
     * cause. Messages are never written: they carry host names, paths and
     * credentials ("Unable to resolve host \"prodbox\""), and this text crosses
     * the JS bridge, where debug builds echo plugin results to logcat.
     */
    static void appendStructure(StringBuilder out, Throwable throwable) {
        Throwable current = throwable;
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        boolean first = true;
        while (current != null && seen.add(current)) {
            out.append(first ? "" : "Caused by: ").append(current.getClass().getName()).append('\n');
            for (StackTraceElement frame : current.getStackTrace()) {
                out.append("\tat ").append(frame.getClassName()).append('.').append(frame.getMethodName());
                String file = frame.getFileName();
                if (file != null) {
                    out.append('(').append(file);
                    if (frame.getLineNumber() >= 0) out.append(':').append(frame.getLineNumber());
                    out.append(')');
                }
                out.append('\n');
            }
            first = false;
            current = current.getCause();
        }
    }

    private static String versionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "unknown" : info.versionName;
        } catch (Exception error) {
            return "unknown";
        }
    }

    /** Keep the newest MAX_REPORTS; a crash loop must not fill the disk. */
    static void prune(File dir) {
        File[] files = dir.listFiles((candidate) -> isReportFile(candidate.getName()));
        if (files == null || files.length <= MAX_REPORTS) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (int index = 0; index < files.length - MAX_REPORTS; index += 1) {
            //noinspection ResultOfMethodCallIgnored
            files[index].delete();
        }
    }

    static boolean isReportFile(String name) {
        return name.endsWith(".txt") && name.substring(0, name.length() - 4).matches(ID_PATTERN);
    }

    private static final class RecordingHandler implements Thread.UncaughtExceptionHandler {
        private final Context context;
        private final Thread.UncaughtExceptionHandler next;

        RecordingHandler(Context context, Thread.UncaughtExceptionHandler next) {
            this.context = context;
            this.next = next;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable throwable) {
            try {
                record(context, thread, throwable);
            } finally {
                if (next != null) next.uncaughtException(thread, throwable);
            }
        }
    }
}
