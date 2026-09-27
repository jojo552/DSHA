# 在 Android 手机上使用 Claude Code

本功能在 `jojo552/DSHA` 的 `feat/claude-code-android6` 分支实现（沿用原 PR 分支名）。当前最低支持 **Android 10 / API 29 / ARM64**，standard 版仍要求 Android 11+。启动页的 Claude Code 使用原生 Android 控件；原有 DSH 和手机控制功能均保留。

## 使用

1. 完成 DSHA 的 Ubuntu 环境安装。Android 10 使用 **low 兼容版**，Android 11+ 可选择 standard。
2. 进入 **Claude Code → 更多 → 安装 Claude Code**。该可选组件需要联网，失败保留原可用版本与安装现场。
3. 在 **更多 → 登录 Claude 账号** 发起登录，自动打开手机浏览器中的官方授权页，由你完成登录和授权。也可在 **更多 → 连接配置** 保存 API Key、模型及 API 地址；密钥使用 Android Keystore 加密，原生聊天通过 stdin 传入子进程。
4. 回到聊天页输入并发送消息。无需开启无障碍。发送与停止分别显示；任务进行中可先编写下一条草稿。

Claude 提问时可以直接点选单选或多选答案，也可以自行填写。点选与自定义回答互斥，提交前检查每题都有答案；旋转会保留当前提问的选择、输入草稿和阅读位置。上滑阅读历史时不会被新输出拉回底部，点 **最新消息** 恢复跟随。横屏键盘避免全屏编辑模式。

**更多** 中保留连接配置、账号登录、完整终端、新对话和可选的手机控制授权。新对话只重置当前页面和续聊编号，官方 CLI 会话文件继续保留。旋转或切页不会结束任务，可从通知返回或停止；进程被系统回收后不自动重放请求。

## 登录授权自动跳转

登录入口为这一轮官方 `claude auth login` 设置专用 `BROWSER`，通过现有带 token 的本机桥请求 Android 打开浏览器。只接受官方 HTTPS OAuth 授权地址；不自动确认账号授权，不保存授权码，不记录完整 URL。授权 state、PKCE 和本机回调仍由官方 CLI 管理。

