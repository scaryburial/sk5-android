package com.leidian.sk5;

import android.content.Context;
import android.util.Log;

import java.io.File;

import io.nekohasekai.libbox.CommandServer;
import io.nekohasekai.libbox.CommandServerHandler;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.OverrideOptions;
import io.nekohasekai.libbox.SetupOptions;
import io.nekohasekai.libbox.SystemProxyStatus;

/**
 * 管理 sing-box 内核的生命周期。
 *
 * 走 CommandServer 启动路径（与官方 Android 客户端一致）：
 * touch 加载 native → setup 初始化 → newCommandServer → start → startOrReloadService。
 * 其中 touch 与 setup 都必须先于其他调用，且 setup 全局只能执行一次。
 */
public final class CoreRuntime {

    private static final String TAG = "CoreRuntime";

    private static CoreRuntime instance;

    /** libbox 的 native 库只需加载一次 */
    private static boolean sdkLoaded;
    /** setup 是进程级一次性初始化，重复调用会抛异常 */
    private static boolean sdkInitialized;

    private CommandServer commandServer;
    /** 必须长期持有：平台回调对象被回收后，Go 侧再回调就是野指针 */
    private BoxPlatform platform;
    private volatile boolean started;

    public static synchronized CoreRuntime get() {
        if (instance == null) {
            instance = new CoreRuntime();
        }
        return instance;
    }

    public synchronized boolean isStarted() {
        return started;
    }

    public static void logLine(String message) {
        Log.i(TAG, message);
    }

    /** 取调用栈上最近的非本类帧，用于定位是谁触发的 */
    private static String caller() {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        for (StackTraceElement e : trace) {
            String name = e.getClassName();
            if (name == null || name.equals(CoreRuntime.class.getName())) {
                continue;
            }
            if (name.startsWith("java.lang.Thread")) {
                continue;
            }
            return e.getMethodName() + "(" + e.getFileName() + ":" + e.getLineNumber() + ")";
        }
        return "?";
    }

    public synchronized String version() {
        try {
            // 这里只加载 native，不做 setup，否则会把初始化标记置上
            // 却没真正执行 setup，后续 start() 会跳过初始化。
            loadNative();
            return Libbox.version();
        } catch (Throwable t) {
            return "unavailable: " + t;
        }
    }

    private static synchronized void loadNative() {
        if (sdkLoaded) {
            return;
        }
        // gomobile 生成的类不会自动加载 native 库，touch() 是官方的加载入口，
        // 漏掉它 newCommandServer 会因找不到符号而直接失败。
        Libbox.touch();
        sdkLoaded = true;
    }

    private static synchronized void ensureSdk(Context context) throws Exception {
        loadNative();
        if (sdkInitialized) {
            return;
        }
        if (context != null) {
            SetupOptions options = new SetupOptions();
            File base = new File(context.getFilesDir(), "box");
            File work = new File(base, "work");
            File tmp = new File(context.getCacheDir(), "box-tmp");
            //noinspection ResultOfMethodCallIgnored
            base.mkdirs();
            //noinspection ResultOfMethodCallIgnored
            work.mkdirs();
            //noinspection ResultOfMethodCallIgnored
            tmp.mkdirs();
            options.setBasePath(base.getAbsolutePath());
            options.setWorkingPath(work.getAbsolutePath());
            options.setTempPath(tmp.getAbsolutePath());
            options.setFixAndroidStack(true);
            options.setDebug(true);
            options.setLogMaxLines(500);
            options.setAppVersion(BuildConfig.VERSION_NAME);
            options.setAppMarketingVersion(BuildConfig.VERSION_NAME);
            Libbox.setup(options);
        }
        sdkInitialized = true;
    }

    /** 两次启动之间的最小间隔，防止某处逻辑循环导致内核被反复重建 */
    private static final long MIN_RESTART_INTERVAL_MS = 3000;
    private static long lastStartAt;
    /** 上一次启动用的配置，用来区分「重复启动」和「换配置重启」 */
    private static String lastConfigJson;

    /**
     * @return true 表示内核已按这份配置启动；false 表示被节流跳过
     */
    public synchronized boolean start(Context context, String configJson) throws Exception {
        long now = System.currentTimeMillis();
        // 只拦截「同一份配置的重复启动」。换配置（比如换入口、换连接形态）
        // 必须允许立即重启，否则切换会静默失败——之前就出现过
        // 「跳过本次」但调用方仍当作成功，界面显示已连接、实际还是旧配置。
        boolean sameConfig = configJson.equals(lastConfigJson);
        if (sameConfig && now - lastStartAt < MIN_RESTART_INTERVAL_MS) {
            LogRecorder.log("内核", "启动过频，跳过本次（距上次 "
                    + (now - lastStartAt) + "ms） ← " + caller());
            return false;
        }
        lastStartAt = now;
        lastConfigJson = configJson;

        stop();

        ensureSdk(context);

        LogRecorder.log("内核", "启动，配置长度 " + configJson.length());
        LogRecorder.log("内核", "调用来源: " + caller());
        LogRecorder.log("内核", "配置内容: " + configJson);

        // 先让内核校验配置，错误在这里就能拿到可读信息，而不是等到连接超时
        try {
            Libbox.checkConfig(configJson);
        } catch (Throwable t) {
            throw new IllegalStateException("配置被内核拒绝: " + t.getMessage(), t);
        }

        // 用构造器而不是 Libbox.newCommandServer：构造器走 go.Seq 的引用计数路径，
        // 静态工厂返回的对象缺少 IncRef，Java 侧一旦被回收，Go 侧访问就是野指针。
        platform = new BoxPlatform(context);
        commandServer = new CommandServer(new Handler(), platform);
        commandServer.start();

        OverrideOptions override = new OverrideOptions();
        override.setAutoRedirect(false);
        commandServer.startOrReloadService(configJson, override);

        started = true;
        Log.i(TAG, "内核已启动");
        LogRecorder.log("内核", "已启动，TUN 模式");
        return true;
    }

    public synchronized void stop() {
        if (commandServer != null) {
            try {
                commandServer.closeService();
            } catch (Exception e) {
                Log.w(TAG, "closeService 失败", e);
            }
            try {
                commandServer.close();
            } catch (Exception e) {
                Log.w(TAG, "close 失败", e);
            }
            commandServer = null;
        }
        platform = null;
        started = false;
    }

    private static final class Handler implements CommandServerHandler {
        @Override
        public int connectSSHAgent() {
            return -1;
        }

        @Override
        public SystemProxyStatus getSystemProxyStatus() {
            SystemProxyStatus status = new SystemProxyStatus();
            status.setAvailable(false);
            status.setEnabled(false);
            return status;
        }

        @Override
        public void serviceReload() {
        }

        @Override
        public void serviceStop() {
        }

        @Override
        public void setSystemProxyEnabled(boolean enabled) {
        }

        @Override
        public void triggerNativeCrash() {
        }

        @Override
        public void writeDebugMessage(String message) {
            Log.d(TAG, message);
        }
    }
}
