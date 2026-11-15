package com.leidian.sk5;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃现场记录。
 *
 * Java 层的未捕获异常会被写进 SharedPreferences，下次打开应用时直接弹出来并可复制。
 * native 层的崩溃抓不到这里，那种情况靠 LogRecorder 的落盘日志定位最后一步。
 */
public final class CrashGuard {

    private static final String PREFS = "sk5_crash";
    private static final String KEY_LAST = "last";
    private static final int MAX_CHARS = 20000;

    private CrashGuard() {
    }

    public static void install(final Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            record(app, thread, error);
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                System.exit(1);
            }
        });
    }

    static void record(Context context, Thread thread, Throwable error) {
        try {
            StringWriter buffer = new StringWriter();
            PrintWriter writer = new PrintWriter(buffer);
            writer.println("时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                    .format(new Date()));
            writer.println("线程: " + (thread == null ? "?" : thread.getName()));
            writer.println("版本: " + BuildConfig.VERSION_NAME);
            writer.println();
            if (error != null) {
                error.printStackTrace(writer);
            }
            writer.println();
            writer.println("---- 崩溃前的日志 ----");
            writer.println(LogRecorder.dump());
            writer.flush();

            String text = buffer.toString();
            if (text.length() > MAX_CHARS) {
                text = text.substring(0, MAX_CHARS);
            }
            prefs(context).edit().putString(KEY_LAST, text).commit();
        } catch (Throwable ignored) {
        }
    }

    /** 取上次崩溃记录；没有则返回空串 */
    public static String lastReport(Context context) {
        return prefs(context).getString(KEY_LAST, "");
    }

    public static void clear(Context context) {
        prefs(context).edit().remove(KEY_LAST).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
