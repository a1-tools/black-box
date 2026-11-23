package top.niunaijun.blackbox.utils;

import android.content.Context;
import android.os.Process;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import top.niunaijun.blackbox.BlackBoxCore;

/**
 * Cross-process, on-disk diagnostics for virtual app startup.
 *
 * The virtual framework runs the stub activities in separate processes (:p0 .. :p49) and the
 * package service in :black. Static in-memory state is therefore invisible to the UI process,
 * and on restricted containers (HarmonyOS / APK containers) logcat is unavailable, so a crash
 * in a stub process used to be a complete black box: a white window followed by a silent exit.
 *
 * All processes of one APK share the same private files directory, so writing here is the only
 * reliable channel between the stub and the host UI. Everything in this class is best-effort:
 * a diagnostics failure must never be the cause of a crash.
 */
public final class BBDiag {
    public static final String TAG = "BBDiag";
    private static final String DIR = "bb_diag";
    private static final String TRACE = "trace.log";
    private static final String STAGE = "stage.txt";
    private static final String CRASH = "crash.txt";
    private static final long MAX_TRACE = 256 * 1024;

    private static volatile Context sContext;
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private BBDiag() {
    }

    private static Context context() {
        Context ctx = sContext;
        if (ctx == null) {
            try {
                ctx = BlackBoxCore.getContext();
            } catch (Throwable ignored) {
            }
        }
        return ctx;
    }

