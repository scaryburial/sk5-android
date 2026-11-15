#!/usr/bin/env node
// 静态契约校验：编译前先挡住「配置写错但能编译」的问题。
// 每条断言都对应一个真实会出事的点，不做泛泛的格式检查。
const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
let passed = 0;
const failures = [];

function read(rel) {
  return fs.readFileSync(path.join(root, rel), "utf8");
}

function check(name, condition, detail) {
  if (condition) {
    passed++;
  } else {
    failures.push(`${name}${detail ? " — " + detail : ""}`);
  }
}

const manifest = read("app/src/main/AndroidManifest.xml");
const vpn = read("app/src/main/java/com/leidian/sk5/Sk5VpnService.java");
const node = read("app/src/main/java/com/leidian/sk5/SocksNode.java");
const strings = read("app/src/main/res/values/strings.xml");
const gradle = read("app/build.gradle.kts");
const proxy = read("app/src/main/java/com/leidian/sk5/ProxyManager.java");

// ── 清单与权限 ──
check("声明网络权限", manifest.includes("android.permission.INTERNET"));
check("声明前台服务权限", manifest.includes("android.permission.FOREGROUND_SERVICE"));
check("声明 VPN 绑定权限", manifest.includes("android.permission.BIND_VPN_SERVICE"));
check("VPN 服务声明 specialUse 类型", manifest.includes('foregroundServiceType="specialUse"'));
check("VPN 服务有 VpnService intent-filter", manifest.includes('android.net.VpnService'));
check("已声明开机广播接收器", manifest.includes("BootReceiver"));
check("清单有启动 Activity", manifest.includes("android.intent.action.MAIN"));
check("已注册 Application 以安装崩溃捕获", manifest.includes('android:name=".Sk5App"'));
check("崩溃记录只写不丢", read("app/src/main/java/com/leidian/sk5/CrashGuard.java").includes("uncaughtException"));
check("日志启动时能从磁盘恢复",
  read("app/src/main/java/com/leidian/sk5/LogRecorder.java").includes("KEY_TAIL"));
check("日志同步落盘（native 崩溃也不丢）",
  read("app/src/main/java/com/leidian/sk5/LogRecorder.java").includes(".commit()"));
check("能抓设备日志里的内核崩溃输出",
  read("app/src/main/java/com/leidian/sk5/LogRecorder.java").includes("GoLog".toLowerCase())
  || read("app/src/main/java/com/leidian/sk5/LogRecorder.java").includes("golog"));
check("异常结束能被下次启动识别",
  read("app/src/main/java/com/leidian/sk5/Sk5App.java").includes("wasRunning"));
check("日志中的账号密码已脱敏",
  read("app/src/main/java/com/leidian/sk5/CoreRuntime.java").includes("Redact.secrets"));
check("内核错误输出会进应用内日志",
  read("app/src/main/java/com/leidian/sk5/CoreRuntime.java").includes('LogRecorder.logInMemory("内核"'));
check("崩溃记录可在界面查看",
  read("app/src/main/java/com/leidian/sk5/MainActivity.java").includes("showCrashReportIfAny"));
check("包名统一", gradle.includes('namespace = "com.leidian.sk5"')
  && gradle.includes('applicationId = "com.leidian.sk5"'));

// ── 全局 TUN 的关键行为 ──
check("全局模式排除自身而非只代理自身",
  vpn.includes("addDisallowedApplication")
  && !new RegExp("builder\\s*\\.\\s*addAllowedApplication").test(vpn));
check("内核 socket 会被 protect", vpn.includes("protect(fd)"));
check("TUN 建立时下发全量路由", vpn.includes('addRoute("0.0.0.0", 0)'));
check("IPv6 地址缺失时先补地址再加路由", vpn.includes("FALLBACK_V6"));
check("MTU 失败会降级重试", vpn.includes("buildTun(options, 1500)"));
check("前台服务失败不崩进程", vpn.includes('启动前台服务失败'));
check("内核未给 DNS 时回落到 TUN 网关", vpn.includes('addDnsServer("172.19.0.1")'));

// ── 默认网卡回调：关闭后不能再回调 Go 对象 ──
const platform = read("app/src/main/java/com/leidian/sk5/BoxPlatform.java");
check("默认网卡回调有失效保护", platform.includes("monitoring")
  && platform.includes("if (!monitoring)"));
