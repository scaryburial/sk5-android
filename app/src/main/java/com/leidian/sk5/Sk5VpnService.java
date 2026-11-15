package com.leidian.sk5;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import io.nekohasekai.libbox.RoutePrefix;
import io.nekohasekai.libbox.RoutePrefixIterator;
import io.nekohasekai.libbox.StringIterator;
import io.nekohasekai.libbox.TunOptions;

/**
 * 承载 sing-box 的 TUN 设备，接管整机流量。
 *
 * 与只代理自身的玩法不同：这里不调用 addAllowedApplication，
 * 而是把自己排除在外（addDisallowedApplication）。原因是内核连节点的 socket
 * 已经 protect 过，而 App 自己的探测请求没必要绕一圈隧道，
 * 排除自身还能避免「App 内请求 → 隧道 → 内核 → 隧道」的死循环。
 */
public class Sk5VpnService extends VpnService {

    private static final String TAG = "Sk5Vpn";
    private static final String CHANNEL_ID = "sk5_status";
    private static final int NOTIFICATION_ID = 1001;

    private static volatile Sk5VpnService instance;

    private ParcelFileDescriptor tunFd;
    private Notification.Builder notification;

    public static Sk5VpnService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.i(TAG, "VPN 服务已创建");
        LogRecorder.log("TUN", "服务已创建");
        createChannel();
        startAsForeground(getString(R.string.state_connecting));
    }

    @Override
    public void onRevoke() {
        // 用户在系统设置或其他客户端里把 VPN 切走了，内核此时已经没有意义
        LogRecorder.log("TUN", "被系统或用户撤销");
        ProxyManager.get().onVpnRevoked();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        instance = null;
        closeTun();
        LogRecorder.log("TUN", "服务已销毁");
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    public void updateNotification(String text) {
        if (notification == null) {
            return;
        }
        notification.setContentText(text);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, notification.build());
        }
    }

    public synchronized boolean isEstablished() {
        return tunFd != null;
    }

    /** 按内核给的参数建立 TUN，返回文件描述符交给内核接管 */
    public synchronized int establish(TunOptions options) throws Exception {
        if (prepare(this) != null) {
            throw new IllegalStateException(getString(R.string.err_vpn_denied));
        }

        int mtu = options.getMTU() > 0 ? options.getMTU() : 9000;
        Builder builder = new Builder()
                .setSession("SK5")
                .setMtu(mtu);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false);
        }
        builder.allowBypass();

        List<String> v4 = addAddresses(builder, options.getInet4Address());
        addAddresses(builder, options.getInet6Address());
        if (v4.isEmpty()) {
            builder.addAddress("172.19.0.1", 30);
        }

        if (options.getAutoRoute()) {
            builder.addRoute("0.0.0.0", 0);
            builder.addRoute("::", 0);
        }

        // 内核没给 DNS 地址时显式指向 TUN 网关：Android 才会把解析请求送进隧道，
        // 由 sing-box 用配置里的 DoH（经节点）去查，避免本地 DNS 污染。
        boolean dnsAdded = false;
        try {
            StringIterator dns = options.getDNSServerAddress();
            if (dns != null) {
                while (dns.hasNext()) {
                    builder.addDnsServer(dns.next());
                    dnsAdded = true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "读取 DNS 失败", e);
        }
        if (!dnsAdded) {
            builder.addDnsServer("172.19.0.1");
        }

        // 自身的流量不进隧道，避免 App 内探测与隧道互相缠绕
        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (Exception e) {
            Log.w(TAG, "排除自身失败", e);
        }

        closeTun();
        tunFd = builder.establish();
        if (tunFd == null) {
            throw new IllegalStateException(getString(R.string.err_vpn_denied));
        }
        LogRecorder.log("TUN", "已建立 fd=" + tunFd.getFd() + " mtu=" + mtu);
        updateNotification(getString(R.string.state_connected));
        return tunFd.getFd();
    }

    /** 内核连节点的 socket 必须绕过 VPN，否则流量绕回自己形成死循环 */
    public void protectSocket(int fd) {
        try {
            protect(fd);
        } catch (Exception e) {
            Log.w(TAG, "protect 失败", e);
        }
    }

    private void closeTun() {
        if (tunFd != null) {
            try {
                tunFd.close();
            } catch (Exception ignored) {
            }
            tunFd = null;
        }
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private void startAsForeground(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_nav_connect)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pending);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            notification.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        Notification built = notification.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, built, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, built);
        }
    }

    private static List<String> addAddresses(Builder builder, RoutePrefixIterator iterator) {
        List<String> added = new ArrayList<>();
        if (iterator == null) {
            return added;
        }
        while (iterator.hasNext()) {
            RoutePrefix prefix = iterator.next();
            try {
                builder.addAddress(prefix.address(), prefix.prefix());
                added.add(prefix.address());
            } catch (Exception e) {
                Log.w(TAG, "添加地址失败: " + prefix.address(), e);
            }
        }
        return added;
    }
}
