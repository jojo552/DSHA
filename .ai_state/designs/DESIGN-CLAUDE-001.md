# Claude Code 接入设计

关联需求：REQ-CLAUDE-001。

选择原生聊天加官方 SDK 协议，复用 PTY 进行账号登录和完整 CLI 操作。相比直接把 DSH Web 替换为 Claude，可保留原功能，并让 Android 6 的聊天不依赖新 WebView。相比仅提供终端，原生页面能够明确展示手机操作授权及多项工具提问。

运行时是可选的版本化 npm 安装，使用 Ubuntu 内固定 proot 和独立进程监督。stdout 传结构化事件，stdin 传请求及授权；API Key 经 Keystore 保存，不拼入 argv。Java 会话与 Activity 生命周期分开，流式界面节流、输出队列有背压，授权请求排队显示。

停止和维护以进程组真实退出为条件，无法确认则保留工作锁。手机 MCP 增加节点操作以及按 API 和连接状态报告的能力，不使用不确定结果后的自动重试。Android 6/7 的完整运行结果待真机验收。
