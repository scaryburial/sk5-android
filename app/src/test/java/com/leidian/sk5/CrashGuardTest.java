package com.leidian.sk5;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** 崩溃与日志必须能落盘，否则 native 崩溃时没有任何线索。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class CrashGuardTest {

    @Test
    public void recordsUncaughtExceptionWithLogs() {
        Context context = ApplicationProvider.getApplicationContext();
        LogRecorder.init(context);
        CrashGuard.clear(context);
        LogRecorder.step("准备连接");
        LogRecorder.log("TUN", "已建立 fd=1");

        CrashGuard.record(context, Thread.currentThread(),
                new IllegalStateException("模拟崩溃"));

        String report = CrashGuard.lastReport(context);
        assertTrue(report.contains("IllegalStateException"));
        assertTrue(report.contains("模拟崩溃"));
        assertTrue(report.contains("准备连接"));
        assertTrue(report.contains("已建立 fd=1"));
    }

    @Test
    public void clearingRemovesReport() {
        Context context = ApplicationProvider.getApplicationContext();
        CrashGuard.record(context, Thread.currentThread(), new RuntimeException("x"));
        assertTrue(!CrashGuard.lastReport(context).isEmpty());
        CrashGuard.clear(context);
        assertTrue(CrashGuard.lastReport(context).isEmpty());
    }

    @Test
    public void logSurvivesUntilCleared() {
        Context context = ApplicationProvider.getApplicationContext();
        LogRecorder.init(context);
        LogRecorder.clear();
        LogRecorder.step("第一步");
        assertTrue(LogRecorder.dump().contains("第一步"));
        assertTrue(LogRecorder.dump().contains("[步骤]"));
    }
}
