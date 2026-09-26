'use strict';
// 每轮使用官方 SDK；应用只传输结构化事件和用户授权，不解析终端 ANSI。
const {pathToFileURL} = require('node:url');
const crypto = require('node:crypto');
const RUNTIME = '/root/.local/share/dsha-claude/current';
const MCP = '/root/dsha-computer-use-android/lib/server.cjs';
const GUIDANCE = '你运行在 Android 上的 DSHA Ubuntu 环境中。手机操作先调用 android_capabilities，读取页面后再操作并验证。Android 6 优先使用 android_click_text 和 android_scroll。能力不可用时如实说明，不重复执行结果未知的操作。';

class AgentBridge {
  constructor(query, send) {
    this.query = query; this.send = send; this.pending = new Map();
    this.active = null; this.abort = null; this.closed = false; this.started = false;
  }
  async permission(tool, input, {signal} = {}) {
    if (this.closed || signal?.aborted) return {behavior: 'deny', message: '会话已取消'};
    const id = crypto.randomUUID();
    return new Promise(resolve => {
      const settle = value => {
        this.pending.delete(id); signal?.removeEventListener('abort', cancel); resolve(value);
      };
      const cancel = () => settle({behavior: 'deny', message: '会话已取消'});
      this.pending.set(id, {settle, tool, input});
      signal?.addEventListener('abort', cancel, {once: true});
      this.send({type: 'permission', id, tool, input});
    });
  }
  answer(message) {
    const request = this.pending.get(message.id);
    if (!request) return;
    if (message.allow !== true) {
      request.settle({behavior: 'deny', message: '用户拒绝了这次操作'}); return;
    }
    let input = request.input;
    if (request.tool === 'AskUserQuestion') {
      const questions = Array.isArray(input.questions) ? input.questions : [];
      if (!message.answers || questions.some(q => typeof message.answers[q.question] !== 'string' || !message.answers[q.question].trim())) return;
      input = {...input, answers: message.answers};
    }
    request.settle({behavior: 'allow', updatedInput: input});
  }
  async run(request) {
    if (this.closed) throw Error('会话已取消');
    if (this.started) throw Error('每个进程只能接收一轮请求');
    this.started = true;
    if (typeof request.prompt !== 'string' || !request.prompt.trim() || request.prompt.length > 32000)
      throw Error('请输入不超过 32000 字符的消息');
    if (typeof request.cwd !== 'string' || !request.cwd.startsWith('/') || request.cwd.includes('\0'))
      throw Error('工作目录必须是 Ubuntu 内的绝对路径');
    if (request.resume && !/^[a-f0-9-]{36}$/i.test(request.resume)) throw Error('无效的会话编号');
    if (request.baseUrl) {
      const endpoint = new URL(request.baseUrl);
      if (endpoint.username || endpoint.password || endpoint.search || endpoint.hash ||
          endpoint.protocol !== 'https:' && !(endpoint.protocol === 'http:' && ['localhost','127.0.0.1','[::1]'].includes(endpoint.hostname)))
        throw Error('API 地址必须使用 HTTPS 或本机 HTTP');
    }
    this.abort = new AbortController();
    const env = {...process.env};
    if (request.apiKey) env.ANTHROPIC_API_KEY = request.apiKey;
    if (request.baseUrl) env.ANTHROPIC_BASE_URL = request.baseUrl;
    const options = {
      cwd: request.cwd, env, abortController: this.abort, includePartialMessages: true,
      permissionMode: 'default', settingSources: ['user', 'project'],
      pathToClaudeCodeExecutable: RUNTIME + '/node_modules/.bin/claude',
      systemPrompt: {type: 'preset', preset: 'claude_code', append: GUIDANCE},
      canUseTool: this.permission.bind(this),
      mcpServers: { 'dsha-android': {command: process.execPath, args: [MCP]} }
    };
    if (request.resume) options.resume = request.resume;
    if (request.model) options.model = request.model;
    let result = false;
    try {
      this.active = this.query({prompt: request.prompt, options});
      for await (const event of this.active) {
        // stdout 不写环境、鉴权数据或 SDK 原始调试日志。
        if (event.type === 'system' && event.subtype === 'init')
          this.send({type: 'session', id: event.session_id, model: event.model, mcp: event.mcp_servers});
        if (event.type === 'stream_event' && !event.parent_tool_use_id) {
          const delta = event.event?.delta;
          if (delta?.type === 'text_delta') this.send({type: 'delta', text: delta.text});
        }
        if (event.type === 'assistant' && !event.parent_tool_use_id) {
          const content = event.message?.content || [];
          for (const block of content) if (block.type === 'tool_use')
            this.send({type: 'tool', name: block.name});
          const text = content.filter(b => b.type === 'text').map(b => b.text).join('');
          if (text) this.send({type: 'assistant', text});
        }
        if (event.type === 'result') {
          result = true;
          this.send({type: 'result', id: event.session_id, error: !!event.is_error,
            text: event.result || (event.errors || []).join('\n') || event.subtype});
        }
      }
      if (!result && !this.closed) throw Error('Claude Code 未返回完成结果，请检查登录和运行环境');
    } finally {
      this.active?.close(); this.active = null;
      for (const item of [...this.pending.values()]) item.settle({behavior: 'deny', message: '会话已结束'});
    }
  }
  cancel() {
    this.closed = true;
    for (const item of [...this.pending.values()]) item.settle({behavior: 'deny', message: '会话已取消'});
    this.abort?.abort(); this.active?.close();
  }
}

