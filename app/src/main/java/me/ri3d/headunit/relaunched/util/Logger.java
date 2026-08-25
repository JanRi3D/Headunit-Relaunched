package me.ri3d.headunit.relaunched.util;

import android.util.Log;

/**
 * Thin wrapper over android.util.Log so the whole app has one tag and one
 * level switch. Set LEVEL to Log.WARN on a slow head unit -- logcat writes are
 * synchronous kernel calls and 30fps video logging will visibly cost you frames.
 */
public final class Logger {

    public static final String TAG = "HU";

    /**
     * Log.VERBOSE .. Log.ERROR. Anything below this is dropped without formatting.
     *
     * INFO by default, not DEBUG: at DEBUG every non-media message logs a line
     * and every dropped video frame logs another, and on the hardware this app
     * targets those writes cost frames. Raise it from a debugger or a one-line
     * edit when you are chasing something.
     */
    public static int LEVEL = Log.INFO;

    private Logger() {}

    public static boolean isDebug() { return LEVEL <= Log.DEBUG; }

    public static void v(String msg) { if (LEVEL <= Log.VERBOSE) Log.v(TAG, msg); }
    public static void d(String msg) { if (LEVEL <= Log.DEBUG) Log.d(TAG, msg); }
    public static void i(String msg) { if (LEVEL <= Log.INFO) Log.i(TAG, msg); }
    public static void w(String msg) { if (LEVEL <= Log.WARN) Log.w(TAG, msg); }
    public static void e(String msg) { if (LEVEL <= Log.ERROR) Log.e(TAG, msg); }

    public static void e(String msg, Throwable t) { if (LEVEL <= Log.ERROR) Log.e(TAG, msg, t); }
    public static void w(String msg, Throwable t) { if (LEVEL <= Log.WARN) Log.w(TAG, msg, t); }
}
