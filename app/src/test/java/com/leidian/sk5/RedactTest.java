package com.leidian.sk5;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/** 日志脱敏：贴给别人的日志里不能出现节点账号密码。 */
public class RedactTest {

    @Test
    public void hidesPasswordAndUsername() {
        String json = "{\"type\":\"socks\",\"username\":\"alice\",\"password\":\"s3cret\"}";
        String safe = Redact.secrets(json);
        assertFalse(safe.contains("s3cret"));
        assertFalse(safe.contains("alice"));
        assertTrue(safe.contains("\"password\":\"***\""));
        assertTrue(safe.contains("\"username\":\"***\""));
    }

    @Test
    public void keepsStructure() {
        String json = "{\"server\":\"1.2.3.4\",\"server_port\":1080}";
        assertTrue(Redact.secrets(json).contains("1.2.3.4"));
    }

    @Test
    public void handlesEmpty() {
        assertTrue(Redact.secrets(null).isEmpty());
        assertTrue(Redact.secrets("").isEmpty());
    }
}