check("回调不延后到主线程", !platform.includes("mainHandler"));
check("网卡索引无效时不上报", platform.includes("index <= 0"));
check("上报地址去掉 IPv6 作用域（否则内核 panic）",
  platform.includes("IfAddr.toPrefix")
  && read("app/src/main/java/com/leidian/sk5/IfAddr.java").includes("stripZone")
  && read("app/src/main/java/com/leidian/sk5/IfAddr.java").includes("isLinkLocalAddress"));
check("内核提供本机回环代理用于自检", node.includes('put("type", "mixed")')
  && node.includes("CoreOptions.LOCAL_PROXY_PORT"));
check("自检区分节点与隧道",
  read("app/src/main/java/com/leidian/sk5/NetProbe.java").includes("probeNode")
  && read("app/src/main/java/com/leidian/sk5/NetProbe.java").includes("probeTunnel"));
check("DNS 解析方式可切换",
  read("app/src/main/java/com/leidian/sk5/SocksNode.java").includes("CoreOptions.DNS_DOMESTIC.equals(dnsMode)"));
check("自检包含 DNS 可达性",
  read("app/src/main/java/com/leidian/sk5/NetProbe.java").includes("probeDns"));
check("连上后会自动跑自检",
  read("app/src/main/java/com/leidian/sk5/ProxyManager.java").includes("scheduleProbe"));

// ── sing-box 配置 ──
check("使用 tun 入站", node.includes('put("type", "tun")'));
check("默认协议栈是 gvisor", node.includes("CoreOptions.DEFAULT_STACK")
  && read("app/src/main/java/com/leidian/sk5/CoreOptions.java").includes('DEFAULT_STACK = STACK_GVISOR'));
check("默认网卡跟随可关闭",
  read("app/src/main/java/com/leidian/sk5/BoxPlatform.java").includes("followInterface()"));
check("设置页有兼容开关",
  read("app/src/main/java/com/leidian/sk5/MainActivity.java").includes("buildCompatibilityCard"));
check("使用 socks 出站", node.includes('put("type", "socks")'));
check("SOCKS5 版本明确", node.includes('put("version", "5")'));
check("开启 auto_route", node.includes('put("auto_route", true)'));
check("开启 auto_detect_interface", node.includes('put("auto_detect_interface", autoDetectRoute)'));
check("DNS 经节点转发", node.includes('put("detour", "proxy")'));
check("智能分流有国内域名规则集", node.includes('put("tag", "cn")'));
check("默认出站是节点", node.includes('put("final", "proxy")'));
check("账号密码可选", node.includes("hasAuth()"));

// ── 连接顺序 ──
check("先启动 VPN 服务再启内核",
  proxy.indexOf("startForegroundService") < proxy.indexOf("CoreRuntime.get().start"));
check("连接流程在主线程执行", proxy.includes("main.post"));
check("失败时回收内核与服务", proxy.includes("CoreRuntime.get().stop()")
  && proxy.includes("stopService"));

// ── 构建配置 ──
check("minSdk 26", gradle.includes("minSdk = 26"));
check("仅打 arm64", gradle.includes('abiFilters += listOf("arm64-v8a")'));
check("release 开启混淆", gradle.includes("isMinifyEnabled = true"));
check("保留 libbox 类名", read("app/proguard-rules.pro").includes("-keep class io.nekohasekai.libbox.**"));

// ── 中文与文案 ──
check("应用名存在", new RegExp('<string name="app_name">[^<]+</string>').test(strings));
const mustChinese = ["state_connected", "err_vpn_denied", "err_link_invalid",
  "err_node_unreachable", "node_import", "settings_log"];
for (const key of mustChinese) {
  const match = strings.match(new RegExp(`<string name="${key}">([^<]*)</string>`));
  check(`文案 ${key} 存在且有中文`, !!match && /[\u4e00-\u9fa5]/.test(match[1]));
}
check("没有英文占位文案", !/TODO|FIXME|Lorem ipsum/.test(strings));

// ── 结果 ──
if (failures.length > 0) {
  console.error(`静态校验失败 ${failures.length} 项：`);
  for (const item of failures) {
    console.error("  ✗ " + item);
  }
  process.exit(1);
}
console.log(`静态校验通过：${passed} 项`);
