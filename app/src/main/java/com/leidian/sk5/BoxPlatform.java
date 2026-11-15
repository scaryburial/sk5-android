package com.leidian.sk5;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;

import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.nekohasekai.libbox.BridgeOptions;
import io.nekohasekai.libbox.BridgeSession;
import io.nekohasekai.libbox.ConnectionOwner;
import io.nekohasekai.libbox.InterfaceUpdateListener;
import io.nekohasekai.libbox.LocalDNSTransport;
import io.nekohasekai.libbox.NeighborUpdateListener;
import io.nekohasekai.libbox.NetworkInterfaceIterator;
import io.nekohasekai.libbox.Notification;
import io.nekohasekai.libbox.PlatformInterface;
import io.nekohasekai.libbox.PlatformUser;
import io.nekohasekai.libbox.ShellSession;
import io.nekohasekai.libbox.StringIterator;
import io.nekohasekai.libbox.TunOptions;
import io.nekohasekai.libbox.WIFIState;

/**
 * libbox 的平台回调。
 *
 * 这里只做「进程内本地代理」，不创建 TUN、不接管系统流量。
 *
 * 重要：凡是返回对象的方法都绝不能返回 null。libbox 是 gomobile 封装的 Go 代码，
 * Go 侧拿到 nil 之后会直接解引用，导致整个进程被 abort —— 表现出来就是
 * 「点连接后自己退出」，而且没有任何 Java 堆栈，极难定位。
 */
public class BoxPlatform implements PlatformInterface {

    // 与 OsConstants 同值的接口标志位
    private static final int IFF_UP = 0x1;
    private static final int IFF_LOOPBACK = 0x8;
    private static final int IFF_RUNNING = 0x40;
    private static final int IFF_POINTOPOINT = 0x10;
    private static final int IFF_MULTICAST = 0x1000;

    private final Context context;

    /**
     * 是否处于监听状态。Go 回调对象是 gomobile 代理，closeDefaultInterfaceMonitor 之后
     * 它就失效；任何未执行的延后调用必须在入口先检查这个标记，否则就是野指针访问，
     * 表现是进程被 abort、没有任何 Java 堆栈。
     */
    private volatile boolean monitoring;
    private ConnectivityManager.NetworkCallback callback;

    public BoxPlatform(Context context) {
        this.context = context.getApplicationContext();
    }

    // ── 网卡列表：内核启动时要枚举本地接口，返回 null 会崩 ──

