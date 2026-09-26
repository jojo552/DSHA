# Claude Code 与手机操作

本功能在 `jojo552/DSHA` 的 `feat/claude-code-android6` 分支实现。启动页新增独立的 Claude Code 入口；原有 DSH 入口继续可用。聊天使用原生 Android 控件，不依赖系统 WebView 的 JavaScript 版本。

## 使用

1. 在 DSHA 中完成 Ubuntu 环境安装。Android 6–10 使用 **low 兼容版**，Android 11+ 可以使用 standard；两版都只支持 ARM64。
2. 打开启动页的 **Claude Code**，点 **安装 Claude Code**。该可选组件需要联网下载，失败会保留当前可用版本与安装现场。
3. 选择一种鉴权方式：在 **连接配置** 中保存 Anthropic API Key，或打开 **登录 / 完整终端 → 登录 Claude 账号**，按官方 CLI 给出的链接完成登录。API Key 加密保存在 Android Keystore 保护的配置中，通过子进程 stdin 传递，不拼入 shell 命令。
4. 回到原生页面发送消息。可以选择模型和自定义 HTTPS API 地址；留空使用默认设置。HTTP 仅允许本机回环地址。自建服务必须兼容 Claude Code 所用的 Anthropic API。
5. 首次操作手机，点 **手机操作授权** 打开系统无障碍设置并启用 DSHA。Claude 会先查询设备能力，再读取页面与执行操作；每次手机操作会请求原生确认。

原生聊天支持流式文字、工具提示、允许/拒绝、Claude 的提问、取消和续聊。旋转或离开页面保留正在执行的任务，可从通知返回或停止；进程被系统回收后不自动重放请求。**新对话**重置当前页面及续聊编号，官方 CLI 保存的会话文件继续保留。

完整终端用于官方交互式 CLI 与登录，复用现有 PTY、软键盘和终端标签。它使用官方 CLI 自身的账号/配置；原生页面填写的 API Key、模型和 API 地址仅传给原生聊天。若登录时没有自动打开浏览器，可复制终端中的链接到浏览器完成验证。

## Android 能力矩阵

以下为本次 MCP 接入的无障碍通道，不代表 Android 版本和授权到位后所有应用都一定暴露控件。

| 操作 | Android 6 / API 23 | Android 7–10 / API 24–29 | Android 11+ / API 30+ |
|---|---|---|---|
| 能力查询 | 支持 | 支持 | 支持 |
| 读取文字与控件树 | 支持 | 支持 | 支持 |
| 按文字点击、输入、返回/主页 | 支持 | 支持 | 支持 |
| 对可滚动控件翻页 | 支持 | 支持 | 支持 |
| 坐标点击、滑动手势 | 不支持，使用节点操作 | 支持 | 支持 |
| 无障碍截图 | 不支持 | 不支持 | 支持，受保护窗口除外 |

未连接无障碍服务时，能力查询会把相关动作标为不可用。Android 6 不会把“手势不支持”误报为成功。无控件树的游戏、画布或特殊应用仍可能无法操作。该接入没有增加 Root/ADB 截图或手势回退，也不自动切换通道重放操作。

Android 6+ 是 APK 和原生功能的兼容目标。Claude Code 在 Ubuntu ARM64 内运行，仍受手机内核、内存和 proot 兼容性影响。验收口径已收敛到 **Android 10 及以下**（low 兼容版），Android 6/7 真机不作为前置条件；**在真机安装本次构建并完成一次真实鉴权前，仍不能把代码检查视为已证明完整运行**。

## 实现与数据

