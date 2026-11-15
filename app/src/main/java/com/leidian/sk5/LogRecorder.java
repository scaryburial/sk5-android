package com.leidian.sk5;

import android.content.Context;
import android.content.SharedPreferences;

import android.os.Build;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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
 *
 * 另外必须抓 logcat：Go 侧的 panic 与 SIGSEGV 只会出现在系统日志里
 * （gobind 把 Go 的 stderr 写到 logcat 的 GoLog 标签），应用内看不到。
 */
public final class LogRecorder {

    private static final int MAX_LINES = 400;
    private static final String PREFS = "sk5_log";
    private static final String KEY_TAIL = "tail";

    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    private static SharedPreferences prefs;
    /** 本次运行的起始位置：只有这之后的行才落盘，避免每次启动把历史反复叠加 */
    private static int sessionStart;

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
            push("---- 上次运行记录结束 ----");
        }
        sessionStart = LINES.size();
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

    /**
     * 只进内存、不落盘。
     *
     * 用于内核日志回调：那是 Go 侧的单一日志线程，在这里做磁盘同步写
     * 会把它的通道堵住，严重时整个内核卡停。
     */
    public static synchronized void logInMemory(String tag, String message) {
        push(TIME.format(new Date()) + "  [" + tag + "] " + message);
    }

    private static synchronized void append(String line) {
        push(TIME.format(new Date()) + "  " + line);
        persist();
    }

    private static void push(String line) {
        LINES.addLast(line);
        while (LINES.size() > MAX_LINES) {
            LINES.removeFirst();
            if (sessionStart > 0) {
                sessionStart--;
            }
        }
    }

    private static void persist() {
        if (prefs == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        int index = 0;
        for (String line : LINES) {
            if (index++ >= sessionStart) {
                sb.append(line).append('\n');
            }
        }
        // 必须 commit 而不是 apply：native 层崩溃时进程直接消失，
        // apply 的异步写盘来不及落盘，最后几行——恰好是定位崩溃的关键——会丢。
        prefs.edit().putString(KEY_TAIL, sb.toString()).commit();
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
        sessionStart = 0;
        if (prefs != null) {
            prefs.edit().remove(KEY_TAIL).commit();
        }
    }

    /**
     * 抓取设备日志里与本应用、内核相关的行。
     *
     * libbox 是 gobind 生成的，Go 的 stderr 被 gomobile 转到 logcat 的 GoLog 标签，
     * Go panic、SIGSEGV、SIGABRT 的前半段都会出现在那里。
     */
    public static void captureDeviceLog() {
        if (prefs == null) {
            return;
        }
        new Thread(() -> {
            String[] lines = readLogcat();
            if (lines.length == 0) {
                append("[设备日志] 读取失败（系统限制或本机型禁止应用读日志）");
                return;
            }
            append("[设备日志] 已抓取 " + lines.length + " 行（内核与崩溃相关）");
            int limit = Math.min(lines.length, 120);
            for (int i = 0; i < limit; i++) {
                append("[设备日志] " + lines[lines.length - limit + i]);
            }
        }, "device-log").start();
    }

    private static String[] readLogcat() {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "/system/bin/logcat", "-d", "-v", "time", "-t", "3000");
            builder.redirectErrorStream(true);
            process = builder.start();
            java.util.List<String> kept = new java.util.ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (relevant(line)) {
                        kept.add(line);
                    }
                }
            }
            return kept.toArray(new String[0]);
        } catch (Throwable t) {
            return new String[0];
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean relevant(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("golog")
                || lower.contains("libbox")
                || lower.contains("sing-box")
                || lower.contains("com.leidian.sk5")
                || lower.contains("panic")
                || lower.contains("fatal signal")
                || lower.contains("sigsegv")
                || lower.contains("sigabrt")
                || lower.contains("androidruntime")
                || lower.contains("tombstone")
                || lower.contains("vpn");
    }

    static String deviceInfo() {
        return "Android " + Build.VERSION.RELEASE
                + " / API " + Build.VERSION.SDK_INT
                + " / " + Build.MANUFACTURER + " " + Build.MODEL
                + " / " + Build.SUPPORTED_ABIS[0];
    }
}
