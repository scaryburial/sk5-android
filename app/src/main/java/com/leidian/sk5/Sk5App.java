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
    }
}
