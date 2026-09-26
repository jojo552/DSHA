#!/usr/bin/env node
/**
 * DSHA 真机验收（在手机里的 Ubuntu 容器内运行，不是 CI）。
 * 对应 docs/claude-code.md 的《真机验收（Android 10 及以下）》六条。
 *
 * 前置：
 *   1. 已安装待验收的包（正式覆盖包，或并存安装的 com.dsh.client.verify）
 *   2. DSHA「设置 → 设备能力授权」已开启屏幕操作（无障碍服务）
 *   3. 手机旁要有人：每次手机操作都会弹原生确认，脚本会等
 *
 * 用法：
 *   node tools/accept-android-device.mjs             # 静态项 + 滚动 + 文字点击
 *   node tools/accept-android-device.mjs --static    # 只跑不需要确认的项
 *   node tools/accept-android-device.mjs --gesture   # 额外验证坐标手势（API 24+）
 *
 * 环境变量：
 *   BRIDGE_TOKEN  桥 token，默认读 /root/.dsh/.bridge_token
 *   BRIDGE        桥地址，默认 http://127.0.0.1:3090
 *   DSHA_PACKAGE  要拉起的包名，默认不拉起（用当前屏幕）
 *
 * 判读：脚本只做客观断言（字段、前缀、参数校验），不代替人工看界面。
 */
import { readFileSync, writeFileSync } from 'node:fs';

const BASE = process.env.BRIDGE || 'http://127.0.0.1:3090';
const TOKEN = process.env.BRIDGE_TOKEN || readFileSync('/root/.dsh/.bridge_token', 'utf8').trim();
const APP = process.env.DSHA_PACKAGE || '';
const argv = process.argv.slice(2);
const STATIC_ONLY = argv.includes('--static');
const WITH_GESTURE = argv.includes('--gesture');

const rows = [];
function report(id, name, verdict, detail) {
    rows.push({ id, name, verdict, detail });
    const icon = { PASS: '✅', FAIL: '❌', WARN: '⚠️', SKIP: '⏭️' }[verdict] || '❔';
    console.log(icon + ' [' + id + '] ' + name + '\n        ' + detail);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function bridge(path, params = {}, ms = 60000) {
    const url = new URL(BASE + path);
    url.searchParams.set('token', TOKEN);
    for (const [k, v] of Object.entries(params)) url.searchParams.set(k, String(v));
    const res = await fetch(url, { signal: AbortSignal.timeout(ms) });
    const text = await res.text();
    try {
        const parsed = JSON.parse(text);
        return typeof parsed?.result === 'string' ? parsed.result : text;
    } catch { return text; }
}

function parseDump(dump) {
    return dump.split('\n').map((line) => {
        const m = /^\[(\d+)\]\s+(?:"([^"]*)")?(.*)$/.exec(line.trim());
        if (!m) return null;
        const rest = m[3] || '';
        const center = /中心=\((\d+),(\d+)\)/.exec(rest);
        return {
            index: Number(m[1]), label: m[2] || '',
            clickable: rest.includes('可点击'), editable: rest.includes('可输入'),
            enabled: !rest.includes('不可用'),
            x: center ? Number(center[1]) : null, y: center ? Number(center[2]) : null,
        };
    }).filter(Boolean);
}

const SAFE_TARGETS = ['设置', '终端', '插件', '日志', '启动', '关于', '仪表盘', '用户', '主页', '首页'];

// ---------- 1. 版本与构建识别 ----------
const version = await bridge('/app/version');
const appVersion = (/APP_VERSION=(.*)/.exec(version) || [])[1] || '未知';
report('1', '/app/version', version.includes('APP_VERSION') ? 'PASS' : 'FAIL', version.trim().replace(/\n/g, ' | '));

// ---------- 2. 能力查询（新增端点） ----------
let caps = null;
const capsRaw = await bridge('/app/ui/capabilities');
if (capsRaw.includes('未知端点')) {
    report('2', '能力查询 /app/ui/capabilities', 'FAIL',
        '装的还是旧包：' + capsRaw.trim() + ' —— 先装上待验收的包再跑');
} else {
    try { caps = JSON.parse(capsRaw); } catch { caps = null; }
    if (!caps) {
        report('2', '能力查询 /app/ui/capabilities', 'FAIL', '返回的不是 JSON：' + capsRaw.slice(0, 200));
    } else {
        const dev = await bridge('/app/device');
        const sdk = Number((/SDK (\d+)/.exec(dev) || [])[1] || 0);
        const av = caps.available || {};
        const problems = [];
        if (caps.requiresDshaApproval !== true) problems.push('requiresDshaApproval 不是 true');
        if (sdk && caps.androidApi !== sdk) problems.push('androidApi=' + caps.androidApi + ' 与实际 SDK ' + sdk + ' 不一致');
        if (caps.accessibilityConnected === true) {
            for (const t of ['android_get_state', 'android_click_text', 'android_type', 'android_key', 'android_scroll']) {
                if (av[t] !== true) problems.push(t + ' 应为 true');
            }
            if (caps.androidApi >= 24) {
                for (const t of ['android_click', 'android_swipe']) if (av[t] !== true) problems.push(t + ' 应为 true（API 24+）');
            }
        } else {
            for (const [t, v] of Object.entries(av)) if (v === true) problems.push(t + ' 在无障碍未连接时报成可用');
        }
        if (caps.androidApi < 30 && av.android_screenshot !== false) problems.push('API < 30 时 android_screenshot 应为 false');
        if (!caps.guidance) problems.push('缺 guidance');
        report('2', '能力查询 /app/ui/capabilities', problems.length ? 'FAIL' : 'PASS',
            'API=' + caps.androidApi + ' 无障碍=' + caps.accessibilityConnected + ' 截图=' + av.android_screenshot
            + (problems.length ? '｜问题：' + problems.join('；') : '｜字段与 API ' + caps.androidApi + ' 的预期一致')
            + '\n        guidance: ' + String(caps.guidance).slice(0, 120));
    }
}