- 固定安装 `@anthropic-ai/claude-code@2.1.283` 与 `@anthropic-ai/claude-agent-sdk@0.3.283`，依赖由 `app/src/main/assets/claude/package-lock.json` 锁定。更新时同步包清单、锁文件和安装目录版本。
- 组件存放于 `/root/.local/share/dsha-claude/`。安装先检查 CLI 可执行，再原子切换 `current`，不修改 Ubuntu 基础环境版本。
- SDK 通过标准输入输出与 Java 会话通信。主聊天使用 DSH 配置中的 Ubuntu 工作目录，凭据配置独立；续聊使用官方 SDK 的 `resume`。
- 工作锁覆盖安装和请求全过程，停止按出生身份回收独立进程组，确认退出后才允许环境维护。备份/恢复等维护入口也会停止 Claude。
- 手机 MCP 复用 `dsh-computer-use-android` 的 stdio 服务和带 token 的本机桥，新增能力查询、文字点击和控件滚动。工具授权采用 SDK 默认权限模式，手机原生确认额外生效。
- Claude 的官方会话与登录信息位于其 Ubuntu 用户目录；原生页面保留最近约 13 万字符及续聊编号。现有 DSH 备份没有为这些 Claude 私有路径增加专用恢复规则，不能把 DSH 会话备份当作 Claude 的完整备份。

Claude Code 组件按用户点击安装时从 npm 下载，其使用与授权遵循 Anthropic 对应条款；本仓库不内置 Anthropic 的 CLI 二进制或账号凭据。

## 自动化验证

```sh
node tools/test-claude-agent.mjs
node tools/test-android-computer-use.mjs
python3 tools/test-bridge-routes.py
bash -n app/src/main/assets/claude/install.sh
./gradlew :app:testStandardDebugUnitTest :app:testLowDebugUnitTest -x prepareStandardAssets
```

SDK 测试使用模拟的官方查询接口，覆盖流式输出、续聊、并行授权、拒绝、提问、取消、错误和有界协议输入；手机 MCP 测试验证发现、参数、取消与错误识别，不发送真实设备操作。Java 测试覆盖 API 23/24/29/30 能力差异和协议边界。

