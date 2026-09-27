import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {spawnSync} from 'node:child_process';
const require = createRequire(import.meta.url);
const {loginUrl, openLogin} = require('../app/src/main/assets/claude/browser.cjs');
const auth = 'https://claude.ai/oauth/authorize?response_type=code&state=test-state&code_challenge=test-challenge&redirect_uri=http%3A%2F%2Flocalhost%3A1234%2Fcallback';
// 锁定版本的官方 CLI 实际使用 claude.com/cai/oauth/authorize（结构见 tools/check-claude-login.mjs）。
const cai = auth.replace('claude.ai/oauth/authorize', 'claude.com/cai/oauth/authorize');

test('官方授权链接保留查询参数与本机回调，不自行交换授权码', () => {
  for (const input of [auth, cai,
    auth.replace('claude.ai', 'console.anthropic.com'), auth.replace('claude.ai', 'platform.claude.com')]) {
    assert.equal(loginUrl(input), input);
  }
});
test('自动跳转拒绝非登录链接、伪装域名与不安全协议', () => {
  for (const input of [auth.replace('https:', 'http:'), auth.replace('claude.ai', 'claude.ai.evil.test'),
    auth.replace('claude.ai', 'user@claude.ai'), auth.replace('claude.ai', 'claude.ai:8443'),
    auth.replace('/oauth/authorize', '/redirect'), auth.replace('state=', 'other='),
    auth.replace('code_challenge=', 'other='), auth + '#fragment', auth + '\n', 'intent://login', 'x'.repeat(9000),
    // 新增的路径判据不能放宽成同主机下的任意路径
    cai.replace('/cai/oauth/authorize', '/cai/redirect'), cai.replace('claude.com', 'claude.com.evil.test'),
    cai.replace('claude.com/cai/oauth/authorize', 'claude.com/cai/oauth/authorize/extra')]) {
    assert.throws(() => loginUrl(input));
  }
});
test('本机桥接使用 token 请求头和原始授权链接，禁止重定向泄漏', async () => {
  let calls = 0;
  await openLogin(auth, {readToken: async () => ' local-token\n', request: async (url, options) => {
    calls++; assert.equal(url.origin, 'http://127.0.0.1:3090'); assert.equal(url.pathname, '/app/open');
    assert.equal(url.searchParams.get('url'), auth); assert.equal(url.searchParams.has('token'), false);
    assert.equal(options.headers['X-Token'], 'local-token'); assert.equal(options.redirect, 'error');
    assert.ok(options.signal instanceof AbortSignal); return new Response('OK: 已打开');
  }}); assert.equal(calls, 1);
});
test('拒绝或失败不能误报成功，也不能重复打开浏览器', async () => {
  for (const reply of ['[UNAUTHORIZED]', 'ERROR: browser unavailable']) {
    let calls = 0;
    await assert.rejects(openLogin(auth, {readToken: async () => 'token', request: async () => {
      calls++; return new Response(reply);
    }}), /BROWSER_OPEN_FAILED/); assert.equal(calls, 1);
  }
  await assert.rejects(openLogin(auth, {readToken: async () => ''}), /BRIDGE_NOT_READY/);
});
test('无效 URL 不读取凭据，命令失败也不在输出暴露 URL 或 state', async () => {
  await assert.rejects(openLogin('https://evil.test/?state=secret-state', {readToken: async () => assert.fail('不应读取 token')}));
  const result = spawnSync(process.execPath, ['app/src/main/assets/claude/browser.cjs', 'https://evil.test/?state=secret-state'], {encoding: 'utf8'});
  assert.equal(result.status, 1); assert.equal(result.stdout, ''); assert.doesNotMatch(result.stderr, /secret-state|evil\.test/);
});