// ---------- 3. 参数校验（不弹确认） ----------
const badDir = await bridge('/app/ui/scroll', { direction: 'left' });
const noDir = await bridge('/app/ui/scroll');
const okBad = badDir.trim() === '[ERR] 无效的滚动方向' && noDir.trim() === '[ERR] 无效的滚动方向';
report('3', '滚动参数校验（非法方向直接拒绝）', okBad ? 'PASS' : 'FAIL',
    'direction=left → ' + badDir.trim() + '｜缺 direction → ' + noDir.trim());

// ---------- 4. 无障碍连接状态（后续项的前置） ----------
let dump = await bridge('/app/ui/dump');
let accOn = !dump.includes('无障碍服务未开启');
report('4', '无障碍服务连接', accOn ? 'PASS' : 'FAIL',
    accOn ? '读屏可用（' + (dump.split('\n')[0] || '').trim() + '）'
           : dump.trim() + ' —— 需要先在 DSHA「设置 → 设备能力授权」点「设置屏幕操作」');

if (!accOn) {
    for (const [id, name] of [['5', '控件滚动'], ['6', '文字点击'], ['7', '坐标手势']]) {
        report(id, name, 'SKIP', '无障碍未开启，无法执行');
    }
} else if (STATIC_ONLY) {
    for (const [id, name] of [['5', '控件滚动'], ['6', '文字点击'], ['7', '坐标手势']]) {
        report(id, name, 'SKIP', '--static 模式跳过（会弹原生确认）');
    }
} else {
    if (APP) {
        await bridge('/app/launch', { pkg: APP });
        await sleep(4000);
    }

    // ---------- 5. 控件滚动（会弹确认） ----------
    let r = await bridge('/app/ui/scroll', { direction: 'forward' });
    const scrollOk = r.startsWith('OK 已请求滚动') || r.includes('没有可滚动控件');
    report('5', '控件滚动 /app/ui/scroll?direction=forward', scrollOk ? 'PASS' : 'FAIL',
        r.trim() + (r.includes('没有可滚动控件') ? '（当前页无滚动控件，属可接受结果）' : '')
        + (r.includes('你拒绝了') ? '｜用户未确认，需重跑' : ''));

    // ---------- 6. 文字点击（会弹确认） ----------
    const before = await bridge('/app/ui/dump');
    const nodes = parseDump(before);
    const target = nodes.find((n) => n.clickable && n.enabled && SAFE_TARGETS.includes(n.label))
        || nodes.find((n) => n.clickable && n.enabled && n.label && n.label.length <= 8);
    if (!target) {
        report('6', '文字点击 /app/ui/tap?text=', 'WARN', '当前屏幕上找不到合适的可点击文字，先切到有按钮的界面再重跑');
    } else {
        r = await bridge('/app/ui/tap', { text: target.label });
        const after = await bridge('/app/ui/dump');
        report('6', '文字点击 /app/ui/tap?text=' + target.label, r.startsWith('OK 已点击') ? 'PASS' : 'FAIL',
            r.trim() + '｜点击后界面' + (after !== before ? '已变化（读到新页面）' : '无变化（该控件可能是当前页或不可切换）'));
    }

    // ---------- 7. 坐标手势（API 24+，会弹确认） ----------
    if (!WITH_GESTURE) {
        report('7', '坐标手势（API 24+）', 'SKIP', '未加 --gesture；矩阵要求 API 24+ 可用，可用 --gesture 验证');
    } else {
        const t = parseDump(await bridge('/app/ui/dump')).find((n) => n.clickable && n.enabled && n.x !== null);
        if (!t) report('7', '坐标手势（API 24+）', 'WARN', '取不到可点坐标，先切到有可点控件的界面');
        else {
            r = await bridge('/app/ui/tap', { x: t.x, y: t.y });
            report('7', '坐标手势 /app/ui/tap?x=&y=', r.startsWith('OK 已点击') ? 'PASS' : 'FAIL',
                '(' + t.x + ',' + t.y + ') → ' + r.trim());
        }
    }
}

// ---------- 8. MCP 内置插件随包同步 ----------
try {
    const plugin = readFileSync('/root/dsha-computer-use-android/lib/server.cjs', 'utf8');
    const toolNames = [...plugin.matchAll(/\['(android_[a-z_]+)'/g)].map((m) => m[1]);
    const missing = ['android_capabilities', 'android_click_text', 'android_scroll'].filter((t) => !toolNames.includes(t));
    report('8', '内置插件工具随包同步（应为 9 个）', missing.length ? 'FAIL' : 'PASS',
        toolNames.length + ' 个：' + toolNames.join(', ') + (missing.length ? '｜缺：' + missing.join(', ') : ''));
} catch (e) {
    report('8', '内置插件工具随包同步', 'WARN', '读不到已装插件：' + e.message);
}

// ---------- 汇总 ----------
const count = (v) => rows.filter((x) => x.verdict === v).length;
console.log('\n===== 汇总 =====');
console.log('包 ' + appVersion + '｜通过 ' + count('PASS') + ' ｜失败 ' + count('FAIL')
    + ' ｜警告 ' + count('WARN') + ' ｜跳过 ' + count('SKIP'));
writeFileSync('/root/dsha-accept-report.json', JSON.stringify({ at: new Date().toISOString(), appVersion, rows }, null, 2));
console.log('报告：/root/dsha-accept-report.json');
process.exit(count('FAIL') ? 1 : 0);
