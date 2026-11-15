package com.leidian.sk5;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * 这一组断言针对一个真实的线上崩溃：
 * 上报 "fe80::de87:88b:ecd3:3dcf%wlan0/64" 会让内核 netip.MustParsePrefix
 * panic，进程被 SIGABRT 杀掉且没有任何 Java 堆栈。
 */
public class IfAddrTest {

    @Test
    public void stripsZoneSuffix() {
        assertEquals("fe80::de87:88b:ecd3:3dcf", IfAddr.stripZone("fe80::de87:88b:ecd3:3dcf%wlan0"));
        assertEquals("2001:db8::1", IfAddr.stripZone("2001:db8::1"));
        assertNull(IfAddr.stripZone(null));
    }

    @Test
    public void keepsGlobalPrefixes() {
        // 不同 JDK 对 IPv6 的文本形式不同（有的不压缩 0），
        // 只断言「有前缀、无作用域」，具体写法交给内核解析。
        String v6 = IfAddr.toPrefix(ip("2001:db8::1"), 64);
        org.junit.Assert.assertNotNull(v6);
        org.junit.Assert.assertTrue(v6.endsWith("/64"));
        org.junit.Assert.assertFalse(v6.contains("%"));
        assertEquals("192.168.1.5/24", IfAddr.toPrefix(ip("192.168.1.5"), 24));
    }

    @Test
    public void dropsLinkLocalAndMulticast() {
        assertNull(IfAddr.toPrefix(ip("fe80::1"), 64));
        assertNull(IfAddr.toPrefix(ip("169.254.3.4"), 16));
        assertNull(IfAddr.toPrefix(ip("224.0.0.1"), 4));
    }

    @Test
    public void dropsInvalidInput() {
        assertNull(IfAddr.toPrefix(null, 24));
        assertNull(IfAddr.toPrefix(ip("192.168.1.5"), -1));
    }

    /** 无论输入是什么形态，上报出去的前缀里都不能出现 % */
    @Test
    public void neverEmitsZone() {
        String[] candidates = {"fe80::1", "2001:db8::1", "10.0.0.2", "fd00::1"};
        for (String candidate : candidates) {
            String prefix = IfAddr.toPrefix(ip(candidate), 64);
            if (prefix != null) {
                org.junit.Assert.assertFalse(candidate, prefix.contains("%"));
            }
        }
    }

    private static java.net.InetAddress ip(String text) {
        try {
            return java.net.InetAddress.getByName(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
