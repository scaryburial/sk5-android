package com.leidian.sk5;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * 内存日志。出问题时用户能在设置里直接看到，不用连电脑抓 logcat。
 * 只保留最近若干行，避免长时间运行占内存。
 */
public final class LogRecorder {

    private static final int MAX_LINES = 300;
    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    private LogRecorder() {
    }

    public static void log(String tag, String message) {
        append("[" + tag + "] " + message);
    }

    public static void error(String tag, String message, Throwable t) {
        append("[" + tag + "] " + message + (t == null ? "" : "：" + t));
    }

    private static synchronized void append(String line) {
        LINES.addLast(TIME.format(new Date()) + "  " + line);
        while (LINES.size() > MAX_LINES) {
            LINES.removeFirst();
        }
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
    }
}
