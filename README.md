# SK5

一个只做 SOCKS5 转发的 Android 客户端。没有浏览器、没有订阅、没有账号体系，
打开、填节点、点连接，整机流量走节点。

界面全中文，Material 风格，深色模式跟随系统。

## 功能

- **一键连接**：系统级 VpnService 接管整机流量，所有 App 自动走节点，无需改服务器。
- **SOCKS5 节点**：填地址/端口/账号密码，或粘贴 `socks5://user:pass@host:port` 导入。
- **两档分流**
  - 智能分流：国内域名直连，其余走节点。
  - 全局代理：全部流量经节点。
- **状态可见**：连接状态、节点延迟、已连接时长、运行日志。
- **中文错误提示**：节点不完整、链接格式错、VPN 未授权、内核启动失败，都有对应说明。
- **开机自连**：可开关，仅在已授予 VPN 权限时执行。

## 使用前提

- 自备一个可用的 SOCKS5 节点。
- Android 8.0 及以上，arm64 设备。
- 系统同一时间只能运行一个 VPN，装了其他代理类 App 需要先关掉。

## 编译

需要 JDK 17、Android SDK 35。项目只打 `arm64-v8a`。

### 1. 准备内核 AAR

`app/libs/libbox.aar` 不入库（112MB）。这是 sing-box 的 gomobile 构建产物，
本项目使用 1.14.0，需要自行获取后放到该路径：

```bash
# 从 sing-box 官方 Android 客户端仓库构建，或从同版本客户端 APK 中提取 libbox
ls -lh app/libs/libbox.aar
```

### 2. 准备签名（可选）

没有签名文件也能编译出未签名包。要出可安装的正式包，在 `signing/` 下放：

```properties
# signing/keystore.properties
storeFile=signing/your-release.jks
storePassword=***
keyAlias=***
keyPassword=***
```

该目录已在 `.gitignore` 中，不会入库。

### 3. 打包

```bash
./build.sh
```

脚本依次执行：版本号自增 → 静态契约校验（`tools/verify.js`）→ 单元测试与
release 打包 → 输出 `sk5-v<N>.apk`。任一步失败即中断，不出包。

手动执行：

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

## 实现要点

- **接管方式**：`Sk5VpnService` 建立 TUN，路由 `0.0.0.0/0` 与 `::/0`。
  内核自己的出站 socket 做 `protect`，并把本应用排除在隧道外，
  避免「App 请求 → 隧道 → 内核 → 隧道」的死循环。
- **配置生成**：`SocksNode.toSingBoxConfig(global)` 生成 sing-box 配置，
  `tun` 入站 + `socks` 出站（version 5）+ `direct`。DNS 用 DoH 并经节点解析，
  不依赖本地解析器。
- **智能分流**：只按域名后缀匹配（内置国内常用域名列表），不引入 `ip_cidr`。
  用 IP 规则意味着每个域名都要先在本地解析一次才能匹配，既慢又把访问目标暴露给本地 DNS。
- **连接顺序**：先启动 VPN 服务并等实例就绪，再启动内核——反了 `openTun`
  拿不到服务，内核会直接报错退出。

## 测试

- `tools/verify.js`：35 项静态契约断言，覆盖权限、TUN 路由、配置字段、
  连接顺序、中文文案等。
- `app/src/test`：Robolectric + JUnit，覆盖链接解析、配置生成、分流差异、
  Activity 启动、节点存储。

单测在 aarch64 上需要 `@ConscryptMode(ConscryptMode.Mode.OFF)`，
否则 conscrypt 原生库加载失败。

生成的配置可用 sing-box 本体校验字段是否合法：

```bash
sing-box check -c /tmp/sk5-smart.json
```

## 目录结构

```
app/src/main/java/com/leidian/sk5/
  MainActivity.java     三页界面：连接 / 节点 / 设置
  CircleButton.java     自绘圆形连接按钮
  ProxyManager.java     连接状态与启动顺序
  Sk5VpnService.java    全局 TUN 建立
  CoreRuntime.java      内核生命周期
  BoxPlatform.java      内核平台回调
  SocksNode.java        节点参数与配置生成
  NodeStore.java        节点与偏好存储
tools/verify.js         静态契约校验
```

## 说明

本项目只负责把流量转发到你自己的节点，不提供节点、不内置任何服务器地址。
