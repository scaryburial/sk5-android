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

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 网络自检。
 *
 * 必须真的走一遍数据流才算数。sing-box 的 HTTP 入站在**拨号之前**就回
 * "200 Connection established"（见 sing@v0.9.3 protocol/http/handshake.go），
 * 所以「CONNECT 拿到 200」只证明本机代理活着，完全不能说明隧道通。
 * 这里的每一项都要求后续真的有字节往返：TLS 握手 + 读到真实 HTTP 响应。
 */
public final class NetProbe {

    static final String TARGET_HOST = "github.com";
    static final int TARGET_PORT = 443;

    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 15000;
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

    public interface ReachCallback {
        void onResult(boolean reachable);
    }

    private NetProbe() {
    }

    public static void run(final SocksNode node, final Callback callback) {
        POOL.execute(() -> {
            Result nodeResult = probeNode(node);
            Result dnsResult = nodeResult.ok ? probeDns(node) : skipped("节点不通");
            Result tunnelResult = nodeResult.ok ? probeTunnel() : skipped("节点不通");
            UiKit.runOnMain(() -> callback.onResult(nodeResult, dnsResult, tunnelResult));
        });
    }

    /** 节点能否访问境外 DoH。DNS 自动模式靠它选路 */
    public static void probeDohViaNode(final SocksNode node, final ReachCallback callback) {
        POOL.execute(() -> {
            boolean reachable = probeDns(node).ok;
            UiKit.runOnMain(() -> callback.onResult(reachable));
        });
    }

    /**
     * 直连节点：SOCKS5 握手 + 请求建连 GitHub。
     * 节点必须真的连上目标才会回成功码，所以这一项是真实往返。
     */
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

