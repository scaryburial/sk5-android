package com.leidian.sk5;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * 连接状态的唯一来源：界面只订阅它，不直接碰内核与 VPN 服务。
 *
 * 整个流程都跑在主线程，用 Handler 轮询服务是否就绪，而不是 sleep。
 * 原因：内核是 gomobile 生成的 JNI 绑定，官方客户端也从主线程调用；
 * 放到工作线程上没有任何收益，反而多一类「回调线程不对」的故障面。
 *
 * 连接顺序有硬性要求：VPN 服务必须先建立，内核启动时才会回调 openTun。
 * 顺序反了 openTun 拿不到服务，内核直接报错退出。
 */
public final class ProxyManager {

    public enum State {
        DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING
    }

    public interface Listener {
        void onStateChanged(State state, String message);
    }

    private static final ProxyManager INSTANCE = new ProxyManager();
    /** 等 VPN 服务就绪的上限，50ms 一次，最长 3 秒 */
    private static final int MAX_WAIT_TICKS = 60;

    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile State state = State.DISCONNECTED;
    private volatile long connectedAt;
    private volatile String lastProbe = "";
    private Listener listener;

    private ProxyManager() {
    }

    public static ProxyManager get() {
        return INSTANCE;
    }

    public State state() {
        return state;
    }

    public long connectedAt() {
        return connectedAt;
    }

    public void setListener(Listener value) {
        listener = value;
    }

    public void connect(Context context, SocksNode node) {
        if (state == State.CONNECTING || state == State.CONNECTED) {
            return;
        }
        if (node == null || !node.isValid()) {
            notifyState(State.DISCONNECTED, context.getString(R.string.err_node_invalid));
            return;
        }
        final Context app = context.getApplicationContext();
        notifyState(State.CONNECTING, null);
        main.post(() -> startService(app, node));
    }

    private void startService(Context app, SocksNode node) {
        try {
            LogRecorder.step("启动 VPN 服务");
            Intent intent = new Intent(app, Sk5VpnService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent);
            } else {
                app.startService(intent);
            }
        } catch (Throwable t) {
            fail(app, t);
            return;
        }
        waitForService(app, node, 0);
    }

    private void waitForService(Context app, SocksNode node, int tick) {
        if (state != State.CONNECTING) {
            return;
        }
        if (Sk5VpnService.getInstance() != null) {
            startCore(app, node);
            return;
        }
        if (tick >= MAX_WAIT_TICKS) {
            fail(app, new IllegalStateException(app.getString(R.string.err_vpn_denied)));
            return;
        }
        main.postDelayed(() -> waitForService(app, node, tick + 1), 50);
    }

    private void startCore(Context app, SocksNode node) {
        try {
            NodeStore store = NodeStore.get(app);
            String config = node.toSingBoxConfig(store.globalMode(), store.stack(),
                    store.followInterface(), store.dnsMode());
            LogRecorder.step("启动内核（栈 " + store.stack()
                    + "，跟随网卡 " + store.followInterface()
                    + "，DNS " + store.dnsMode()
                    + "，配置 " + config.length() + " 字节）");
            // 标记在启内核之前置位：崩在启动过程中的话，下次启动就能识别出来
            CrashGuard.setRunning(app, true);
            CoreRuntime.get().start(app, config);
            connectedAt = System.currentTimeMillis();
            LogRecorder.step("内核已启动");
            notifyState(State.CONNECTED, null);
            scheduleProbe(node);
        } catch (Throwable t) {
            fail(app, t);
        }
    }

    /** 连上后自动跑一次自检：日志里就能看出是节点不行还是隧道不行 */
    private void scheduleProbe(SocksNode node) {
        lastProbe = "检测中";
        main.postDelayed(() -> NetProbe.run(node, (nodeResult, dnsResult, tunnelResult) -> {
            lastProbe = "节点：" + nodeResult.detail + "\n"
                    + "DNS：" + dnsResult.detail + "\n"
                    + "隧道：" + tunnelResult.detail;
            LogRecorder.step("自检 节点 → " + nodeResult.detail);
            LogRecorder.step("自检 DNS → " + dnsResult.detail);
            LogRecorder.step("自检 隧道 → " + tunnelResult.detail);
        }), 2000);
    }

    public String lastProbe() {
        return lastProbe;
    }

    public void disconnect(Context context) {
        if (state == State.DISCONNECTED || state == State.DISCONNECTING) {
            return;
        }
        notifyState(State.DISCONNECTING, null);
        final Context app = context.getApplicationContext();
        main.post(() -> {
            LogRecorder.step("断开连接");
            stopCoreAndService(app);
            connectedAt = 0;
            notifyState(State.DISCONNECTED, null);
        });
    }

    /** VPN 被系统或其他客户端抢走时，内核必须一起停，否则界面还显示已连接 */
    public void onVpnRevoked() {
        main.post(() -> {
            LogRecorder.step("VPN 被撤销，停止内核");
            stopCoreAndService(null);
            connectedAt = 0;
            notifyState(State.DISCONNECTED, null);
        });
    }

    private void fail(Context app, Throwable error) {
        LogRecorder.error("连接", "启动失败", error);
        stopCoreAndService(app);
        connectedAt = 0;
        String reason = error == null || error.getMessage() == null
                ? app.getString(R.string.err_core_start) : error.getMessage();
        notifyState(State.DISCONNECTED, reason);
    }

    private void stopCoreAndService(Context app) {
        try {
            CoreRuntime.get().stop();
        } catch (Throwable t) {
            LogRecorder.error("连接", "停止内核失败", t);
        }
        if (app != null) {
            // 正常停掉才清标记，残留标记等于「上次异常结束」
            CrashGuard.setRunning(app, false);
        }
        if (app == null) {
            return;
        }
        try {
            app.stopService(new Intent(app, Sk5VpnService.class));
        } catch (Throwable t) {
            LogRecorder.error("连接", "停止服务失败", t);
        }
    }

    private void notifyState(State next, String message) {
        state = next;
        final Listener target = listener;
        if (target == null) {
            return;
        }
        UiKit.runOnMain(() -> target.onStateChanged(next, message));
    }
}
