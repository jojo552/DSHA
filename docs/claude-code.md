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

Android 6+ 是 APK 和原生功能的兼容目标。Claude Code 在 Ubuntu ARM64 内运行，仍受手机内核、内存和 proot 兼容性影响；**未取得 Android 6/7 真机与真实 Claude 鉴权测试结果前，不能把代码检查视为已证明完整运行**。

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

GitHub 工作流 `Claude 与 Android 兼容检查` 使用标准 Linux Android 工具链编译两版并执行单元测试。`-x prepareStandardAssets` 仅跳过离线 Ubuntu 包生成，这条验证命令不交付可安装 APK。真机布局、真实登录/计费请求、后台保活及非调试 APK 的进程回收仍需设备验收。

参考：[官方安装说明](https://code.claude.com/docs/en/setup)、[Agent SDK](https://platform.claude.com/docs/en/agent-sdk/overview)、[MCP 配置](https://code.claude.com/docs/en/mcp)。