    @Override
    public NetworkInterfaceIterator getInterfaces() {
        List<io.nekohasekai.libbox.NetworkInterface> items = new ArrayList<>();
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            List<java.net.NetworkInterface> all = Collections.list(
                    java.net.NetworkInterface.getNetworkInterfaces());

            for (java.net.NetworkInterface nic : all) {
                if (!nic.isUp()) {
                    continue;
                }
                io.nekohasekai.libbox.NetworkInterface item =
                        new io.nekohasekai.libbox.NetworkInterface();
                item.setName(nic.getName());
                item.setIndex(nic.getIndex());
                try {
                    item.setMTU(nic.getMTU());
                } catch (Exception e) {
                    item.setMTU(1500);
                }

                List<String> addresses = new ArrayList<>();
                for (InterfaceAddress ia : nic.getInterfaceAddresses()) {
                    String prefix = IfAddr.toPrefix(ia.getAddress(), ia.getNetworkPrefixLength());
                    if (prefix != null) {
                        addresses.add(prefix);
                    }
                }
                item.setAddresses(new StringList(addresses));

                // 以下三个字段必须是真实迭代器：Go 侧会直接遍历，传 null 会解引用空指针
                item.setDNSServer(new StringList(Collections.<String>emptyList()));
                item.setGateway(new StringList(Collections.<String>emptyList()));

                int flags = 0;
                if (nic.isUp()) {
                    flags |= IFF_UP | IFF_RUNNING;
                }
                if (nic.isLoopback()) {
                    flags |= IFF_LOOPBACK;
                }
                if (nic.isPointToPoint()) {
                    flags |= IFF_POINTOPOINT;
                }
                try {
                    if (nic.supportsMulticast()) {
                        flags |= IFF_MULTICAST;
                    }
                } catch (Exception ignored) {
                }
                item.setFlags(flags);
                item.setType(interfaceType(nic));
                item.setMetered(false);

                items.add(item);
            }

            // 把连通性信息（DNS、网关）补齐到对应接口上
            if (cm != null) {
                for (android.net.Network network : cm.getAllNetworks()) {
                    LinkProperties props = cm.getLinkProperties(network);
                    if (props == null || props.getInterfaceName() == null) {
                        continue;
                    }
                    for (io.nekohasekai.libbox.NetworkInterface item : items) {
                        if (!props.getInterfaceName().equals(item.getName())) {
                            continue;
                        }
                        List<String> dns = new ArrayList<>();
                        for (InetAddress addr : props.getDnsServers()) {
                            if (addr.getHostAddress() != null) {
                                dns.add(addr.getHostAddress());
                            }
                        }
                        item.setDNSServer(new StringList(dns));

                        List<String> gateways = new ArrayList<>();
                        for (android.net.RouteInfo route : props.getRoutes()) {
                            InetAddress gateway = route.getGateway();
                            if (route.getDestination().getPrefixLength() == 0
                                    && gateway != null && !gateway.isAnyLocalAddress()) {
                                String host = IfAddr.stripZone(gateway.getHostAddress());
                                if (host != null && !host.isEmpty()) {
                                    gateways.add(host);
                                }
                            }
                        }
                        item.setGateway(new StringList(gateways));

                        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                        if (caps != null) {
                            item.setMetered(!caps.hasCapability(
                                    NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 任何一个接口构造失败都退回空列表；空列表是安全的，null 不是
            items.clear();
        }
        return new InterfaceList(items);
    }

    private static int interfaceType(java.net.NetworkInterface nic) {
        String name = nic.getName();
        if (name == null) {
            return io.nekohasekai.libbox.Libbox.InterfaceTypeOther;
        }
        if (name.startsWith("wlan")) {
            return io.nekohasekai.libbox.Libbox.InterfaceTypeWIFI;
        }
        if (name.startsWith("rmnet") || name.startsWith("ccmni")
                || name.startsWith("pdp") || name.startsWith("wwan")) {
            return io.nekohasekai.libbox.Libbox.InterfaceTypeCellular;
        }
        if (name.startsWith("eth")) {
            return io.nekohasekai.libbox.Libbox.InterfaceTypeEthernet;
        }
        return io.nekohasekai.libbox.Libbox.InterfaceTypeOther;
    }

    @Override
    public void startDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        // 全局 TUN 需要跟随默认网卡：WiFi 切移动网络时内核要立刻换出站网卡。
        // 与官方 Android 客户端一致，直接在网络回调线程上调用 listener，
        // 不 post 到主线程——延后执行会在监听关闭后踩到已释放的 Go 对象。
        closeDefaultInterfaceMonitor(listener);
        if (!NodeStore.get(context).followInterface()) {
            LogRecorder.log("网络", "跟随默认网卡已关闭，跳过注册");
            return;
        }
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return;
        }
        monitoring = true;
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                reportDefault(listener, network, true);
            }

            @Override
            public void onLost(Network network) {
                reportDefault(listener, network, false);
            }
        };
        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(), callback);
        } catch (Exception e) {
            monitoring = false;
            callback = null;
            LogRecorder.error("网络", "注册网络回调失败", e);
            return;
        }
        LogRecorder.log("网络", "默认网卡监听已启动");
    }

    private void reportDefault(InterfaceUpdateListener listener, Network network, boolean isDefault) {
        if (!monitoring) {
            return;
        }
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return;
        }
        LinkProperties props = cm.getLinkProperties(network);
        if (props == null || props.getInterfaceName() == null) {
            return;
        }
        String name = props.getInterfaceName();
        int index = 0;
        try {
            java.net.NetworkInterface nic = java.net.NetworkInterface.getByName(name);
            if (nic != null) {
                index = nic.getIndex();
            }
        } catch (Exception ignored) {
        }
        // 索引拿不到就不上报：内核拿到 0 或负数索引会当成非法网卡
        if (index <= 0) {
            return;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        boolean expensive = caps == null
                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        try {
            listener.updateDefaultInterface(name, index, isDefault, expensive);
        } catch (Throwable t) {
            LogRecorder.error("网络", "上报默认网卡失败", t);
        }
    }

    @Override
    public void closeDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        // 先清标记再注销：标记一旦为 false，未执行的回调就会直接返回
        monitoring = false;
        if (callback == null) {
            return;
        }
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(callback);
            } catch (Exception ignored) {
            }
        }
        callback = null;
    }

    // ── 以下返回对象的方法一律构造真实对象，不能返回 null ──

    @Override
    public WIFIState readWIFIState() {
        return new WIFIState("", "");
    }

    @Override
    public ConnectionOwner findConnectionOwner(int ipProtocol, String sourceAddress,
                                               int sourcePort, String destinationAddress,
                                               int destinationPort) {
        ConnectionOwner owner = new ConnectionOwner();
        owner.setUserId(android.os.Process.myUid());
        owner.setUserName("self");
        return owner;
    }

    @Override
    public PlatformUser lookupUser(String username) {
        return new PlatformUser();
    }

    @Override
    public LocalDNSTransport localDNSTransport() {
        // 返回 null 表示“不使用系统解析器”，这是 libbox 明确支持的取值
        return null;
    }

    @Override
    public BridgeSession createBridge(BridgeOptions options) {
        return null;
    }

    @Override
    public ShellSession openShellSession(PlatformUser user, String command,
                                         StringIterator args, String workDir,
                                         int env, int terminal) {
        return null;
    }

    @Override
    public int openTun(TunOptions options) {
        Sk5VpnService service = Sk5VpnService.getInstance();
        if (service == null) {
            LogRecorder.log("TUN", "openTun 失败：VPN 服务未启动");
            return -1;
        }
        try {
            return service.establish(options);
        } catch (Throwable t) {
            LogRecorder.error("TUN", "openTun 异常", t);
            return -1;
        }
    }

    @Override
    public String readSystemSSHHostKey() {
        return "";
    }

    @Override
    public String lookupSFTPServer() {
        return "";
    }

    @Override
    public String tailscaleHostname() {
        return "";
    }

    @Override
    public void autoDetectInterfaceControl(int fd) {
        // 内核自建 socket 必须绕过 VPN，否则流量绕回自己形成死循环
        Sk5VpnService service = Sk5VpnService.getInstance();
        if (service != null) {
            service.protectSocket(fd);
        }
    }

    @Override
    public void cancelNotification(String tag, int id) {
    }

    @Override
    public void checkPlatformShell() {
    }

    @Override
    public void clearDNSCache() {
    }

    @Override
    public void closeNeighborMonitor(NeighborUpdateListener listener) {
    }

    @Override
    public boolean includeAllNetworks() {
        return false;
    }

    @Override
    public void registerMyInterface(String name) {
    }

    @Override
    public void sendNotification(Notification notification) {
    }

    @Override
    public void startNeighborMonitor(NeighborUpdateListener listener) {
    }

    @Override
    public boolean underNetworkExtension() {
        return false;
    }

    @Override
    public boolean usePlatformAutoDetectInterfaceControl() {
        // 开启后内核会把自建 socket 交过来，由我们 protect
        return true;
    }

    @Override
    public boolean usePlatformBridge() {
        return false;
    }

    @Override
    public boolean usePlatformShell() {
        return false;
    }

    @Override
    public boolean useProcFS() {
        // 普通应用读不到完整的 /proc 网络信息，开启反而让内核走一条走不通的路
        return false;
    }

    // ── gomobile 要求的迭代器实现 ──

    private static final class StringList implements StringIterator {
        private final List<String> items;
        private int cursor;

        StringList(List<String> items) {
            this.items = items;
        }

        @Override
        public boolean hasNext() {
            return cursor < items.size();
        }

        @Override
        public int len() {
            return items.size();
        }

        @Override
        public String next() {
            return cursor < items.size() ? items.get(cursor++) : "";
        }
    }

    private static final class InterfaceList implements NetworkInterfaceIterator {
        private final List<io.nekohasekai.libbox.NetworkInterface> items;
        private int cursor;

        InterfaceList(List<io.nekohasekai.libbox.NetworkInterface> items) {
            this.items = items;
        }

        @Override
        public boolean hasNext() {
            return cursor < items.size();
        }

        @Override
        public io.nekohasekai.libbox.NetworkInterface next() {
            return items.get(cursor++);
        }
    }
}
