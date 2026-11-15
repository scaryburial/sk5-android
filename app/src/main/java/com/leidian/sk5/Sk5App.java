package com.leidian.sk5;

import android.app.Application;

/** 尽早安装崩溃捕获与日志落盘：Application.onCreate 早于任何 Activity。 */
public class Sk5App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        LogRecorder.init(this);
        CrashGuard.install(this);
        LogRecorder.log("启动", "版本 " + BuildConfig.VERSION_NAME);
        LogRecorder.log("设备", LogRecorder.deviceInfo());

        // 上次若有内核在跑却没走到正常断开，说明进程是被杀掉的。
        // 这种情况 Java 侧抓不到堆栈，只能去系统日志里找 Go 的崩溃输出。
        if (CrashGuard.wasRunning(this)) {
            LogRecorder.log("启动", "上次运行异常结束：内核启动后进程消失");
            CrashGuard.setRunning(this, false);
            LogRecorder.captureDeviceLog();
        }
    }
}
