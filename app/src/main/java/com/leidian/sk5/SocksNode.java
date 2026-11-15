package com.leidian.sk5;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;

/**
 * 一个 SOCKS5 节点的全部参数，以及由它生成的 sing-box 配置。
 *
 * 这里刻意只支持 SOCKS5：没有 VLESS/SS 这些协议的分支，
 * 配置短到能一眼看完，出问题时也好判断是哪一行。
 */
public final class SocksNode {

    public String name = "";
    public String host = "";
    public int port = 1080;
    public String username = "";
    public String password = "";

    /**
     * 智能分流用的国内域名后缀。
     *
     * 只按域名判断，不查 IP：一旦引入 ip_cidr 规则，sing-box 必须先解析域名
     * 才能匹配，等于每个国外域名都多一次本地 DNS 查询，既慢又把域名暴露给了
     * 本地解析器。域名后缀规则可以做到「命中就直连、不命中原样交给节点」。
     */
    private static final String[] CN_SUFFIX = {
            ".cn", ".com.cn", ".net.cn", ".org.cn", ".gov.cn", ".edu.cn",
            ".baidu.com", ".bdstatic.com", ".bdimg.com",
            ".qq.com", ".qpic.cn", ".qlogo.cn", ".gtimg.com", ".tencent.com",
            ".weixin.qq.com", ".wechat.com", ".wx.qq.com",
            ".taobao.com", ".tmall.com", ".alicdn.com", ".alipay.com", ".aliyun.com",
            ".alibaba.com", ".1688.com", ".alipayobjects.com", ".taobao.net",
            ".jd.com", ".360buyimg.com", ".jdcloud.com",
            ".pinduoduo.com", ".yangkeduo.com", ".duoduoyun.com",
            ".weibo.com", ".weibo.cn", ".sina.com.cn", ".sinaimg.cn", ".sina.cn",
            ".bilibili.com", ".bilibili.tv", ".hdslb.com", ".bilivideo.com",
            ".zhihu.com", ".zhimg.com", ".douban.com", ".doubanio.com",
            ".163.com", ".126.com", ".126.net", ".netease.com", ".yeah.net",
            ".douyin.com", ".bytedance.com", ".toutiao.com", ".ixigua.com",
            ".snssdk.com", ".byteimg.com", ".pstatp.com", ".feishu.cn", ".larkoffice.com",
            ".iqiyi.com", ".youku.com", ".kuaishou.com", ".gifshow.com",
            ".xiaomi.com", ".mi.com", ".miui.com", ".huawei.com", ".honor.com",
            ".meituan.com", ".dianping.com", ".ele.me", ".ctrip.com", ".qunar.com",
            ".12306.cn", ".amap.com", ".autonavi.com",
            ".csdn.net", ".cnblogs.com", ".gitee.com", ".jianshu.com",
            ".oschina.net", ".51cto.com", ".runoob.com",
            ".sohu.com", ".ifeng.com", ".people.com.cn", ".xinhuanet.com", ".cctv.com",
            ".hupu.com", ".sspai.com", ".36kr.com",
            ".wps.cn", ".kdocs.cn", ".qcloud.com", ".myqcloud.com", ".bcebos.com",
            ".unionpay.com", ".icbc.com.cn", ".ccb.com", ".abchina.com",
    };

    public boolean isValid() {
        return host != null && !host.trim().isEmpty() && port > 0 && port <= 65535;
    }

    public String displayName() {
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        return host + ":" + port;
    }

    public String endpoint() {
        return host + ":" + port;
    }

    public boolean hasAuth() {
        return username != null && !username.isEmpty();
    }

    /** socks5://用户名:密码@服务器:端口 */
    public String toLink() {
        StringBuilder sb = new StringBuilder("socks5://");
        if (hasAuth()) {
            sb.append(encode(username));
            if (password != null && !password.isEmpty()) {
                sb.append(':').append(encode(password));
            }
            sb.append('@');
        }
        sb.append(host).append(':').append(port);
        return sb.toString();
    }

