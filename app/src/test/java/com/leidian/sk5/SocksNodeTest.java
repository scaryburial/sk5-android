package com.leidian.sk5;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SocksNodeTest {

    @Test
    public void parsesCredentialsAndPort() {
        SocksNode node = SocksNode.parseLink("socks5://alice:p%40ss@203.0.113.9:1088");
        assertNotNull(node);
        assertEquals("alice", node.username);
        assertEquals("p@ss", node.password);
        assertEquals("203.0.113.9", node.host);
        assertEquals(1088, node.port);
    }

    @Test
    public void defaultsPortWhenMissing() {
        SocksNode node = SocksNode.parseLink("socks5://example.com");
        assertNotNull(node);
        assertEquals(1080, node.port);
        assertFalse(node.hasAuth());
    }

    @Test
    public void acceptsSocksScheme() {
        assertNotNull(SocksNode.parseLink("socks://1.2.3.4:1080"));
    }

    @Test
    public void rejectsForeignScheme() {
        assertNull(SocksNode.parseLink("http://example.com:8080"));
        assertNull(SocksNode.parseLink("hello"));
        assertNull(SocksNode.parseLink(null));
    }

    @Test
    public void linkRoundTrip() {
        SocksNode node = new SocksNode();
        node.host = "1.2.3.4";
        node.port = 1080;
        node.username = "u@ser";
        node.password = "p@ss:word";
        SocksNode parsed = SocksNode.parseLink(node.toLink());
        assertNotNull(parsed);
        assertEquals(node.username, parsed.username);
        assertEquals(node.password, parsed.password);
        assertEquals(node.host, parsed.host);
        assertEquals(node.port, parsed.port);
    }

    @Test
    public void configUsesTunInboundAndSocksOutbound() throws Exception {
        SocksNode node = new SocksNode();
        node.host = "203.0.113.9";
        node.port = 1088;
        node.username = "alice";
        node.password = "secret";

        JSONObject root = new JSONObject(node.toSingBoxConfig(false));
        JSONObject tun = root.getJSONArray("inbounds").getJSONObject(0);
        assertEquals("tun", tun.getString("type"));
        assertTrue(tun.getBoolean("auto_route"));

        JSONObject proxy = root.getJSONArray("outbounds").getJSONObject(0);
        assertEquals("socks", proxy.getString("type"));
        assertEquals("5", proxy.getString("version"));
        assertEquals("203.0.113.9", proxy.getString("server"));
        assertEquals(1088, proxy.getInt("server_port"));
        assertEquals("alice", proxy.getString("username"));

        assertEquals("proxy", root.getJSONObject("route").getString("final"));
    }

    @Test
    public void smartModeCarriesChinaRuleSet() throws Exception {
        SocksNode node = new SocksNode();
        node.host = "1.2.3.4";
        JSONObject root = new JSONObject(node.toSingBoxConfig(false));
        JSONObject route = root.getJSONObject("route");
        assertEquals(1, route.getJSONArray("rule_set").length());
        assertEquals(1, route.getJSONArray("rules").length());
        assertEquals("direct", route.getJSONArray("rules").getJSONObject(0).getString("outbound"));
    }

    @Test
    public void globalModeHasNoRules() throws Exception {
        SocksNode node = new SocksNode();
        node.host = "1.2.3.4";
        JSONObject route = new JSONObject(node.toSingBoxConfig(true)).getJSONObject("route");
        assertEquals(0, route.getJSONArray("rules").length());
        assertFalse(route.has("rule_set"));
    }

    @Test
    public void defaultStackIsGvisor() throws Exception {
        SocksNode node = new SocksNode();
        node.host = "1.2.3.4";
        JSONObject tun = new JSONObject(node.toSingBoxConfig(false))
                .getJSONArray("inbounds").getJSONObject(0);
        assertEquals(CoreOptions.STACK_GVISOR, tun.getString("stack"));
        assertTrue(new JSONObject(node.toSingBoxConfig(false)).getJSONObject("route")
                .getBoolean("auto_detect_interface"));
    }

    @Test
    public void stackAndFollowAreConfigurable() throws Exception {
        SocksNode node = new SocksNode();
        node.host = "1.2.3.4";
        JSONObject root = new JSONObject(node.toSingBoxConfig(false,
                CoreOptions.STACK_SYSTEM, false));
        assertEquals(CoreOptions.STACK_SYSTEM,
                root.getJSONArray("inbounds").getJSONObject(0).getString("stack"));
        assertFalse(root.getJSONObject("route").getBoolean("auto_detect_interface"));
    }

    @Test
    public void writesConfigForKernelCheck() throws Exception {
        // 交给真实 sing-box 做 check：JSON 字段写错时编译期看不出来，
        // 只能靠内核自己的严格解析发现。
        SocksNode node = new SocksNode();
        node.host = "203.0.113.9";
        node.port = 1088;
        node.username = "alice";
        node.password = "secret";
        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"));
        try (java.io.FileWriter smart = new java.io.FileWriter(new java.io.File(dir, "sk5-smart.json"));
             java.io.FileWriter global = new java.io.FileWriter(new java.io.File(dir, "sk5-global.json"));
             java.io.FileWriter sys = new java.io.FileWriter(new java.io.File(dir, "sk5-system.json"))) {
            smart.write(node.toSingBoxConfig(false));
            global.write(node.toSingBoxConfig(true));
            sys.write(node.toSingBoxConfig(false, CoreOptions.STACK_SYSTEM, false));
        }
        assertTrue(new java.io.File(dir, "sk5-smart.json").length() > 0);
    }

    @Test
    public void invalidNodeIsRejected() {
        SocksNode node = new SocksNode();
        node.host = "";
        assertFalse(node.isValid());
        boolean thrown = false;
        try {
            node.toSingBoxConfig(false);
        } catch (IllegalStateException e) {
            thrown = true;
        }
        assertTrue(thrown);
    }
}