// 不等待换行才限制大小，避免截断或损坏的宿主请求无限积累。
function protocolInput(input, onMessage, onError, onClose) {
  let pending = '', closed = false;
  const close = () => {
    if (closed) return;
    closed = true; pending = '';
    input.off('data', data); input.off('end', end); input.off('error', fail); input.pause();
  };
  const fail = () => { close(); onError(); };
  const end = () => { close(); onClose(); };
  const data = chunk => {
    pending += chunk;
    while (!closed) {
      const newline = pending.indexOf('\n');
      if ((newline < 0 ? pending.length : newline) > 256 * 1024) { fail(); return; }
      if (newline < 0) return;
      const line = pending.slice(0, newline); pending = pending.slice(newline + 1);
      let message;
      try { message = JSON.parse(line); } catch { fail(); return; }
      if (!message || typeof message !== 'object' || Array.isArray(message)) { fail(); return; }
      onMessage(message);
    }
  };
  input.setEncoding('utf8'); input.on('data', data); input.once('end', end); input.once('error', fail);
  return close;
}

async function main() {
  const send = value => process.stdout.write(JSON.stringify(value) + '\n');
  let bridge, first = true, cancelled = false;
  const cancel = () => {cancelled = true; bridge?.cancel(); closeInput();};
  // 先监听输入再加载 SDK，防止应用紧接启动发送首条消息时丢失输入。
  const closeInput = protocolInput(process.stdin, message => {
    if (message.type === 'run' && first) {
      first = false;
      sdk.then(({query}) => {
        if (cancelled) return;
        bridge = new AgentBridge(query, send);
        return bridge.run(message);
      }).catch(error => {
        if (!cancelled && !bridge?.closed) send({type: 'error', text: String(error.message || error).replace(/sk-[\w-]+/g, '[REDACTED]')});
        process.exitCode = 1;
      }).finally(closeInput);
    } else if (message.type === 'permission') bridge?.answer(message);
    else if (message.type === 'cancel') cancel();
  }, () => {send({type: 'error', text: '请求数据无效或超过大小限制'}); process.exitCode = 1; cancel();}, cancel);
  const sdk = import(pathToFileURL(RUNTIME + '/node_modules/@anthropic-ai/claude-agent-sdk/sdk.mjs').href);
  process.once('SIGTERM', cancel);
  // 安装缺失也通过协议报告；不把包含凭据的请求打印到日志。
  sdk.catch(() => {});
}
if (require.main === module) main().catch(() => {process.exitCode = 1;});
module.exports = {AgentBridge, protocolInput};
