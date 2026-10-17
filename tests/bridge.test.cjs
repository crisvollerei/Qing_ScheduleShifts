
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
let JSDOM;
try { ({ JSDOM } = require('jsdom')); }
catch (_) { ({ JSDOM } = require('../.qa/node_modules/jsdom')); }

const root = path.resolve(__dirname,'..','app','src','main','assets');
const html = fs.readFileSync(path.join(root,'index.html'),'utf8');

/**
 * 启动一个带 Android 桥桩的页面实例，用于验证网页与原生侧的双向同步。
 * stub 为 null 表示普通浏览器环境（没有 Android 对象）。
 */
function boot(t,{storage={},android=null,now='2026-10-16T12:00:00'}={}) {
  const dom = new JSDOM(html,{url:'http://localhost/',runScripts:'outside-only',pretendToBeVisual:true});
  t.after(()=>dom.window.close());
  const w = dom.window, NativeDate = w.Date;
  let instant = new NativeDate(now).getTime();
  w.Date = class extends NativeDate { constructor(...args) { super(...(args.length ? args : [instant])); } static now(){return instant;} };
  w.confirm = () => true; w.prompt = () => null;
  w.matchMedia = () => ({matches:false,addEventListener(){}});
  for (const [k,v] of Object.entries(storage)) w.localStorage.setItem(k,v);
  const calls = { save: [], get: 0 };
  if (android) {
    w.Android = {
      saveSettings: (...a) => { calls.save.push(a); android.saved = a; },
      getSettings: () => { calls.get++; return android.payload; }
    };
  }
  const context = dom.getInternalVMContext();
  for (const f of ['schedule-core.js','calendar-data.js','app.js'])
    vm.runInContext(fs.readFileSync(path.join(root,'assets',f),'utf8'),context,{filename:f});
  return { w, d: dom.window.document, calls, read: e => vm.runInContext(e,context) };
}

test('saving pushes cycle, anchor and shift definitions to the native bridge', t => {
  const android = { saved: null, payload: '' };
  const { w, calls } = boot(t,{android});
  w.openSettings();
  w.saveSettings();
  assert.equal(calls.save.length, 1);
  const [cycle, anchor, defs] = calls.save[0];
  assert.ok(JSON.parse(cycle).length > 0);
  assert.match(anchor, /^\d{4}-\d{2}-\d{2}$/);
  assert.equal(typeof JSON.parse(defs), 'object');
});

test('a fresh web profile adopts the schedule already stored on the native side', t => {
  // 原生侧已有排班，但 WebView 的 localStorage 是空的（例如清除了站点数据）。
  const nativeConfig = { cycle:['早班','晚班','休息'], anchor:'2026-06-01',
    shiftDefs: { '早班':{emoji:'🌅',cssClass:'morning',startTime:'06:00',endTime:'14:00',isWork:true},
                 '晚班':{emoji:'🌆',cssClass:'evening',startTime:'14:00',endTime:'22:00',isWork:true},
                 '休息':{emoji:'🌿',cssClass:'rest',startTime:null,endTime:null,isWork:false} } };
  const android = { saved: null, payload: JSON.stringify({ cycle:JSON.stringify(nativeConfig.cycle), anchorDate:nativeConfig.anchor, shiftDefs:JSON.stringify(nativeConfig.shiftDefs) }) };
  const { w, read } = boot(t,{android});
  assert.equal(read('CYCLE.length'), 3);
  assert.deepEqual([...read('CYCLE')], nativeConfig.cycle);
  assert.equal(read('SHIFT_DEFS["早班"].startTime'), '06:00');
});

test('browser-only usage never touches the bridge and keeps working', t => {
  const { w, d } = boot(t,{});   // 没有 Android 对象
  assert.equal(d.querySelectorAll('#daysGrid button').length, 31);
  w.openSettings(); w.saveSettings();   // 不应抛错
  assert.ok(JSON.parse(w.localStorage.getItem('qingzheng-config')).cycle.length > 0);
});

test('local configuration wins over the native copy so edits are never reverted', t => {
  // 本地已有配置，原生侧是另一套：应以本地为准，避免页面被小部件数据覆盖。
  const local = { version:1, anchor:'2026-04-18', cycle:['白班','休息'],
    shiftDefs:{ '白班':{emoji:'☀️',cssClass:'day',startTime:'08:00',endTime:'20:00',isWork:true},
                '休息':{emoji:'🌿',cssClass:'rest',startTime:null,endTime:null,isWork:false} } };
  const android = { saved:null, payload: JSON.stringify({ cycle:JSON.stringify(['夜班','休息']), anchorDate:'2026-01-01', shiftDefs:'{}' }) };
  const { read } = boot(t,{storage:{'qingzheng-config':JSON.stringify(local)},android});
  assert.deepEqual([...read('CYCLE')], ['白班','休息']);
  assert.equal(read('ANCHOR_DATE.getMonth()'), 3);   // 4 月 = 索引 3
});

test('a corrupt native payload is ignored instead of breaking startup', t => {
  const android = { saved:null, payload:'{not json' };
  const { d, read } = boot(t,{android});
  assert.equal(d.querySelectorAll('#daysGrid button').length, 31);
  assert.ok(read('CYCLE.length') > 0);
});

test('an empty native payload does not override the default schedule', t => {
  const android = { saved:null, payload: JSON.stringify({ cycle:'', anchorDate:'', shiftDefs:'' }) };
  const { read } = boot(t,{android});
  assert.equal(read('CYCLE.length'), 8);   // 默认周期
});

test('a throwing bridge cannot interrupt saving on the web side', t => {
  const dom = new JSDOM(html,{url:'http://localhost/',runScripts:'outside-only',pretendToBeVisual:true});
  t.after(()=>dom.window.close());
  const w = dom.window;
  const NativeDate = w.Date;
  w.Date = class extends NativeDate { constructor(...a){ super(...(a.length?a:[new NativeDate('2026-10-16T12:00:00').getTime()])); } static now(){return new NativeDate('2026-10-16T12:00:00').getTime();} };
  w.confirm = () => true; w.prompt = () => null;
  w.matchMedia = () => ({matches:false,addEventListener(){}});
  w.Android = { saveSettings(){ throw new Error('bridge exploded'); }, getSettings(){ throw new Error('bridge exploded'); } };
  const context = dom.getInternalVMContext();
  for (const f of ['schedule-core.js','calendar-data.js','app.js'])
    vm.runInContext(fs.readFileSync(path.join(root,'assets',f),'utf8'),context,{filename:f});
  w.openSettings(); w.saveSettings();
  assert.ok(JSON.parse(w.localStorage.getItem('qingzheng-config')).cycle.length > 0);
});
