package com.leidian.sk5;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

/**
 * 开机自连接。
 *
 * 只在「用户打开过开关 + 之前已授予 VPN 权限」时才会真正连，
 * 没有授权时静默跳过：开机弹授权框会被系统判定为骚扰。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        NodeStore store = NodeStore.get(context);
        if (!store.autoConnect() || store.current() == null) {
            return;
        }
        if (VpnService.prepare(context) != null) {
            LogRecorder.log("开机", "未授予 VPN 权限，跳过自动连接");
            return;
        }
        LogRecorder.log("开机", "自动连接 " + store.current().displayName());
        ProxyManager.get().connect(context, store.current());
    }
}
