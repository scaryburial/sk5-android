package com.leidian.sk5;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 连接状态的唯一来源：界面只订阅它，不直接碰内核与 VPN 服务。
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
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private volatile State state = State.DISCONNECTED;
    private volatile long connectedAt;
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
        boolean global = NodeStore.get(context).globalMode();
        notifyState(State.CONNECTING, null);

        final Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                Intent intent = new Intent(app, Sk5VpnService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent);
                } else {
                    app.startService(intent);
                }
                // startForegroundService 是异步的，等实例就绪再启内核
                if (!waitForService(2000)) {
                    throw new IllegalStateException(app.getString(R.string.err_vpn_denied));
                }
                String config = node.toSingBoxConfig(global);
                CoreRuntime.get().start(app, config);
                connectedAt = System.currentTimeMillis();
                notifyState(State.CONNECTED, null);
            } catch (Throwable t) {
                LogRecorder.error("连接", "启动失败", t);
                CoreRuntime.get().stop();
                app.stopService(new Intent(app, Sk5VpnService.class));
                connectedAt = 0;
                String reason = t.getMessage() == null
                        ? app.getString(R.string.err_core_start) : t.getMessage();
                notifyState(State.DISCONNECTED, reason);
            }
        });
    }

    public void disconnect(Context context) {
        if (state == State.DISCONNECTED || state == State.DISCONNECTING) {
            return;
        }
        notifyState(State.DISCONNECTING, null);
        final Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            CoreRuntime.get().stop();
            app.stopService(new Intent(app, Sk5VpnService.class));
            connectedAt = 0;
            notifyState(State.DISCONNECTED, null);
        });
    }

    /** VPN 被系统或其他客户端抢走时，内核必须一起停，否则界面还显示已连接 */
    public void onVpnRevoked() {
        WORKER.execute(() -> {
            CoreRuntime.get().stop();
            connectedAt = 0;
            notifyState(State.DISCONNECTED, null);
        });
    }

    private static boolean waitForService(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Sk5VpnService.getInstance() != null) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return Sk5VpnService.getInstance() != null;
    }

    private void notifyState(State next, String message) {
        state = next;
        final Listener target = listener;
        if (target != null) {
            UiKit.runOnMain(() -> target.onStateChanged(next, message));
        }
    }
}
