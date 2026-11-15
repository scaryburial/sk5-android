package com.leidian.sk5;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * 内存日志 + 落盘。
 *
 * 必须落盘：native 层崩溃（Go panic / 野指针）不会走 Java 的未捕获异常处理器，
 * 进程直接消失。只有把每一行同步写进 SharedPreferences，重启后才能看到
 * 「崩在哪一步」，否则这个死法完全没有线索。
 */
public final class LogRecorder {

    private static final int MAX_LINES = 300;
    private static final String PREFS = "sk5_log";
    private static final String KEY_TAIL = "tail";

    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    private static SharedPreferences prefs;

    private LogRecorder() {
    }

    public static synchronized void init(Context context) {
        if (prefs != null) {
            return;
        }
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY_TAIL, "");
        if (!saved.isEmpty()) {
            push("---- 以下为上次运行的记录 ----");
            for (String line : saved.split("\n")) {
                if (!line.isEmpty()) {
                    push(line);
                }
            }
        }
    }

    public static void log(String tag, String message) {
        append("[" + tag + "] " + message);
    }

    public static void error(String tag, String message, Throwable t) {
        append("[" + tag + "] " + message + (t == null ? "" : "：" + t));
    }

    /** 连接流程的分步标记，用来定位崩溃发生在哪一步之后 */
    public static void step(String message) {
        append("[步骤] " + message);
    }

    private static synchronized void append(String line) {
        push(TIME.format(new Date()) + "  " + line);
        persist();
    }

    private static void push(String line) {
        LINES.addLast(line);
        while (LINES.size() > MAX_LINES) {
            LINES.removeFirst();
        }
    }

    private static void persist() {
        if (prefs == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : LINES) {
            sb.append(line).append('\n');
        }
        prefs.edit().putString(KEY_TAIL, sb.toString()).apply();
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String line : LINES) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        LINES.clear();
        if (prefs != null) {
            prefs.edit().remove(KEY_TAIL).apply();
        }
    }
}