            int code = socksConnect(out, in, TARGET_HOST, TARGET_PORT);
            if (code != 0x00) {
                return fail("节点拒绝连接 GitHub：" + replyText(code));
            }
            int cost = (int) (System.currentTimeMillis() - start);
            return new Result(true, cost, "握手与建连成功（" + cost + " 毫秒）");
        } catch (SocketTimeoutException e) {
            return fail("节点无响应（超时）");
        } catch (IOException e) {
            return fail("连不上节点：" + shortError(e));
        }
    }

    /**
     * 经节点访问 1.1.1.1 并完成 TLS 握手。
     * 只建 TCP 不够——DoH 要用 443，443 不通就等于 DNS 全废。
     */
    static Result probeDns(SocksNode node) {
        long start = System.currentTimeMillis();
        try (Socket socket = connectViaNode(node, "1.1.1.1", 443)) {
            SSLSocket tls = startTls(socket, "cloudflare-dns.com");
            tls.close();
            int cost = (int) (System.currentTimeMillis() - start);
            return new Result(true, cost, "经节点访问 DoH 成功，TLS 已握手（" + cost + " 毫秒）");
        } catch (SocketTimeoutException e) {
            return fail("经节点访问 1.1.1.1 超时：节点到不了境外 DoH");
        } catch (IOException e) {
            return fail("经节点访问 1.1.1.1 失败：" + shortError(e));
        } catch (IllegalStateException e) {
            return fail(e.getMessage());
        }
    }

    /**
     * 端到端：经内核 2080 建连，然后 TLS 握手 + 发一个真实 HTTP 请求，读到状态行。
     * 只有这里成功，才说明「内核 → 节点 → 目标」整条链路真的能传数据。
     */
    static Result probeTunnel() {
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", CoreOptions.LOCAL_PROXY_PORT), 3000);
            socket.setSoTimeout(READ_TIMEOUT);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(("CONNECT " + TARGET_HOST + ":" + TARGET_PORT + " HTTP/1.1\r\n"
                    + "Host: " + TARGET_HOST + ":" + TARGET_PORT + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String status = readLine(in);
            if (status == null) {
                return fail("本机代理无响应");
            }
            if (!status.contains(" 200")) {
                return fail("本机代理拒绝了建连：" + status.trim());
            }

            // 到这里只能说明入站活着，必须继续握手与收发才算通过
            SSLSocket tls = startTls(socket, TARGET_HOST);
            OutputStream tlsOut = tls.getOutputStream();
            InputStream tlsIn = tls.getInputStream();
            tlsOut.write(("GET / HTTP/1.1\r\nHost: " + TARGET_HOST
                    + "\r\nConnection: close\r\nUser-Agent: SK5\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            tlsOut.flush();
            String httpStatus = readLine(tlsIn);
            tls.close();
            if (httpStatus == null || !httpStatus.startsWith("HTTP/")) {
                return fail("隧道里没有收到 HTTP 响应");
            }
            int cost = (int) (System.currentTimeMillis() - start);
            return new Result(true, cost, "经隧道访问 GitHub 成功（" + cost + " 毫秒，" + httpStatus.trim() + "）");
        } catch (SocketTimeoutException e) {
            return fail("隧道无响应（超时）：连接建立了但数据出不去");
        } catch (IOException e) {
            return fail("隧道不通：" + shortError(e));
        } catch (IllegalStateException e) {
            return fail(e.getMessage());
        }
    }

    // ── 内部工具 ──

    /** 经节点的 SOCKS5 建连，返回已连通目标的 socket */
    private static Socket connectViaNode(SocksNode node, String host, int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(node.host, node.port), CONNECT_TIMEOUT);
        socket.setSoTimeout(READ_TIMEOUT);
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        out.write(node.hasAuth()
                ? new byte[]{0x05, 0x02, 0x00, 0x02}
                : new byte[]{0x05, 0x01, 0x00});
        out.flush();
        byte[] hello = readFully(in, 2);
        if (hello[0] != 0x05) {
            socket.close();
            throw new IllegalStateException("节点不是 SOCKS5 服务");
        }
        int method = hello[1] & 0xFF;
        if (method == 0x02) {
            if (!node.hasAuth()) {
                socket.close();
                throw new IllegalStateException("节点要求用户名密码，但没填");
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
            if ((readFully(in, 2)[1] & 0xFF) != 0x00) {
                socket.close();
                throw new IllegalStateException("认证失败：用户名或密码不对");
            }
        } else if (method != 0x00) {
            socket.close();
            throw new IllegalStateException("节点要求不支持的认证方式 " + method);
        }

        int code = socksConnect(out, in, host, port);
        if (code != 0x00) {
            socket.close();
            throw new IllegalStateException("节点拒绝连接 " + host + "：" + replyText(code));
        }
        return socket;
    }

    private static int socksConnect(OutputStream out, InputStream in, String host, int port)
            throws IOException {
        byte[] name = host.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(0x05);
        request.write(0x01);
        request.write(0x00);
        request.write(0x03);
        request.write(name.length);
        request.write(name);
        request.write((port >> 8) & 0xFF);
        request.write(port & 0xFF);
        out.write(request.toByteArray());
        out.flush();

        byte[] reply = readFully(in, 4);
        if (reply[0] != 0x05) {
            throw new IOException("CONNECT 响应格式不对");
        }
        int addressType = reply[3] & 0xFF;
        if (addressType == 0x01) {
            readFully(in, 4);
        } else if (addressType == 0x04) {
            readFully(in, 16);
        } else if (addressType == 0x03) {
            int length = readFully(in, 1)[0] & 0xFF;
            readFully(in, length);
        }
        readFully(in, 2);
        return reply[1] & 0xFF;
    }

    private static SSLSocket startTls(Socket socket, String host) throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket tls = (SSLSocket) factory.createSocket(socket, host, TARGET_PORT, true);
        tls.setSoTimeout(READ_TIMEOUT);
        tls.startHandshake();
        return tls;
    }

    private static Result skipped(String reason) {
        return new Result(false, -1, reason + "，未测");
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
