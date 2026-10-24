import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createRequire} from 'node:module';
import {createApp} from '../app/src/main/assets/assets/app.js';
import {installReminders} from '../app/src/main/assets/assets/reminders.js';
const require = createRequire(import.meta.url);
let JSDOM;
try { ({JSDOM} = require('jsdom')); } catch (_) { ({JSDOM} = require('../.qa/node_modules/jsdom')); }
const html = fs.readFileSync(new URL('../app/src/main/assets/index.html',import.meta.url),'utf8');
const entry = {name:'夜班',startText:'10月25日 20:00',fireText:'10月25日 19:00',start:1792929600000,fire:1792926000000};
const tick = () => new Promise(resolve => setImmediate(resolve));
function boot(t,{native = true, overrides = {}} = {}) {
  const dom = new JSDOM(html,{url:'https://appassets.androidplatform.net/index.html',pretendToBeVisual:true});
  t.after(() => dom.window.close());
  const w = dom.window; w.matchMedia = () => ({matches:false,addEventListener(){}}); w.scrollTo = () => {};
  const calls = [], state = {enabled:false,lead:60,sound:true,vibrate:true,exact:true,notifications:true,fullScreen:true,status:'未启用',next:entry,scheduled:null,testFire:0,calendarAllowed:true,calendarCount:0};
  if (native) w.Android = {reminderRequest(id,action,raw) {
    const payload = JSON.parse(raw); calls.push({id,action,payload});
    let data = {...state};
    if (action === 'save') {Object.assign(state,payload); state.status = state.enabled ? '已安排' : '未启用'; data = {...state};}
    if (action === 'calendars') data = {calendars:[{id:3,name:'我的日历',account:'本地'}]};
    if (action === 'calendarPreview') data = {entries:[entry],skipped:0,token:'plan-one',lead:state.lead};
    if (action === 'calendarExport') data = {created:1,updated:0,removed:0};
    if (action === 'clockPreview') data = {entry,token:'clock-one'};
    if (action === 'test') state.testFire = 123;
    if (action === 'cancelTest') state.testFire = 0;
    queueMicrotask(() => {
      const result = overrides[action] ? overrides[action](payload) : {ok:true,data};
      if (result) w.QingZhengReminders.receive(id,result);
    });
  }};
  const app = createApp(w), reminders = installReminders(w,app), d = w.document;
  return {w,d,app,reminders,calls,state,click:id => d.getElementById(id).click()};
}

