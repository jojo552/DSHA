// 在独立空目录验证锁定 CLI 的 BROWSER 契约；不登录账号，不访问用户凭据。
import assert from 'node:assert/strict';
import {mkdtemp, writeFile, readFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {spawn} from 'node:child_process';
import {setTimeout as delay} from 'node:timers/promises';
import {createRequire} from 'node:module';
const {loginUrl} = createRequire(import.meta.url)('../app/src/main/assets/claude/browser.cjs');
const root = await mkdtemp(join(tmpdir(), 'dsha-login-check-'));
const captured = join(root, 'url'); const browser = join(root, 'browser');
const cli = resolve('app/src/main/assets/claude/node_modules/.bin/claude');
let child;
try {
  await writeFile(browser, '#!/usr/bin/env node\nrequire("node:fs").writeFileSync(process.env.DSHA_LOGIN_CAPTURE, process.argv[2], {mode:0o600});\n', {mode: 0o700});
  child = spawn(cli, ['auth', 'login'], {
    cwd: root, detached: true, stdio: ['pipe', 'ignore', 'ignore'],
    env: {PATH: process.env.PATH, HOME: root, CLAUDE_CONFIG_DIR: join(root, 'config'),
      BROWSER: browser, DSHA_LOGIN_CAPTURE: captured, TERM: 'dumb', LANG: 'C.UTF-8'},
  });
  let failure; child.on('error', error => { failure = error; });
  let url = ''; const deadline = Date.now() + 45000;
  while (!url && Date.now() < deadline) {
    if (failure) throw failure;
    try { url = await readFile(captured, 'utf8'); } catch (error) { if (error.code !== 'ENOENT') throw error; }
    if (!url && child.exitCode !== null) throw Error('官方 CLI 未调用 BROWSER 即退出');
    if (!url) await delay(100);
  }
  assert.ok(url, '官方 CLI 在 45 秒内未调用 BROWSER'); loginUrl(url);
  console.log('官方 CLI 登录已调用 BROWSER，授权地址通过校验；未提交账号授权。');
} finally {
  if (child?.pid) {
    try { process.kill(-child.pid, 'SIGTERM'); } catch (error) { if (error.code !== 'ESRCH') throw error; }
    await delay(500);
    try { process.kill(-child.pid, 'SIGKILL'); } catch (error) { if (error.code !== 'ESRCH') throw error; }
  }
  await rm(root, {recursive: true, force: true});
}