2026-09-26 结果：以上检查全部通过。GitHub 工作流 [Claude 与 Android 兼容检查 run 36238981382](https://github.com/jojo552/DSHA/actions/runs/36238981382) 在标准 Linux Android 工具链上完成官方 CLI 安装与 SDK 入口检查，并编译两版执行单元测试 —— 两版各 625 项（合计 1250 项），0 失败、0 跳过。本地同一组 node/python/语法检查通过；本地两版单测 624 项通过、1 项因 POSIX 环境条件跳过。

GitHub 工作流 `Claude 与 Android 兼容检查` 使用标准 Linux Android 工具链编译两版并执行单元测试。`-x prepareStandardAssets` 仅跳过离线 Ubuntu 包生成，这条验证命令不交付可安装 APK。真机布局、真实登录/计费请求、后台保活及非调试 APK 的进程回收仍需设备验收：Android 10 设备上已安装的 `0.1.7-alpha2low` 尚不含本次新增的 `/app/ui/capabilities` 与 `/app/ui/scroll`，需安装本次构建后复验。

## 真机验收（Android 10 及以下）

已发布的 `0.1.7-alpha2low` 没有本次新增的桥端点，所以本节要在**安装本次构建的 low 兼容版**之后执行。覆盖安装必须用发布用的那把 keystore（`DSHA_KEYSTORE`）：Android 只允许同签名覆盖安装，换密钥就只能卸载重装，会丢掉已有环境与数据。

执行方式：`node tools/accept-android-device.mjs`（在手机容器内跑，`--static` 只做不需要确认的项，`--gesture` 加验坐标手势）。手机旁要有人，每次手机操作都会弹原生确认。

1. **能力查询**：`/app/ui/capabilities` 在 API 29 上应报 `android_get_state`、`android_click_text`、`android_type`、`android_key`、`android_scroll`、`android_click`、`android_swipe` 为 true，`android_screenshot` 为 false，guidance 提示该通道不支持截图。未连接无障碍服务时同一查询必须把这些动作标为不可用，不能把系统版本支持报成已授权。
2. **文字点击**（Android 6 起可用）：`/app/ui/tap?text=<界面上的文字>` 应命中控件并弹原生确认；拒绝时返回 `[ERR] 你拒绝了这次点击`。
3. **控件滚动**：`/app/ui/scroll?direction=forward` 返回 `OK 已请求滚动，请重新读屏确认` 或 `[ERR] 当前页面没有可滚动控件`；`direction=left` 必须返回 `[ERR] 无效的滚动方向`，不能当成成功。
4. **坐标手势**：API 24 及以上的坐标点击与滑动可用；API 23 必须返回 `[ERR] 手势点按需 Android 7+；Android 6 请使用文字点击` / `[ERR] 手势滑动需 Android 7+；Android 6 请使用控件滚动`，不能静默失败或自动改成别的通道重放。
5. **MCP 发现**：`tools/list` 应返回 9 个工具（新增 `android_capabilities`、`android_click_text`、`android_scroll`）；参数不合法时按协议返回错误码，不发送设备操作。
6. **界面**：启动页 Claude Code 入口、连接配置、聊天流式输出与授权弹窗在短屏和 1.3 倍字体下可点可滚动；旋转、切页不丢正在执行的任务，通知可回到任务。

### 2026-09-26 真机结果（Android 10 / API 29，vivo V1914A）

用并存安装包（`com.dsh.client.verify`，versionCode 145 / `0.1.7-alpha2-verifylow`，minSdk 23，由本分支构建）在真机执行 `node tools/accept-android-device.mjs --static`：**通过 4、失败 0、跳过 4**。

| 检查 | 结果 |
|---|---|
| `/app/version` 是本次构建 | ✅ BRIDGE_PROTOCOL=2 / APP_CODE=145 |
| `/app/ui/capabilities` 字段语义 | ✅ API=29、`screenshot=false`；未连接无障碍时**全部动作报 false**，未把系统版本支持误报为已授权；guidance 正确提示该通道不支持截图 |
| `/app/ui/scroll` 参数校验 | ✅ `direction=left` 与缺参都返回 `[ERR] 无效的滚动方向`，不弹确认 |
| 内置插件工具随包同步 | ✅ 9 个（`android_capabilities`、`android_click_text`、`android_scroll` + 原有 6 个） |

**未覆盖**（用户本轮不启用无障碍，脚本按"未覆盖"记录，不记为失败）：控件滚动、文字点击、坐标手势三个动作，以及第 6 条界面观感。因此本结果只证明端点存在性、能力语义、参数校验与插件同步，**不证明手机操作动作在真机可用**。

### 没有发布签名时：并存安装（已授权）

E7E3 发布密钥由上游持有，fork 侧拿不到，因此覆盖安装拿不到同签名包。经用户明确重新授权（2026-09-26），本轮的**功能可用性验收**可以用隔离包名的并存安装；**发布交付仍必须用同签名正式 Release 覆盖安装**。

```sh
# Windows: set DSHA_ENABLE_PARALLEL_INSTALL=1 后再执行 gradlew.bat
DSHA_ENABLE_PARALLEL_INSTALL=1 bash build.sh :app:assembleLowRelease -I tools/parallel-install.init.gradle
```

没有 PC 也能出这个包：完整版 APK 需要 `app/src/main/assets/{offline-rootfs,dsh-runtime,ubuntu-tools}.bin` 三份输入，它们在仓库里不提交，但**官方 Release 的 APK 里就有原样的一份**。把它们从官方 APK 取出来放进 assets，再按仓库锁算出 `dsh-runtime.inputs.json` 与 `ubuntu-tools.inputs.json`（后者含 rootfs 内 `var/lib/dpkg/status` 的摘要），就能在任意 x86_64 Linux（含 CI）上构建——本次就是这样在服务器上重压 rootfs 并打包的。另需注意：`signingConfigs.publish` 在缺 `DSHA_KEYSTORE` 时**不会**自动退回 debug keystore，release 打包会直接失败，自测需自备一把测试签名。

产物包名为 `com.dsh.client.verify`，与正式包并存，未配置 `DSHA_KEYSTORE` 时用本机 debug keystore 签名。判读时记住三条限制：桥端口 3090 与 web 端口 3080 是固定端口，隔离包与正式包不能同时对外服务，跑桥验收前先停掉正式包环境；隔离包要自建一份 Ubuntu 环境（2–3 GB）；包内写死 `com.dsh.client` 的几处（ADB 保活授权、设备策略豁免）只对正式包生效。

参考：[官方安装说明](https://code.claude.com/docs/en/setup)、[Agent SDK](https://platform.claude.com/docs/en/agent-sdk/overview)、[MCP 配置](https://code.claude.com/docs/en/mcp)。
