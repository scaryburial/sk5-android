package com.leidian.sk5;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 直接和节点握手测延迟，不走隧道，避免「隧道本身有问题」干扰判断。 */
public final class LatencyTester {

    public interface Callback {
        /** @param ms 毫秒；-1 表示不可达 */
        void onResult(int ms);
    }

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    private LatencyTester() {
    }

    public static void test(SocksNode node, Callback callback) {
        POOL.execute(() -> {
            int best = -1;
            for (int i = 0; i < 3; i++) {
                long start = System.currentTimeMillis();
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(node.host, node.port), 4000);
                    int cost = (int) (System.currentTimeMillis() - start);
                    best = best < 0 ? cost : Math.min(best, cost);
                } catch (Exception e) {
                    break;
                }
            }
            final int result = best;
            UiKit.runOnMain(() -> callback.onResult(result));
        });
    }
}
