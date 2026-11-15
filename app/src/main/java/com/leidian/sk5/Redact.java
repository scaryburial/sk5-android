package com.leidian.sk5;

/** 日志脱敏：日志要能贴给别人看，节点密码不能跟着出门。 */
final class Redact {

    private Redact() {
    }

    static String secrets(String json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        return json.replaceAll("(\"password\"\\s*:\\s*\")[^\"]*(\")", "$1***$2")
                .replaceAll("(\"username\"\\s*:\\s*\")[^\"]*(\")", "$1***$2");
    }
}