    private static File dir() {
        Context ctx = context();
        if (ctx == null) return null;
        try {
            File d = new File(ctx.getFilesDir(), DIR);
            if (!d.exists()) d.mkdirs();
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String stamp() {
        synchronized (TS) {
            return TS.format(new Date());
        }
    }

    /** Append a timestamped, pid-tagged line to the shared trace log. */
    public static void trace(String tag, String msg) {
        synchronized (LOCK) {
            File d = dir();
            if (d == null) return;
            File f = new File(d, TRACE);
            try {
                if (f.length() > MAX_TRACE) {
                    // Keep the most recent half; a full log is of little use on a phone screen.
                    byte[] all = readAll(f);
                    int keep = Math.min(all.length, (int) (MAX_TRACE / 2));
                    try (FileOutputStream os = new FileOutputStream(f, false)) {
                        os.write(all, all.length - keep, keep);
                    } catch (Throwable ignored) {
                    }
                }
                try (FileOutputStream os = new FileOutputStream(f, true);
                     OutputStreamWriter w = new OutputStreamWriter(os, "UTF-8")) {
                    w.write("[" + stamp() + "][p" + Process.myPid() + "]["
                            + (tag == null ? "-" : tag) + "] " + String.valueOf(msg) + "\n");
                    w.flush();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** Overwrite the "last known stage". Survives a crash so the UI can tell where it died. */
    public static void stage(String stage) {
        synchronized (LOCK) {
            File d = dir();
            if (d == null) return;
            try (FileOutputStream os = new FileOutputStream(new File(d, STAGE), false);
                 OutputStreamWriter w = new OutputStreamWriter(os, "UTF-8")) {
                w.write("[" + stamp() + "][p" + Process.myPid() + "] "
                        + String.valueOf(stage) + "\n");
                w.flush();
            } catch (Throwable ignored) {
            }
        }
        trace("stage", stage);
    }

    /** Persist a full crash report, including the stage and the cause chain. */
    public static void crash(Thread thread, Throwable e) {
        synchronized (LOCK) {
            File d = dir();
            if (d == null) return;
            StringBuilder sb = new StringBuilder();
            sb.append("=== VIRTUAL PROCESS CRASH ===\n");
            sb.append("time: ").append(stamp()).append("\n");
            sb.append("pid: ").append(Process.myPid()).append("\n");
            sb.append("thread: ").append(thread == null ? "unknown" : thread.getName()).append("\n");
            try {
                sb.append("stage: ").append(readTail(new File(d, STAGE), 512).trim()).append("\n");
            } catch (Throwable ignored) {
            }
            sb.append("throwable: ").append(String.valueOf(e)).append("\n");
            sb.append("=== STACK ===\n");
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                pw.flush();
                sb.append(sw.toString());
                Throwable cause = e == null ? null : e.getCause();
                int depth = 0;
                while (cause != null && depth++ < 8) {
                    sb.append("--- Caused by: ").append(cause).append(" ---\n");
                    StringWriter cw = new StringWriter();
                    PrintWriter cpw = new PrintWriter(cw);
                    cause.printStackTrace(cpw);
                    cpw.flush();
                    sb.append(cw.toString());
                    cause = cause.getCause();
                }
            } catch (Throwable t) {
                sb.append("(stack unavailable: ").append(t).append(")\n");
            }
            sb.append("=== END ===\n");
            try (FileOutputStream os = new FileOutputStream(new File(d, CRASH), false);
                 OutputStreamWriter w = new OutputStreamWriter(os, "UTF-8")) {
                w.write(sb.toString());
                w.flush();
            } catch (Throwable ignored) {
            }
        }
        trace("crash", e == null ? "unknown" : String.valueOf(e));
    }

    /**
     * Install a global uncaught-exception handler that writes the crash to disk before
     * delegating to the previous handler. Called from Application.attachBaseContext so it is
     * installed in EVERY process of the APK, including the stub processes.
     */
    public static void installCrashHandler(Context ctx) {
        if (ctx != null) sContext = ctx.getApplicationContext() == null
                ? ctx : ctx.getApplicationContext();
        try {
            Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
            if (old instanceof DiagCrashHandler) return;
            Thread.setDefaultUncaughtExceptionHandler(new DiagCrashHandler(old));
        } catch (Throwable ignored) {
        }
    }

    private static final class DiagCrashHandler implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler mOld;

        DiagCrashHandler(Thread.UncaughtExceptionHandler old) {
            mOld = old;
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            crash(t, e);
            if (mOld != null) {
                mOld.uncaughtException(t, e);
            }
        }
    }

    /** Clear the previous crash so the next launch starts with a clean slate. */
    public static void clearCrash() {
        synchronized (LOCK) {
            File d = dir();
            if (d == null) return;
            try {
                new File(d, CRASH).delete();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Timestamp of the persisted crash, or 0 when there is none. Used to auto-show it once. */
    public static long lastCrashTime() {
        File d = dir();
        if (d == null) return 0L;
        try {
            File f = new File(d, CRASH);
            return f.exists() ? f.lastModified() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** Short, screenshot-friendly crash report for the automatic post-crash dialog. */
    public static String crashReport() {
        File d = dir();
        if (d == null) return "diagDir=UNAVAILABLE";
        StringBuilder sb = new StringBuilder();
        sb.append("lastStage=").append(readTail(new File(d, STAGE), 512).trim()).append("\n\n");
        String crash = readTail(new File(d, CRASH), 6000).trim();
        sb.append(crash.isEmpty() ? "(no crash recorded)" : crash);
        return sb.toString();
    }

    /** A compact, screenshot-friendly digest appended to on-screen diagnostic reports. */
    public static String digest() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n--- stub process log (shared across processes) ---\n");
        File d = dir();
        if (d == null) {
            sb.append("diagDir=UNAVAILABLE\n");
            return sb.toString();
        }
        sb.append("diagDir=").append(d.getAbsolutePath()).append("\n");
        String stage = readTail(new File(d, STAGE), 512).trim();
        sb.append("lastStage=").append(stage.isEmpty() ? "none" : stage).append("\n");
        String crash = readTail(new File(d, CRASH), 3500).trim();
        sb.append("lastCrash=").append(crash.isEmpty() ? "none" : crash).append("\n");
        String trace = readTail(new File(d, TRACE), 4500).trim();
        sb.append("traceTail=\n").append(trace.isEmpty() ? "(empty)" : trace).append("\n");
        return sb.toString();
    }

    private static byte[] readAll(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return buf;
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    private static String readTail(File f, int maxBytes) {
        if (!f.exists()) return "";
        try {
            byte[] all = readAll(f);
            int start = Math.max(0, all.length - maxBytes);
            return new String(all, start, all.length - start, "UTF-8");
        } catch (Throwable t) {
            return "(read error " + t + ")";
        }
    }
}