    /**
     * 解析粘贴进来的链接。只认 socks5:// 和 socks://，
     * 端口缺省 1080，用户名密码允许缺省。
     */
    public static SocksNode parseLink(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        String lower = text.toLowerCase();
        if (!lower.startsWith("socks5://") && !lower.startsWith("socks://")) {
            return null;
        }
        try {
            URI uri = URI.create(text);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return null;
            }
            SocksNode node = new SocksNode();
            node.host = host;
            node.port = uri.getPort() > 0 ? uri.getPort() : 1080;
            String info = uri.getUserInfo();
            if (info != null && !info.isEmpty()) {
                int colon = info.indexOf(':');
                if (colon >= 0) {
                    node.username = decode(info.substring(0, colon));
                    node.password = decode(info.substring(colon + 1));
                } else {
                    node.username = decode(info);
                }
            }
            return node.isValid() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * 生成完整 sing-box 配置。
     *
     * @param globalMode true 全部走节点；false 国内域名直连、其余走节点
     */
    public String toSingBoxConfig(boolean globalMode) {
        if (!isValid()) {
            throw new IllegalStateException("节点参数不完整");
        }
        try {
            JSONObject root = new JSONObject();

            JSONObject log = new JSONObject();
            log.put("level", "warn");
            log.put("timestamp", true);
            root.put("log", log);

            // 解析全部交给节点那一侧的 DoH。
            // 不用本地 DNS：国内运营商的解析结果对国外域名不可信，
            // 一旦拿到被污染的 IP，SOCKS5 就只能连到错误地址。
            JSONObject server = new JSONObject();
            server.put("type", "https");
            server.put("tag", "remote");
            server.put("server", "1.1.1.1");
            server.put("detour", "proxy");

            JSONObject dns = new JSONObject();
            dns.put("servers", new JSONArray().put(server));
            dns.put("final", "remote");
            dns.put("strategy", "ipv4_only");
            root.put("dns", dns);

            JSONObject tun = new JSONObject();
            tun.put("type", "tun");
            tun.put("tag", "tun-in");
            tun.put("address", new JSONArray().put("172.19.0.1/30"));
            tun.put("mtu", 9000);
            tun.put("auto_route", true);
            tun.put("strict_route", false);
            tun.put("stack", "system");
            tun.put("sniff", false);
            root.put("inbounds", new JSONArray().put(tun));

            JSONObject proxy = new JSONObject();
            proxy.put("type", "socks");
            proxy.put("tag", "proxy");
            proxy.put("server", host);
            proxy.put("server_port", port);
            proxy.put("version", "5");
            if (hasAuth()) {
                proxy.put("username", username);
                proxy.put("password", password == null ? "" : password);
            }

            JSONObject direct = new JSONObject();
            direct.put("type", "direct");
            direct.put("tag", "direct");

            JSONArray outArr = new JSONArray();
            outArr.put(proxy);
            outArr.put(direct);
            root.put("outbounds", outArr);

            JSONObject route = new JSONObject();
            route.put("auto_detect_interface", true);
            JSONArray rules = new JSONArray();
            if (!globalMode) {
                JSONObject cnRule = new JSONObject();
                cnRule.put("domain_suffix", toArray(CN_SUFFIX));
                JSONObject ruleSet = new JSONObject();
                ruleSet.put("type", "inline");
                ruleSet.put("tag", "cn");
                ruleSet.put("rules", new JSONArray().put(cnRule));

                JSONObject match = new JSONObject();
                match.put("rule_set", "cn");
                match.put("outbound", "direct");
                rules.put(match);

                route.put("rule_set", new JSONArray().put(ruleSet));
            }
            route.put("rules", rules);
            route.put("final", "proxy");
            root.put("route", route);

            return root.toString();
        } catch (Exception e) {
            throw new IllegalStateException("生成代理配置失败", e);
        }
    }

    private static JSONArray toArray(String[] values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        return array;
    }
}
