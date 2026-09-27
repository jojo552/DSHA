import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {PassThrough} from 'node:stream';
const {AgentBridge, protocolInput} = createRequire(import.meta.url)('../app/src/main/assets/claude/runner.cjs');
const request = {prompt: '读取项目', cwd: '/root/project', resume: '12345678-1234-1234-1234-123456789abc', apiKey: 'test-private-key'};
const events = [];
let received, closed = 0;
function query(value) {
  received = value;
  const iterator = (async function* () {
    yield {type:'system', subtype:'init', session_id:request.resume, model:'test'};
    yield {type:'stream_event', event:{delta:{type:'text_delta', text:'你好'}}};
    yield {type:'stream_event', parent_tool_use_id:'child', event:{delta:{type:'text_delta', text:'不能混入主对话'}}};
    const original = {command:'echo "中文"'};
    const permission = await value.options.canUseTool('Bash', original, {signal: value.options.abortController.signal});
    assert.deepEqual(permission, {behavior:'allow', updatedInput:original});
    yield {type:'assistant', message:{content:[{type:'tool_use', name:'Bash'}, {type:'text', text:'你好'}]}};
    yield {type:'result', session_id:request.resume, result:'你好', is_error:false};
  })();
  iterator.close = () => closed++;
  return iterator;
}
const bridge = new AgentBridge(query, event => {
  events.push(event);
  if (event.type === 'permission') {
    bridge.answer({id:'stale', allow:true});
    queueMicrotask(() => bridge.answer({id:event.id, allow:true}));
  }
});
await bridge.run(request);
assert.equal(received.options.resume, request.resume);
assert.equal(received.options.permissionMode, 'default');
assert.equal(received.options.env.ANTHROPIC_API_KEY, request.apiKey);
assert.deepEqual(received.options.mcpServers['dsha-android'].args, ['/root/dsha-computer-use-android/lib/server.cjs']);
assert.equal(JSON.stringify(events).includes(request.apiKey), false);
assert.equal(JSON.stringify(events).includes('不能混入主对话'), false);
assert.equal(events.at(-1).type, 'result');
assert.equal(closed, 1);
await assert.rejects(bridge.run(request), /只能接收一轮/);

const answers = [];
const permissionBridge = new AgentBridge(null, event => answers.push(event));
const input = {questions:[{question:'选哪个？', options:[{label:'A'}, {label:'B'}]}]};
const question = permissionBridge.permission('AskUserQuestion', input);
permissionBridge.answer({id:answers[0].id, allow:true, answers:{'选哪个？':'B'}});
assert.deepEqual(await question, {behavior:'allow', updatedInput:{...input, answers:{'选哪个？':'B'}}});
const denied = permissionBridge.permission('Write', {file_path:'a'});
permissionBridge.answer({id:answers[1].id, allow:false});
assert.equal((await denied).behavior, 'deny');
const cancelled = permissionBridge.permission('Bash', {command:'sleep 1'});
permissionBridge.cancel();
assert.equal((await cancelled).behavior, 'deny');
assert.equal(permissionBridge.pending.size, 0);
// 并行工具授权必须保留各自的输入和答复，不能被最后一个请求覆盖。
const parallelEvents = [];
const parallel = new AgentBridge(null, event => parallelEvents.push(event));
const firstTool = parallel.permission('Read', {file_path:'a'});
const secondTool = parallel.permission('Write', {file_path:'b'});
parallel.answer({id:parallelEvents[1].id, allow:false});
parallel.answer({id:parallelEvents[0].id, allow:true});
assert.equal((await firstTool).behavior, 'allow');
assert.equal((await secondTool).behavior, 'deny');
const controller = new AbortController(); controller.abort();
assert.equal((await new AgentBridge(null,()=>assert.fail()).permission('Bash',{}, {signal:controller.signal})).behavior, 'deny');

const missingResult = new AgentBridge(() => {
  const iterator = (async function* () { yield {type:'assistant', message:{content:[]}}; })();
  iterator.close = () => {}; return iterator;
}, () => {});
await assert.rejects(missingResult.run({...request, resume:''}), /未返回完成结果/);
for (const invalid of [{...request, prompt:''}, {...request, cwd:'relative'}, {...request, resume:'; rm'}])
  await assert.rejects(new AgentBridge(()=>assert.fail(),()=>{}).run(invalid));

const stdin = new PassThrough(); const messages = [];
const close = protocolInput(stdin, value => messages.push(value), () => assert.fail('有效输入不应失败'), () => {});
const bytes = Buffer.from('{"type":"run","prompt":"中文😊"}\n{"type":"cancel"}\n');
for (const byte of bytes) stdin.write(Buffer.from([byte]));
assert.deepEqual(messages, [{type:'run', prompt:'中文😊'}, {type:'cancel'}]); close();
for (const invalid of ['null\n', '[]\n', '{bad\n', 'x'.repeat(256 * 1024 + 1)]) {
  const source = new PassThrough(); let failed = 0;
  protocolInput(source, () => assert.fail('无效输入不应转发'), () => failed++, () => {});
  source.write(invalid); assert.equal(failed, 1); assert.equal(source.listenerCount('data'), 0);
}
console.log('Claude SDK 适配：流式输出、续聊、并行授权、提问、取消、凭据隔离和协议边界测试通过。');
