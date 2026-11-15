package com.leidian.sk5;

/**
 * 内核兼容参数。
 *
 * 这两项是「TUN 建起来之后进程直接消失」排查用的开关：
 * 有些机型/内核组合在 system 栈或跟随默认网卡时会让 sing-box 崩溃，
 * 出问题时可以逐项关掉，而不必每次重新打包。
 */
public final class CoreOptions {

    /** gvisor 走用户态协议栈，不依赖系统对 TUN 的处理，兼容性最好 */
    public static final String STACK_GVISOR = "gvisor";
    public static final String STACK_SYSTEM = "system";
    public static final String DEFAULT_STACK = STACK_GVISOR;

    /** 内核在本机回环提供的混合代理，用于连接后的端到端自检 */
    public static final int LOCAL_PROXY_PORT = 2080;

    /** 境外 DoH，经节点解析：结果可信，但要求节点能访问 1.1.1.1 */
    public static final String DNS_REMOTE = "remote";
    /** 国内 UDP DNS，直连解析：一定可达，但被污染的域名会拿到错误地址 */
    public static final String DNS_DOMESTIC = "domestic";
    public static final String DEFAULT_DNS = DNS_REMOTE;

    private CoreOptions() {
    }
}
