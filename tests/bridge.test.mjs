import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

import { createApp } from '../app/src/main/assets/assets/app.js';
import Core from '../app/src/main/assets/assets/schedule-core.js';
import { installAndroidBridge } from '../app/src/main/assets/assets/android-bridge.js';

const require = createRequire(import.meta.url);
let JSDOM;
try { ({ JSDOM } = require('jsdom')); }
catch (_) { ({ JSDOM } = require('../.qa/node_modules/jsdom')); }

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..','app','src','main','assets');
const html = fs.readFileSync(path.join(root,'index.html'),'utf8');

/**
 * 启动一个带 Android 桥桩的页面实例，用于验证网页与原生侧的双向同步。
 * android 为 null 表示普通浏览器环境（没有 Android 对象）。
 */
function boot(t,{storage={},android=null,now='2026-10-16T12:00:00'}={}) {
  const dom = new JSDOM(html,{url:'https://appassets.androidplatform.net/index.html',runScripts:'outside-only',pretendToBeVisual:true});
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
  const app = createApp(w);
  const bridge = installAndroidBridge(w, app, Core);
  return { app, bridge, w, d: dom.window.document, calls };
}

test('saving pushes cycle, anchor and shift definitions to the native bridge', t => {
  const android = { saved: null, payload: '' };
  const { app, d, calls } = boot(t,{android});
  app.settings.openSettings();
  d.getElementById('anchorDate').value = '2026-06-01';
  d.getElementById('cyclePattern').value = '白班 夜班 休息';
  app.settings.saveSettings();
  assert.equal(calls.save.length, 1);
  const [cycle, anchor, defs] = calls.save[0];
  assert.deepEqual(JSON.parse(cycle),['白班','夜班','休息']);
  assert.equal(anchor,'2026-06-01');
  assert.equal(typeof JSON.parse(defs),'object');
});

test('a fresh web profile adopts the schedule already stored on the native side', t => {
  const nativePayload = JSON.stringify({
    cycle: JSON.stringify(['白班','休息']),
    anchorDate: '2026-05-01',
    shiftDefs: JSON.stringify({'白班':{emoji:'☀️',cssClass:'day',startTime:'08:00',endTime:'20:00',isWork:true}})
  });
  const { app, bridge } = boot(t,{android:{saved:null,payload:nativePayload}});
  assert.deepEqual(app.schedule.cycle,['白班','休息']);
  assert.equal(app.schedule.captureCurrentConfig().anchor,'2026-05-01');
  assert.ok(bridge);
});

test('browser-only usage never touches the bridge and keeps working', t => {
  const { app, bridge, d } = boot(t);
  assert.equal(bridge,null);
  assert.equal(d.querySelectorAll('#daysGrid button').length,31);
  assert.equal(typeof app.schedule.commitConfig,'function');
});

test('local configuration wins over the native copy so edits are never reverted', t => {
  const local = {anchor:'2026-04-18',cycle:['夜班','休息','白班'],shiftDefs:{
    '夜班':{emoji:'🌙',cssClass:'night',startTime:'20:00',endTime:'08:00',crossDay:true,isWork:true},
    '休息':{emoji:'🌿',cssClass:'rest',startTime:null,endTime:null,isWork:false},
    '白班':{emoji:'☀️',cssClass:'day',startTime:'08:00',endTime:'20:00',isWork:true}
  }};
  const nativePayload = JSON.stringify({cycle:JSON.stringify(['白班']),anchorDate:'2026-01-01',shiftDefs:'{}'});
  const { app } = boot(t,{
    storage:{'qingzheng-config':JSON.stringify(local)},
    android:{saved:null,payload:nativePayload}
  });
  assert.deepEqual(app.schedule.cycle,['夜班','休息','白班']);
});

test('a corrupt native payload is ignored instead of breaking startup', t => {
  for (const payload of ['not json','{}','{"cycle":"[]","anchorDate":"2026-02-31"}']) {
    const { app, d } = boot(t,{android:{saved:null,payload}});
    assert.equal(d.querySelectorAll('#daysGrid button').length,31);
    assert.ok(app.schedule.cycle.length > 0);
  }
});

test('an empty native payload does not override the default schedule', t => {
  const { app } = boot(t,{android:{saved:null,payload:''}});
  const defaults = app.schedule.captureCurrentConfig();
  assert.equal(defaults.anchor,'2026-04-18');
  assert.equal(defaults.cycle.length,8);
});

test('a throwing bridge cannot interrupt saving on the web side', t => {
  const dom = new JSDOM(html,{url:'https://appassets.androidplatform.net/index.html',runScripts:'outside-only',pretendToBeVisual:true});
  t.after(()=>dom.window.close());
  const w = dom.window;
  w.matchMedia = () => ({matches:false,addEventListener(){}});
  w.Android = {
    saveSettings(){ throw new Error('bridge exploded'); },
    getSettings(){ throw new Error('bridge exploded'); }
  };
  const app = createApp(w);
  installAndroidBridge(w, app, Core);
  app.settings.openSettings();
  const d = w.document;
  d.getElementById('anchorDate').value = '2026-07-01';
  d.getElementById('cyclePattern').value = '白班 休息';
  assert.doesNotThrow(() => app.settings.saveSettings());
  assert.ok(JSON.parse(w.localStorage.getItem('qingzheng-config')).cycle.length === 2);
});

test('the native side can dismiss the top dialog for the back button', t => {
  const { app, d, bridge } = boot(t,{android:{saved:null,payload:''}});
  assert.equal(bridge.dismissTopDialog(),false);
  app.settings.openSettings();
  assert.equal(d.getElementById('settingsModal').hidden,false);
  assert.equal(bridge.dismissTopDialog(),true);
  assert.equal(d.getElementById('settingsModal').hidden,true);
  assert.equal(app.ui.activeDialog,null);
});

test('syncNow pushes the current configuration for onPageFinished reconciliation', t => {
  const android = { saved: null, payload: '' };
  const { bridge } = boot(t,{android});
  assert.equal(bridge.syncNow(),true);
  assert.equal(android.saved[1],'2026-04-18');
});
