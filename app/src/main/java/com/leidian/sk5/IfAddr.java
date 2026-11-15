package com.leidian.sk5;

import java.net.InetAddress;

/**
 * 网卡地址的格式化。
 *
 * sing-box 用 netip.MustParsePrefix 解析我们上报的地址，解析失败会直接 panic
 * （进程 abort、没有 Java 堆栈）。链路本地 IPv6 的 hostAddress 形如
 * "fe80::1%wlan0"，带作用域后缀，而 netip 的前缀不允许带 zone，
 * 所以必须去掉；链路本地地址本身也无法路由到公网，一并跳过。
 */
final class IfAddr {

    private IfAddr() {
    }

    /** @return 形如 "192.168.1.5/24"；不该上报的地址返回 null */
    static String toPrefix(InetAddress address, int prefixLength) {
        if (address == null || prefixLength < 0) {
            return null;
        }
        if (address.isMulticastAddress() || address.isLinkLocalAddress()) {
            return null;
        }
        String host = stripZone(address.getHostAddress());
        if (host == null || host.isEmpty()) {
            return null;
        }
        return host + "/" + prefixLength;
    }

    /** 去掉 "%wlan0" 这类作用域后缀 */
    static String stripZone(String hostAddress) {
        if (hostAddress == null) {
            return null;
        }
        int zone = hostAddress.indexOf('%');
        return zone >= 0 ? hostAddress.substring(0, zone) : hostAddress;
    }
}
