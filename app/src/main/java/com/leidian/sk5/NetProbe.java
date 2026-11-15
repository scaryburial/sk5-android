package com.leidian.sk5;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 两级网络自检，用来区分「节点不行」和「隧道不行」。
 *
 * 第一级直连节点做 SOCKS5 握手并请求建立到目标站点的连接，
 * 第二级通过内核在本机 2080 的混合代理再建一次——
 * 只有第二级成功，才说明「手机 → 节点 → 目标」整条链路真的通了。
 */
public final class NetProbe {

    /** 用 GitHub 做目标：这正是用户要用它的理由 */
    static final String TARGET_HOST = "github.com";
    static final int TARGET_PORT = 443;

    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 10000;
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    public static final class Result {
        public final boolean ok;
        public final int ms;
        public final String detail;

        Result(boolean ok, int ms, String detail) {
            this.ok = ok;
            this.ms = ms;
            this.detail = detail;
        }
    }

    public interface Callback {
        /** node 节点级，dns 解析可达性，tunnel 端到端 */
        void onResult(Result node, Result dns, Result tunnel);
    }

    private NetProbe() {
    }

    public static void run(final SocksNode node, final Callback callback) {
        POOL.execute(() -> {
            Result nodeResult = probeNode(node);
            Result dnsResult = nodeResult.ok ? probeDns() : new Result(false, -1, "节点不通，未测");
            Result tunnelResult = nodeResult.ok ? probeTunnel() : new Result(false, -1, "节点不通，未测");
            UiKit.runOnMain(() -> callback.onResult(nodeResult, dnsResult, tunnelResult));
        });
    }

    /**
     * 经节点访问 DoH 服务器本身。
     *
     * 很多国内节点连不上 1.1.1.1，这会让「经节点解析」的 DNS 全失败，
     * 表现就是隧道建起来了、什么都没网。单独测这一步能直接区分责任。
     */
    static Result probeDns() {
        return probeThroughLocal("1.1.1.1", 443, "DoH 服务器 1.1.1.1");
    }

    /** 直连节点：完整走一遍 SOCKS5 握手与 CONNECT */
    static Result probeNode(SocksNode node) {
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(node.host, node.port), CONNECT_TIMEOUT);
            socket.setSoTimeout(READ_TIMEOUT);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            byte[] greeting = node.hasAuth()
                    ? new byte[]{0x05, 0x02, 0x00, 0x02}
                    : new byte[]{0x05, 0x01, 0x00};
            out.write(greeting);
            out.flush();

            byte[] hello = readFully(in, 2);
            if (hello[0] != 0x05) {
                return fail("节点不是 SOCKS5 服务");
            }
            int method = hello[1] & 0xFF;
            if (method == 0xFF) {
                return fail("节点拒绝了所有认证方式");
            }
            if (method == 0x02) {
                if (!node.hasAuth()) {
                    return fail("节点要求用户名密码，但没填");
                }
                byte[] user = node.username.getBytes(StandardCharsets.UTF_8);
                byte[] pass = (node.password == null ? "" : node.password)
                        .getBytes(StandardCharsets.UTF_8);
                ByteArrayOutputStream auth = new ByteArrayOutputStream();
                auth.write(0x01);
                auth.write(user.length);
                auth.write(user);
                auth.write(pass.length);
                auth.write(pass);
                out.write(auth.toByteArray());
                out.flush();
                byte[] authReply = readFully(in, 2);
                if ((authReply[1] & 0xFF) != 0x00) {
                    return fail("认证失败：用户名或密码不对");
                }
            } else if (method != 0x00) {
                return fail("节点要求不支持的认证方式 " + method);
            }

            byte[] host = TARGET_HOST.getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            request.write(0x05);
            request.write(0x01);
            request.write(0x00);
            request.write(0x03);
            request.write(host.length);
            request.write(host);
            request.write((TARGET_PORT >> 8) & 0xFF);
            request.write(TARGET_PORT & 0xFF);
            out.write(request.toByteArray());
            out.flush();

            byte[] reply = readFully(in, 4);
            if (reply[0] != 0x05) {
                return fail("节点的 CONNECT 响应格式不对");
            }
            int code = reply[1] & 0xFF;
            if (code != 0x00) {
                return fail("节点拒绝连接目标站点：" + replyText(code));
            }
            int cost = (int) (System.currentTimeMillis() - start);
            return new Result(true, cost, "握手与建连成功（" + cost + " 毫秒）");
        } catch (SocketTimeoutException e) {
            return fail("节点无响应（超时）");
        } catch (IOException e) {
            return fail("连不上节点：" + shortError(e));
        }
    }

    /** 经内核 2080 回环代理建连，验证整条链路 */
    static Result probeTunnel() {
        return probeThroughLocal(TARGET_HOST, TARGET_PORT, TARGET_HOST);
    }

    private static Result probeThroughLocal(String host, int port, String label) {
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", CoreOptions.LOCAL_PROXY_PORT), 3000);
            socket.setSoTimeout(READ_TIMEOUT);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String request = "CONNECT " + host + ":" + port + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String status = readLine(in);
            if (status == null) {
                return fail("本机代理无响应");
            }
            if (!status.contains(" 200")) {
                return fail("经节点访问 " + label + " 被拒：" + status.trim());
            }
            int cost = (int) (System.currentTimeMillis() - start);
            return new Result(true, cost, "经节点访问 " + label + " 成功（" + cost + " 毫秒）");
        } catch (SocketTimeoutException e) {
            return fail("经节点访问 " + label + " 超时");
        } catch (IOException e) {
            return fail("本机代理连不上：内核可能没起来（" + shortError(e) + "）");
        }
    }

    private static Result fail(String detail) {
        return new Result(false, -1, detail);
    }

    private static String shortError(IOException e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static String replyText(int code) {
        switch (code) {
            case 0x01:
                return "服务器一般性故障";
            case 0x02:
                return "规则不允许";
            case 0x03:
                return "网络不可达";
            case 0x04:
                return "主机不可达";
            case 0x05:
                return "目标拒绝连接";
            case 0x06:
                return "TTL 过期";
            case 0x07:
                return "不支持的命令";
            case 0x08:
                return "不支持的地址类型";
            default:
                return "错误码 " + code;
        }
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read < 0) {
                throw new IOException("连接被关闭");
            }
            offset += read;
        }
        return buffer;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        while (line.length() < 256) {
            int value = in.read();
            if (value < 0) {
                return line.length() == 0 ? null : line.toString();
            }
            if (value == '\n') {
                return line.toString();
            }
            if (value != '\r') {
                line.append((char) value);
            }
        }
        return line.toString();
    }
}