test('ordinary browsers do not show an Android-only reminder entry or install a bridge',t => {
  const {d,reminders,w} = boot(t,{native:false});
  assert.equal(reminders,null); assert.equal(d.querySelector('.reminder-launch'),null); assert.equal(w.QingZhengReminders,undefined);
});
test('reminders open as a themed static input page and native back dismisses it',async t => {
  const {d,reminders,app} = boot(t); reminders.open(); await tick();
  assert.equal(app.ui.activeDialog,'reminderModal'); assert.equal(app.liveTimer,null);
  assert.ok(d.getElementById('reminderModal').classList.contains('input-view'));
  assert.equal(d.getElementById('reminderLead').value,'60');
  app.ui.closeDialog('reminderModal'); assert.notEqual(app.liveTimer,null);
});
test('saving uses a persistent toggle without any day count',async t => {
  const {d,reminders,calls,w} = boot(t); reminders.open(); await tick();
  d.getElementById('reminderEnabled').checked = true; d.getElementById('reminderLead').value = '30';
  d.getElementById('reminderForm').dispatchEvent(new w.Event('submit',{bubbles:true,cancelable:true})); await tick();
  const save = calls.find(call => call.action === 'save');
  assert.equal(save.payload.enabled,true); assert.equal(save.payload.lead,30); assert.equal('days' in save.payload,false);
  assert.equal(d.getElementById('reminderStatus').textContent,'已安排');
});
test('missing permission is not presented as successfully scheduled',async t => {
  const {reminders,d,state} = boot(t); Object.assign(state,{enabled:true,exact:false,status:'待授权'});
  reminders.open(); await tick();
  assert.equal(d.getElementById('reminderStatus').textContent,'待授权');
  assert.match(d.getElementById('reminderPermissions').textContent,/允许精确闹钟/);
});
test('calendar export requires preview and explicit confirmation and range edits invalidate it',async t => {
  const {reminders,click,calls,d,w,app} = boot(t); reminders.open(); await tick();
  click('reminderPreview'); await tick();
  assert.equal(d.getElementById('reminderExport').hidden,false);
  click('reminderGenerate'); assert.equal(app.ui.activeDialog,'confirmModal');
  assert.equal(calls.some(call => call.action === 'calendarExport'),false);
  click('confirmAccept'); await tick();
  assert.deepEqual(calls.find(call => call.action === 'calendarExport').payload,{calendar:3,days:7,token:'plan-one'});
  click('reminderPreview'); await tick();
  d.getElementById('reminderRange').value = 'custom'; d.getElementById('reminderRange').dispatchEvent(new w.Event('change'));
  assert.equal(d.getElementById('reminderExport').hidden,true);
  assert.equal(d.getElementById('reminderDaysGroup').hidden,false);
});
test('unsaved advance changes cannot silently affect exports',async t => {
  const {reminders,d,w,click,calls} = boot(t); reminders.open(); await tick();
  d.getElementById('reminderLead').value = '15'; d.getElementById('reminderLead').dispatchEvent(new w.Event('input'));
  click('reminderPreview'); await tick();
  assert.match(d.getElementById('reminderError').textContent,/先保存/);
  assert.equal(calls.some(call => call.action === 'calendarPreview'),false);
});
test('system clock warns about dates and does not claim an alarm was created',async t => {
  const {reminders,click,d,calls} = boot(t); reminders.open(); await tick();
  click('reminderClock'); await tick();
  assert.match(d.getElementById('confirmDescription').textContent,/核对日期/);
  assert.equal(calls.some(call => call.action === 'clock'),false);
  click('confirmAccept'); await tick();
  assert.equal(calls.find(call => call.action === 'clock').payload.token,'clock-one');
  assert.match(d.getElementById('reminderFeedback').textContent,/尚不能认定/);
  assert.equal(d.getElementById('reminderFeedback').hidden,false);
});
test('native errors leave the page usable without success toasts',async t => {
  const {reminders,click,d} = boot(t,{overrides:{test:() => ({ok:false,error:'请先授权'})}});
  reminders.open(); await tick(); click('reminderTest'); await tick();
  assert.equal(d.getElementById('reminderError').textContent,'请先授权');
  assert.equal(d.getElementById('reminderTest').disabled,false);
});
test('late replies after closing cannot reopen or alter a newer page',async t => {
  let paused;
  const {reminders,click,d,calls} = boot(t,{overrides:{calendarPreview:() => null}});
  reminders.open(); await tick(); click('reminderPreview'); await tick();
  paused = calls.find(call => call.action === 'calendarPreview').id;
  click('reminderClose');
  reminders.receive(paused,{ok:true,data:{entries:[entry],lead:60,token:'old',skipped:0}}); await tick();
  assert.equal(d.getElementById('reminderModal').hidden,true);
  assert.equal(d.getElementById('reminderExport').hidden,true);
});
test('excessive custom ranges are rejected before reaching Android',async t => {
  const {reminders,click,d,calls} = boot(t); reminders.open(); await tick();
  d.getElementById('reminderRange').value = 'custom'; d.getElementById('reminderDays').value = '91';
  click('reminderPreview'); await tick();
  assert.match(d.getElementById('reminderError').textContent,/1–90/);
  assert.equal(calls.some(call => call.action === 'calendarPreview'),false);
});