浏览器授权完成后回到 DSHA 查看 CLI 的登录结果。若官方流程给出登录码，请粘贴到终端提示处；自动打开失败时，终端中的原始链接和输入通道仍可用。**打开浏览器不等于登录成功**。参见[官方登录说明](https://code.claude.com/docs/en/authentication)。

完整终端复用现有 PTY、软键盘和终端标签，并使用 CLI 自身账号/配置；原生页面填写的 API Key、模型及 API 地址只传给原生聊天。普通完整终端不会自动打开其中出现的任意链接。

## 可选手机控制

聊天和编程不要求开启此功能。如果希望 Claude 帮你操作其他应用，可从 **更多 → 可选：手机控制授权** 启用原有无障碍服务；逐次操作仍有原生确认，MCP 的 9 个工具全部保留。

| 操作 | Android 10 / API 29 | Android 11+ / API 30+ |
|---|---|---|
| 查询能力 | 支持 | 支持 |
| 读取控件、文字点击、输入、导航、控件滚动 | 授权后支持 | 授权后支持 |
| 坐标点击与滑动 | 授权后支持 | 授权后支持 |
| 无障碍截图 | 不支持 | 授权后支持，受保护窗口除外 |

未连接无障碍服务时，相关能力明确报为不可用。无控件树的画布、游戏等应用仍可能无法操作。现有 Root、Shizuku、ADB 功能保留，本轮没有增加自动切换通道重放操作。

最低系统版本是 APK 的安装门槛；Claude 在 Ubuntu ARM64 内的运行仍取决于手机内核、内存与 proot 兼容性。

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
node tools/test-claude-browser.mjs
node tools/test-android-computer-use.mjs
python3 tools/test-bridge-routes.py
bash -n app/src/main/assets/claude/install.sh
./gradlew :app:testStandardDebugUnitTest :app:testLowDebugUnitTest -x prepareStandardAssets
```

SDK 测试使用模拟的官方查询接口，覆盖流式输出、续聊、并行授权、拒绝、提问、取消、错误和有界协议输入；手机 MCP 测试验证发现、参数、取消与错误识别，不发送真实设备操作。Java 测试覆盖能力差异、协议边界、单选/多选、自定义答案与草稿恢复。浏览器测试覆盖链接白名单、桥鉴权、错误和敏感信息保护。CI 安装锁定 CLI 后运行 `node tools/check-claude-login.mjs`，在临时空配置中验证真实 BROWSER 调用，不提交账号授权。

2026-09-27 本机结果：两版各 630 项单测，629 通过、1 项 POSIX 环境条件跳过、0 失败；两版 Java 编译通过，low 合并 Manifest 的 minSdk 为 29。新增 5 项浏览器测试、SDK/MCP 协议和 6 项桥路由测试通过，中英文文案字典生成成功。官方 CLI 的真实 BROWSER 调用由 CI 单独验证。

2026-09-26 结果：以上检查全部通过。GitHub 工作流 [Claude 与 Android 兼容检查 run 36238981382](https://github.com/jojo552/DSHA/actions/runs/36238981382) 在标准 Linux Android 工具链上完成官方 CLI 安装与 SDK 入口检查，并编译两版执行单元测试 —— 两版各 625 项（合计 1250 项），0 失败、0 跳过。本地同一组 node/python/语法检查通过；本地两版单测 624 项通过、1 项因 POSIX 环境条件跳过。

GitHub 工作流 `Claude 与 Android 兼容检查` 使用标准 Linux Android 工具链编译两版并执行单元测试。`-x prepareStandardAssets` 仅跳过离线 Ubuntu 包生成，这条验证命令不交付可安装 APK。本轮手机界面、浏览器跳转、真实账号授权/模型请求、后台保活及非调试 APK 进程回收仍需真机验收。上一轮手机桥的已测范围见下方历史结果。

## 当前手机使用验收（Android 10+）

在短屏、横屏、日夜主题、中英文和 1.3 倍字体下，检查聊天、菜单与提问可点可滚动；键盘弹出后仍能发送/停止。连续输出时上滑阅读应保留位置，点最新消息回底。回答一部分问题后旋转应保留选择与草稿，新一轮提问不应带入上一题答案。点击登录应打开手机浏览器，取消授权后可返回终端；登录成功后再验证一次真实聊天。

布局统一性可运行 `LayoutAuditInstrumentation` 的 `style` 验收；自动化编译不能替代真机结果。

## 可选手机控制验收（保留原测试入口）

本节用于可选手机控制，与 Claude 聊天的必需步骤无关。请在**安装对应 PR 构建的 low 兼容版**之后执行。覆盖安装必须用发布用的那把 keystore（`DSHA_KEYSTORE`）：Android 只允许同签名覆盖安装，换密钥就只能卸载重装，会丢掉已有环境与数据。

执行方式：`node tools/accept-android-device.mjs`（在手机容器内跑，`--static` 只做不需要确认的项，`--gesture` 加验坐标手势）。手机旁要有人，每次手机操作都会弹原生确认。

1. **能力查询**：`/app/ui/capabilities` 在 API 29 上应报 `android_get_state`、`android_click_text`、`android_type`、`android_key`、`android_scroll`、`android_click`、`android_swipe` 为 true，`android_screenshot` 为 false，guidance 提示该通道不支持截图。未连接无障碍服务时同一查询必须把这些动作标为不可用，不能把系统版本支持报成已授权。
2. **文字点击**：`/app/ui/tap?text=<界面上的文字>` 应命中控件并弹原生确认；拒绝时返回 `[ERR] 你拒绝了这次点击`。
3. **控件滚动**：`/app/ui/scroll?direction=forward` 返回 `OK 已请求滚动，请重新读屏确认` 或 `[ERR] 当前页面没有可滚动控件`；`direction=left` 必须返回 `[ERR] 无效的滚动方向`，不能当成成功。
4. **坐标手势**：Android 10+ 的坐标点击与滑动应在授权后可用；拒绝或未连接时不能静默报告成功，也不能自动切换通道重放。旧 API 的兼容分支保留为回归测试。
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
