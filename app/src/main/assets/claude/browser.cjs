#!/usr/local/bin/node
'use strict';
// 仅供用户主动发起的 Claude 登录使用；官方 CLI 继续负责 state、PKCE 和回调。
const fs = require('node:fs/promises');

// 官方入口会随版本变化：claude.ai/oauth/authorize 是旧结构，锁定版本实测走
// claude.com/cai/oauth/authorize（结构由 tools/check-claude-login.mjs 打印）。
// 只放宽「官方主机 + 官方授权路径」两项，其余判据一律不放宽。
const LOGIN_HOSTS = ['claude.ai', 'claude.com', 'console.anthropic.com', 'platform.claude.com'];
const LOGIN_PATHS = new Set(['/oauth/authorize', '/cai/oauth/authorize']);

function loginUrl(value) {
  if (typeof value !== 'string' || value.length > 8192 || /[\s\x00-\x1f\x7f]/.test(value)) throw Error('INVALID_LOGIN_URL');
  const url = new URL(value);
  if (url.protocol !== 'https:' || url.username || url.password || url.port || url.hash
      || !LOGIN_HOSTS.includes(url.hostname)
      || !LOGIN_PATHS.has(url.pathname)
      || url.searchParams.get('response_type') !== 'code'
      || !url.searchParams.get('state') || !url.searchParams.get('code_challenge')) throw Error('INVALID_LOGIN_URL');
  return url.href;
}

async function openLogin(value, {readToken = () => fs.readFile('/root/.dsh/.bridge_token', 'utf8'), request = fetch} = {}) {
  const url = loginUrl(value);
  const token = (await readToken()).trim();
  if (!token) throw Error('BRIDGE_NOT_READY');
  const endpoint = new URL('http://127.0.0.1:3090/app/open'); endpoint.searchParams.set('url', url);
  const response = await request(endpoint, {
    headers: {'X-Token': token}, redirect: 'error', signal: AbortSignal.timeout(5000),
  });
  if (!response.ok || !/^OK[: ]/.test(await response.text())) throw Error('BROWSER_OPEN_FAILED');
}

module.exports = {loginUrl, openLogin};
if (require.main === module) openLogin(process.argv[2]).catch(() => {
  // 不打印 URL、授权 state、桥 token 或网络异常中的查询串。
  process.stderr.write('无法自动打开授权页，请使用 Claude 终端显示的登录链接。\n');
  process.exitCode = 1;
});
