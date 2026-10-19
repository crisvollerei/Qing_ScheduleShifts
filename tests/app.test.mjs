import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { createApp } from '../app/src/main/assets/assets/app.js';
import Core from '../app/src/main/assets/assets/schedule-core.js';
import { DEFAULT_ANCHOR, DEFAULT_CYCLE, DEFAULT_SHIFT_DEFS } from '../app/src/main/assets/assets/config.js';
import { MAX_BACKUP_TEXT_LENGTH, createBackup, parseBackup, createBackupText, parseBackupText } from '../app/src/main/assets/assets/backup-format.js';
const require = createRequire(import.meta.url);
let JSDOM;
try { ({ JSDOM } = require('jsdom')); }
catch (_) { ({ JSDOM } = require('../.qa/node_modules/jsdom')); }
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..','app','src','main','assets');
const html = fs.readFileSync(path.join(root,'index.html'),'utf8');
const initialImageCount = (html.match(/<img\b/g) || []).length;
function boot(t,{storage={},blocked=false,now='2026-10-16T12:00:00',reducedMotion=null}={}) {
  const dom = new JSDOM(html,{url:'http://localhost/',runScripts:'outside-only',pretendToBeVisual:true});
  t.after(()=>dom.window.close());
  const w = dom.window, NativeDate = w.Date;
  let instant = new NativeDate(now).getTime();
  w.Date = class extends NativeDate { constructor(...args) { super(...(args.length ? args : [instant])); } static now() { return instant; } };
  w.confirm = w.prompt = () => { throw new Error('Native dialogs must not be used'); };
  if (reducedMotion !== null) {
    w.matchMedia = () => ({matches:reducedMotion,addEventListener(){}});
    if (reducedMotion) w.Element.prototype.animate = () => {throw new Error('Reduced-motion users must not receive scripted animations');};
  }
  for (const [key,value] of Object.entries(storage)) w.localStorage.setItem(key,value);
  if (blocked) Object.defineProperty(w,'localStorage',{get(){throw new Error('Storage is disabled');}});
  const app = createApp(w);
  return {app,w,d:w.document,setNow:value=>{instant=new NativeDate(value).getTime();}};
}
function keyboardViewport(w,{visual=true,height=844}={}) {
  const viewport = new w.EventTarget(), pending = new Map();
  let nextTimer = 1;
  viewport.height = height; viewport.offsetTop = 0;
  Object.defineProperty(w,'visualViewport',{value:visual ? viewport : undefined,configurable:true});
  Object.defineProperty(w,'innerHeight',{value:height,writable:true,configurable:true});
  w.setTimeout = callback => { const id = nextTimer++; pending.set(id,callback); return id; };
  w.clearTimeout = id => pending.delete(id);
  return {viewport,pending,flush() { const callbacks = [...pending.values()]; pending.clear(); callbacks.forEach(callback=>callback()); }};
}
function pointer(w,target,type,{x=200,y=200,id=1,pointerType='touch',primary=true}={}) {
  const event = new w.Event(type,{bubbles:true,cancelable:true});
  for (const [key,value] of Object.entries({clientX:x,clientY:y,pointerId:id,pointerType,isPrimary:primary})) {
    Object.defineProperty(event,key,{value});
  }
  target.dispatchEvent(event);
  return event;
}
function swipe(w,d,{from=250,to=100,y=200,pointerType='touch'}={}) {
  pointer(w,d.querySelector('.calendar'),'pointerdown',{x:from,y,pointerType});
  pointer(w,w,'pointermove',{x:to,y:y+5,pointerType});
  pointer(w,w,'pointerup',{x:to,y:y+5,pointerType});
}
test('horizontal touch and pen swipes switch months and update the monthly summary',t => {
  const {w,d,app} = boot(t);
  swipe(w,d);
  assert.equal(app.views.viewMonth,10);
  assert.equal(d.querySelector('#statsMonth').textContent,'2026.11');
  assert.equal(d.querySelectorAll('#daysGrid button').length,30);
  swipe(w,d,{from:80,to:250,pointerType:'pen'});
  assert.equal(app.views.viewMonth,9);
  assert.equal(d.querySelector('#statsMonth').textContent,'2026.10');
});
test('vertical scrolling, diagonal scrolling, short drags and mouse drags never switch months',t => {
  const {w,d,app} = boot(t), calendar = d.querySelector('.calendar');
  for (const [x,y] of [[205,350],[300,310],[220,205]]) {
    pointer(w,calendar,'pointerdown');
    const movement = pointer(w,w,'pointermove',{x,y});
    pointer(w,w,'pointerup',{x,y});
    assert.equal(movement.defaultPrevented,false,'Native scrolling must not be cancelled');
    assert.equal(app.views.viewMonth,9);
  }
  swipe(w,d,{pointerType:'mouse'});
  assert.equal(app.views.viewMonth,9);
});
test('a completed swipe suppresses its date click but leaves keyboard and the next tap usable',t => {
  const {w,d,app} = boot(t);
  swipe(w,d);
  let cell = d.querySelector('#daysGrid button');
  cell.dispatchEvent(new w.MouseEvent('click',{bubbles:true,cancelable:true,detail:1}));
  assert.equal(d.querySelector('#detailPanel').classList.contains('show'),false);
  cell.click();
  assert.equal(d.querySelector('#detailPanel').classList.contains('show'),true,'Keyboard activation has detail 0');
  app.views.closeDetail();
  pointer(w,cell,'pointerdown');
  pointer(w,w,'pointerup');
  cell.dispatchEvent(new w.MouseEvent('click',{bubbles:true,cancelable:true,detail:1}));
  assert.equal(d.querySelector('#detailPanel').classList.contains('show'),true);
});
test('pinch gestures, cancelled pointers and backgrounding discard pending swipes',t => {
  const {w,d,app} = boot(t), calendar = d.querySelector('.calendar');
  for (const secondTarget of [calendar,d.body]) {
    pointer(w,calendar,'pointerdown');
    pointer(w,secondTarget,'pointerdown',{id:2,primary:false});
    pointer(w,w,'pointermove',{x:40});
    pointer(w,w,'pointerup',{x:40});
    pointer(w,w,'pointerup',{id:2});
    assert.equal(app.views.viewMonth,9);
  }
  for (const cancel of ['pointercancel','blur','visibilitychange']) {
    pointer(w,calendar,'pointerdown');
    if (cancel === 'pointercancel') pointer(w,w,cancel);
    else (cancel === 'blur' ? w : d).dispatchEvent(new w.Event(cancel));
    pointer(w,w,'pointerup',{x:40});
    assert.equal(app.views.viewMonth,9,cancel);
  }
  swipe(w,d);
  assert.equal(app.views.viewMonth,10,'A cancelled gesture must not block the next swipe');
});
test('swipes respect navigation bounds, year changes, dialogs and reduced motion',t => {
  const {w,d,app} = boot(t,{reducedMotion:true});
  app.views.navigateMonth(app.views.minYear(),0);
  swipe(w,d,{from:70,to:260});
  assert.equal(app.views.viewYear,2025);
  assert.equal(app.views.viewMonth,0);
  app.views.navigateMonth(app.views.maxYear(),11);
  swipe(w,d);
  assert.equal(app.views.viewYear,2027);
  assert.equal(app.views.viewMonth,11);
  app.views.navigateMonth(2026,11);
  swipe(w,d);
  assert.equal(app.views.viewYear,2027);
  assert.equal(app.views.viewMonth,0);
  app.views.openPicker();
  swipe(w,d);
  assert.equal(app.views.viewMonth,0);
  app.views.closePicker();
  pointer(w,d.querySelector('.calendar'),'pointerdown');
  app.settings.openSettings();
  pointer(w,w,'pointerup',{x:20});
  assert.equal(app.views.viewMonth,0,'Opening a dialog mid-gesture must stop navigation');
});
test('slow horizontal holds do not navigate and today retains its accessible date marker',t => {
  const {w,d,app} = boot(t,{now:'2026-10-18T12:00:00'});
  let elapsed = 100;
  w.performance.now = () => elapsed;
  pointer(w,d.querySelector('.calendar'),'pointerdown');
  elapsed += 1200;
  pointer(w,w,'pointerup',{x:20});
  assert.equal(app.views.viewMonth,9);
  const today = d.querySelector('[aria-current="date"]');
  assert.equal(today.dataset.date,'2026-10-18');
  assert.equal(today.querySelector('.today-label').textContent,'今');
  assert.equal(today.querySelector('.holiday-badge').textContent,'重阳节');
});
test('ambient scene observation only enables visible decorations',t => {
  const {w,d,app} = boot(t);
  let callback;
  const observed = [];
  w.IntersectionObserver = class {
    constructor(handler) { callback = handler; }
    observe(element) { observed.push(element); }
  };
  app.ui.observeAmbientMotion();
  assert.deepEqual(observed.map(scene=>scene.classList.contains('workspace-intro') ? 'hero' : scene.classList.contains('today-card') ? 'today' : scene.id),['hero','today','calendarSection']);
  const hero = d.querySelector('.workspace-intro');
  callback([{target:hero,isIntersecting:false}]);
  assert.equal(hero.classList.contains('ambient-visible'),false);
  callback([{target:hero,isIntersecting:true}]);
  assert.equal(hero.classList.contains('ambient-visible'),true);
  const calendar = d.querySelector('#calendarSection');
  callback([{target:calendar,isIntersecting:false}]);
  assert.equal(calendar.classList.contains('ambient-visible'),false);
  callback([{target:calendar,isIntersecting:true}]);
  assert.equal(calendar.classList.contains('ambient-visible'),true);
});
test('muted adjacent dates complete weeks without adding selectable dates or breaking February',t => {
  const {d,app} = boot(t);
  const adjacent = [...d.querySelectorAll('#daysGrid .empty')];
  assert.deepEqual(adjacent.map(cell=>cell.querySelector('.day-num').textContent),['28','29','30','1']);
  assert.deepEqual(adjacent.map(cell=>cell.querySelector('.adjacent-month').textContent),['9月','9月','9月','11月']);
  assert.ok(adjacent.every(cell=>cell.tagName==='DIV' && cell.getAttribute('aria-hidden')==='true'));
  assert.equal(d.querySelector('#daysGrid').children.length,35);
  app.views.navigateMonth(2026,1);
  assert.equal(d.querySelectorAll('#daysGrid button').length,28);
  assert.equal(d.querySelector('#daysGrid').children.length,35);
  app.views.navigateMonth(2026,2);
  assert.equal(d.querySelector('#daysGrid').children.length,42);
});
test('default initialization renders a full month and twelve accessible month buttons',t => {
  const {d, app} = boot(t);
  assert.equal(d.querySelectorAll('#daysGrid button').length,31);
  assert.equal(d.querySelectorAll('#heatmapGrid button').length,12);
  assert.equal(d.querySelectorAll('#statsContainer .stat-card').length,3);
  assert.equal(d.querySelector('#workHours').textContent,'192');
  assert.equal(d.querySelector('#storageNotice').hidden,true);
});
test('monthly balance reports working days rather than individual shift colors and follows month navigation',t => {
  const {d,app} = boot(t);
  assert.equal(d.querySelector('#workDays').textContent,'16 天');
  assert.equal(d.querySelector('#restDays').textContent,'15 天');
  assert.equal(d.querySelector('#workPercent').textContent,'52%');
  assert.equal(d.querySelector('#restPercent').textContent,'48%');
  assert.match(d.querySelector('#workBalance').getAttribute('aria-label'),/工作16天，占52%；休息15天，占48%/);
  app.views.navigateMonth(2026,1);
  assert.equal(d.querySelector('#monthDays').textContent,'全月 28 天');
  assert.equal(Number.parseInt(d.querySelector('#workDays').textContent) + Number.parseInt(d.querySelector('#restDays').textContent),28);
  assert.match(d.querySelector('#workBalance').getAttribute('aria-label'),/^2月共28天/);
});
test('monthly balance uses work attributes even with reversed colors and handles zero-length segments',t => {
  for (const isWork of [true,false]) {
    const config = {anchor:'2026-04-18',cycle:['自定义'],shiftDefs:{'自定义':{cssClass:isWork ? 'rest' : 'day',emoji:'○',isWork,startTime:'08:00',endTime:'16:00'}}};
    const {d} = boot(t,{storage:{'qingzheng-config':JSON.stringify(config)}});
    assert.equal(d.querySelector('#workDays').textContent,isWork ? '31 天' : '0 天');
    assert.equal(d.querySelector('#restDays').textContent,isWork ? '0 天' : '31 天');
    assert.equal(d.querySelector('#workPercent').textContent,isWork ? '100%' : '0%');
    assert.equal(d.querySelector('#workBalanceWork').hidden,!isWork);
    assert.equal(d.querySelector('#workBalanceRest').hidden,isWork);
    assert.equal(d.querySelector('#workBalanceWork').style.flexGrow,isWork ? '31' : '0');
    assert.equal(d.querySelector('#workBalanceRest').style.flexGrow,isWork ? '0' : '31');
  }
});
test('broken JSON, empty cycles and blocked storage all retain a usable calendar',t => {
  for (const options of [{storage:{'qingzheng-cycle':'not JSON'}},{storage:{'qingzheng-cycle':'[]','qingzheng-anchor':'2026-02-31'}},{blocked:true}]) {
    const {d, app} = boot(t,options);
    assert.equal(d.querySelectorAll('#daysGrid button').length,31);
    assert.equal(d.querySelector('#storageNotice').hidden,false);
  }
});
test('legacy settings and presets migrate without an active preset overwriting current settings',t => {
  const preset = {id:'old',name:'旧方案',anchor:'2026-04-18',cycle:['白班'],shiftDefs:{}};
  const {app, d} = boot(t,{storage:{'qingzheng-cycle':'["休息"]','qingzheng-presets':JSON.stringify([preset]),'qingzheng-active-preset':'old'}});
  assert.equal(app.schedule.cycle[0],'休息');
  assert.equal(d.querySelector('#todayShift').textContent.includes('休息'),true);
});
test('settings cancellation discards additions, removals and in-form edits',t => {
  const {w, app, d} = boot(t); const before = JSON.stringify(app.schedule.captureCurrentConfig());
  app.settings.openSettings();
  d.querySelector('[data-shift="白班"] [data-field="startTime"]').value='07:30';
  d.querySelector('#shiftName').value='新班'; app.settings.addShiftDef();
  assert.equal(d.querySelector('[data-shift="白班"] [data-field="startTime"]').value,'07:30');
  app.settings.removeShiftDef('夜班'); d.querySelector('#confirmAccept').click(); app.settings.closeSettings();
  assert.equal(JSON.stringify(app.schedule.captureCurrentConfig()),before);
  app.settings.openSettings(); assert.equal(d.querySelector('[data-shift="白班"] [data-field="startTime"]').value,'08:00');
  assert.equal(d.querySelector('[data-shift="新班"]'),null);
});
test('default restoration first confirms and every cancellation preserves the unfinished draft',t => {
  for (const cancel of ['button','escape','backdrop']) {
    const {app,w,d} = boot(t); app.settings.openSettings();
    d.querySelector('#anchorDate').value = '2026-10-19';
    d.querySelector('#cyclePattern').value = '夜班 休息 夜班';
    d.querySelector('[data-shift="夜班"] [data-field="startTime"]').value = '22:30';
    d.querySelector('#shiftName').value = '还没添加'; app.settings.settingsError('尚未完成的编辑');
    const snapshot = () => JSON.stringify([d.querySelector('#anchorDate').value,d.querySelector('#cyclePattern').value,
      d.querySelector('#shiftName').value,app.settings.readDraftRows(),d.querySelector('#settingsError').textContent]);
    const before = snapshot(), persisted = {...w.localStorage};
    const button = d.querySelector('[data-action="restoreDefaultSettings"]'); button.focus(); button.click();
    assert.equal(app.ui.activeDialog,'confirmModal'); assert.equal(snapshot(),before);
    assert.equal(d.querySelector('#confirmTitle').textContent,'恢复默认排班');
    assert.match(d.querySelector('#confirmDescription').textContent,/已保存方案不会删除/);
    if (cancel === 'button') d.querySelector('#confirmCancel').click();
    else if (cancel === 'escape') d.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true}));
    else d.querySelector('#confirmModal').click();
    assert.equal(app.ui.activeDialog,'settingsModal'); assert.equal(d.activeElement,button);
    assert.equal(snapshot(),before); assert.deepEqual({...w.localStorage},persisted);
    assert.equal(app.liveTimer,null);
  }
});
test('restoring defaults repairs an invalid draft without touching live data, presets or preferences',t => {
  const config = Core.normalizeConfig({anchor:'2026-10-01',cycle:['自定义'],shiftDefs:{'自定义':{isWork:true,startTime:'22:00',endTime:'06:00',crossDay:true}}});
  const preset = {...config,id:'keep-me',name:'原有方案'};
  const {app,w,d} = boot(t,{storage:{'qingzheng-config':JSON.stringify(config),'qingzheng-presets':JSON.stringify([preset]),
    'qingzheng-theme':'dark','qingzheng-theme-mode':'manual','qingzheng-anchor':'2025-01-01'}});
  const before = {...w.localStorage}, presets = JSON.stringify(app.settings.presets);
  app.settings.openSettings(); d.querySelector('#anchorDate').value = ''; d.querySelector('#cyclePattern').value = '';
  d.querySelector('#shiftName').value = '未完成'; app.settings.saveSettings();
  assert.equal(d.querySelector('#settingsError').hidden,false);
  d.querySelector('[data-action="restoreDefaultSettings"]').click(); d.querySelector('#confirmAccept').click();
  assert.equal(app.ui.activeDialog,'settingsModal'); assert.equal(app.liveTimer,null);
  assert.equal(d.querySelector('#anchorDate').value,DEFAULT_ANCHOR);
  assert.equal(d.querySelector('#cyclePattern').value,DEFAULT_CYCLE.join(' '));
  assert.deepEqual(app.settings.readDraftRows(),Core.normalizeDefs(DEFAULT_SHIFT_DEFS));
  assert.equal(d.querySelectorAll('#cyclePreviewDays li').length,DEFAULT_CYCLE.length);
  assert.equal(d.querySelector('#shiftName').value,''); assert.equal(d.querySelector('#settingsError').hidden,true);
  assert.equal(d.querySelector('#settingsStatus').hidden,false); assert.match(d.querySelector('#settingsStatus').textContent,/尚未保存/);
  assert.equal(d.activeElement,d.querySelector('#settingsStatus'));
  assert.deepEqual(app.schedule.captureCurrentConfig(),config); assert.deepEqual({...w.localStorage},before);
  assert.equal(JSON.stringify(app.settings.presets),presets);
  d.querySelector('#settingsModal [data-action="closeSettings"]').click(); app.settings.openSettings();
  assert.equal(d.querySelector('#cyclePattern').value,'自定义'); assert.equal(d.querySelector('#anchorDate').value,config.anchor);
  assert.equal(d.querySelector('#settingsStatus').hidden,true);
  assert.deepEqual(app.settings.readDraftRows(),config.shiftDefs);
});
test('saving restored defaults matches first-run settings, survives reload and never mutates shared defaults',t => {
  const config = Core.normalizeConfig({anchor:'2026-10-01',cycle:['自定义'],shiftDefs:{}});
  const preset = {...config,id:'keep-me',name:'原有方案'};
  const {app,w,d} = boot(t,{storage:{'qingzheng-config':JSON.stringify(config),'qingzheng-presets':JSON.stringify([preset])}});
  const definitions = JSON.stringify(DEFAULT_SHIFT_DEFS), cycle = [...DEFAULT_CYCLE];
  const oldPresets = w.localStorage.getItem('qingzheng-presets');
  app.settings.openSettings(); app.settings.restoreDefaultSettings(); d.querySelector('#confirmAccept').click();
  d.querySelector('[data-shift="白班"] [data-field="startTime"]').value = '07:15';
  app.settings.restoreDefaultSettings(); d.querySelector('#confirmAccept').click();
  assert.equal(d.querySelector('[data-shift="白班"] [data-field="startTime"]').value,'08:00');
  assert.equal(JSON.stringify(DEFAULT_SHIFT_DEFS),definitions); assert.deepEqual(DEFAULT_CYCLE,cycle);
  assert.deepEqual(app.schedule.captureCurrentConfig(),config);
  d.querySelector('[data-action="saveSettings"]').click();
  const defaults = Core.normalizeConfig({anchor:DEFAULT_ANCHOR,cycle:DEFAULT_CYCLE,shiftDefs:DEFAULT_SHIFT_DEFS});
  assert.equal(app.ui.activeDialog,null); assert.deepEqual(app.schedule.captureCurrentConfig(),defaults);
  assert.equal(w.localStorage.getItem('qingzheng-presets'),oldPresets);
  assert.deepEqual(JSON.parse(w.localStorage.getItem('qingzheng-config')),JSON.parse(JSON.stringify(defaults)));
  const reload = boot(t,{storage:{...w.localStorage}});
  assert.deepEqual(reload.app.schedule.captureCurrentConfig(),defaults); assert.equal(reload.app.settings.presets.length,1);
  assert.deepEqual(boot(t).app.schedule.captureCurrentConfig(),defaults,'Recovery and first-run defaults share one source');
});
test('editing a restored draft clears the reset status and allows adjusting its anchor before saving',t => {
  const {app,w,d} = boot(t); app.settings.openSettings();
  app.settings.restoreDefaultSettings(); d.querySelector('#confirmAccept').click();
  const input = d.querySelector('#anchorDate'); input.value = '2026-10-19'; input.dispatchEvent(new w.Event('input',{bubbles:true}));
  assert.equal(d.querySelector('#settingsStatus').hidden,true);
  assert.match(d.querySelector('#cyclePreviewDays li').textContent,/10\/19/);
  app.settings.saveSettings(); assert.equal(app.schedule.captureCurrentConfig().anchor,'2026-10-19');
});
test('default restoration is scoped to the settings draft and preserves persisted data on a save failure',t => {
  const config = Core.normalizeConfig({anchor:'2026-10-01',cycle:['原排班'],shiftDefs:{}});
  const persisted = JSON.stringify(config), {app,w,d} = boot(t,{storage:{'qingzheng-config':persisted}});
  app.settings.restoreDefaultSettings(); assert.equal(app.ui.activeDialog,null);
  app.settings.openSettings(); app.settings.restoreDefaultSettings(); d.querySelector('#confirmAccept').click();
  w.Storage.prototype.setItem = () => {throw new Error('Quota exceeded');};
  app.settings.saveSettings();
  assert.deepEqual(app.schedule.cycle,DEFAULT_CYCLE); assert.equal(w.localStorage.getItem('qingzheng-config'),persisted);
  assert.equal(d.querySelector('#storageNotice').hidden,false); assert.match(d.querySelector('#toast').textContent,/暂时无法保存/);
  app.settings.restoreDefaultSettings(); assert.equal(app.ui.activeDialog,null);
});
test('settings validate work times and save all fields in one canonical record',t => {
  const {w, d, app} = boot(t); app.settings.openSettings();
  const input = d.querySelector('[data-shift="白班"] [data-field="startTime"]');
  input.value=''; app.settings.saveSettings(); assert.equal(d.querySelector('#settingsModal').hidden,false);
  assert.equal(d.querySelector('#settingsError').hidden,false);
  input.value='07:30'; app.settings.saveSettings(); assert.equal(d.querySelector('#settingsModal').hidden,true);
  const saved = JSON.parse(w.localStorage.getItem('qingzheng-config'));
  assert.equal(saved.shiftDefs['白班'].startTime,'07:30');
  assert.equal(app.schedule.shiftDefs["白班"].startTime,'07:30');
  const reloaded = boot(t,{storage:{'qingzheng-config':JSON.stringify(saved)}});
  assert.equal(reloaded.app.schedule.shiftDefs["白班"].startTime,'07:30');
});
test('saving new names first presents their generated shift times for review',t => {
  const {w, d, app}=boot(t); app.settings.openSettings(); d.querySelector('#cyclePattern').value='新班 休息';
  app.settings.saveSettings(); assert.equal(d.querySelector('#settingsModal').hidden,false);
  assert.ok(d.querySelector('[data-shift="新班"]'));
  assert.equal(app.schedule.cycle.length,8);
  app.settings.saveSettings(); assert.equal(app.schedule.cycle.join(" "),'新班 休息');
});
test('custom names are text across the settings, today, statistics and detail views',t => {
  const name = '<img/src=x/onerror=alert(1)>';
  const {w, d, app}=boot(t,{storage:{'qingzheng-cycle':JSON.stringify([name,'constructor','__proto__'])}});
  app.settings.openSettings();
  assert.ok(d.querySelector('#shiftDefsEditor').textContent.includes(name));
  assert.equal(d.querySelectorAll('img').length,initialImageCount);
  app.settings.closeSettings(); app.views.showDetail(new w.Date());
  assert.equal(d.querySelectorAll('img').length,initialImageCount);
  assert.equal(Object.values(app.schedule.calculateMonthlyStats(2026,9)).reduce((a,b)=>a+b,0),31);
});
test('long cycles search the complete cycle and no-rest cycles display a useful message',t => {
  const {w, app, d} = boot(t);
  app.settings.commitConfig({anchor:'2026-10-16',cycle:[...Array(40).fill('白班'),'休息'],shiftDefs:{}});
  assert.equal(app.schedule.getDaysToRest(new w.Date(2026,9,16)),40);
  app.settings.commitConfig({anchor:'2026-10-16',cycle:[...Array(40).fill('休息'),'白班'],shiftDefs:{}});
  assert.ok(app.schedule.getNextWorkDelay(new w.Date(2026,9,16)) > 39*86400000);
  assert.equal(app.schedule.calculateNextShifts(new w.Date(2026,9,16))["白班"],40);
  app.settings.commitConfig({anchor:'2026-10-16',cycle:['白班'],shiftDefs:{}}); app.views.render(); app.views.showDetail(new w.Date());
  assert.equal(d.querySelector('#restCountdown').textContent,'周期内无休息日');
  assert.ok(d.querySelector('#detailExtra').textContent.includes('整个周期'));
  assert.equal(d.querySelector('#detailExtra').textContent.includes('-1'),false);
});
test('overnight card keeps the previous shift date and cycle position until its exact end',t => {
  const {app, w, d}=boot(t,{now:'2026-04-20T07:59:59'});
  const current = app.schedule.getEffectiveShiftInfo();
  assert.equal(current.shift,'夜班'); assert.equal(current.date.getDate(),19);
  assert.equal(d.querySelector('#todayCycle').textContent,'2');
  assert.ok(d.querySelector('#todayShiftTime').textContent.includes('昨日班次'));
  assert.equal(d.querySelector('#restCountdown').textContent,'本班结束后');
  assert.equal(app.schedule.getEffectiveShiftInfo(new w.Date(2026,3,20,8,0)).shift,'休息');
  assert.equal(app.schedule.formatCountdown(40*86400000),'40天 00:00:00');
});
test('navigation bounds apply to direct calls, keyboard navigation and picker years',t => {
  const {w, d, app}=boot(t);
  app.views.navigateMonth(2025,0); app.views.prevMonth(); assert.equal(app.views.viewYear,2025); assert.equal(app.views.viewMonth,0);
  app.views.navigateMonth(2027,11); app.views.nextMonth(); assert.equal(app.views.viewYear,2027); assert.equal(app.views.viewMonth,11);
  d.dispatchEvent(new w.KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true})); assert.equal(app.views.viewYear,2027);
  app.views.openPicker(); app.views.pickerNextYear(); assert.equal(app.views.pickerYear,2027);
  const before=app.views.viewMonth; d.dispatchEvent(new w.KeyboardEvent('keydown',{key:'ArrowLeft',bubbles:true})); assert.equal(app.views.viewMonth,before);
});
test('dialogs trap focus, restore their opener and close with Escape',t => {
  const {w, d, app}=boot(t); const opener=d.querySelector('[aria-label="打开排班设置"]'); opener.focus(); app.settings.openSettings();
  assert.equal(d.querySelector('.main-container').inert,true);
  const save=d.querySelector('.btn-group .button-primary'); save.focus();
  save.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Tab',bubbles:true,cancelable:true}));
  assert.equal(d.activeElement.getAttribute('aria-label'),'关闭排班设置');
  d.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Escape',bubbles:true}));
  assert.equal(d.querySelector('#settingsModal').hidden,true); assert.equal(d.activeElement,opener);
});
test('preset add/apply/delete preserves live settings and restores the saved configuration',t => {
  const {w, d, app}=boot(t); app.settings.saveAsPreset();
  d.querySelector('#presetName').value='测试方案';
  d.querySelector('#presetForm').dispatchEvent(new w.Event('submit',{bubbles:true,cancelable:true}));
  assert.equal(d.querySelectorAll('.preset-group').length,1);
  assert.equal(d.querySelectorAll('.preset-btn button').length,0);
  const id=app.settings.presets[0].id, before=JSON.stringify(app.schedule.captureCurrentConfig());
  app.settings.openSettings(); d.querySelector('#cyclePattern').value='休息'; app.settings.saveSettings();
  app.settings.applyPreset(id); assert.equal(JSON.stringify(app.schedule.captureCurrentConfig()),before);
  app.settings.deletePreset(id); d.querySelector('#confirmAccept').click(); assert.equal(app.schedule.cycle.length,8); assert.equal(d.querySelectorAll('.preset-group').length,0);
});
test('saving presets works when native WebView prompts return null, throw or are missing',t => {
  for (const prompt of [()=>null,()=>{throw new Error('Native dialogs are disabled');},undefined]) {
    const {w, d, app}=boot(t);
    w.prompt=prompt;w.confirm=()=>{throw new Error('Native confirmation is disabled');};
    const opener=d.querySelector('[data-action="saveAsPreset"]');opener.focus();opener.click();
    assert.equal(d.querySelector('#presetModal').hidden,false);
    assert.equal(d.activeElement.id,'presetForm');
    assert.equal(d.querySelector('.main-container').inert,true);
    assert.equal(app.settings.presets.length,0);
    d.querySelector('#presetName').value='应用内方案';
    const event=new w.Event('submit',{bubbles:true,cancelable:true});
    d.querySelector('#presetForm').dispatchEvent(event);
    assert.equal(event.defaultPrevented,true);
    assert.equal(d.querySelector('#presetModal').hidden,true);
    assert.equal(app.settings.presets.length,1);
    assert.equal(JSON.parse(w.localStorage.getItem('qingzheng-presets'))[0].name,'应用内方案');
    assert.equal(d.querySelector('#toast').textContent,'当前排班已保存为方案');
    assert.equal(d.activeElement,opener);
    assert.equal(w.location.href,'http://localhost/');
  }
});
test('preset naming validates blank, long and duplicate names inside the dialog',t => {
  const preset={id:'existing',name:'已有方案',anchor:'2026-04-18',cycle:['白班'],shiftDefs:{}};
  const {w, d, app}=boot(t,{storage:{'qingzheng-presets':JSON.stringify([preset])}});
  const previous=w.localStorage.getItem('qingzheng-presets');
  app.settings.saveAsPreset();
  const input=d.querySelector('#presetName'),error=d.querySelector('#presetError');
  for (const [name,expected] of [['   ','请填写'],['长'.repeat(41),'最多 40'],[' 已有方案 ','同名']]) {
    input.value=name;app.settings.submitPreset();
    assert.equal(d.querySelector('#presetModal').hidden,false);
    assert.equal(error.hidden,false);assert.ok(error.textContent.includes(expected));
    assert.equal(input.getAttribute('aria-invalid'),'true');
    assert.equal(d.activeElement,input);
    assert.equal(app.settings.presets.length,1);
    assert.equal(w.localStorage.getItem('qingzheng-presets'),previous);
    input.dispatchEvent(new w.Event('input',{bubbles:true}));
    assert.equal(error.hidden,true);assert.equal(input.getAttribute('aria-invalid'),'false');
  }
  input.value='新'.repeat(40);app.settings.submitPreset();
  assert.equal(app.settings.presets.length,2);
  assert.equal(d.querySelector('#presetModal').hidden,true);
});
test('cancel, close, Escape and backdrop dismiss preset naming without modifying storage',t => {
  for (const method of ['cancel','close','escape','backdrop']) {
    const {w, d, app}=boot(t);
    const opener=d.querySelector('[data-action="saveAsPreset"]');opener.focus();opener.click();
    d.querySelector('#presetName').value='还没确认';
    if (method==='cancel') d.querySelector('#presetModal .btn-group button[type="button"]').click();
    else if (method==='close') d.querySelector('[aria-label="关闭保存方案"]').click();
    else if (method==='escape') d.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true}));
    else d.querySelector('#presetModal').click();
    assert.equal(d.querySelector('#presetModal').hidden,true,method);
    assert.equal(app.settings.presets.length,0,method);
    assert.equal(w.localStorage.getItem('qingzheng-presets'),null,method);
    assert.equal(d.querySelector('.main-container').inert,false,method);
    assert.equal(d.body.style.overflow,'',method);
    assert.equal(d.activeElement,opener,method);
    opener.click();assert.equal(d.querySelector('#presetName').value,'',method);
    assert.equal(d.querySelector('#presetError').hidden,true,method);
  }
});
test('repeated open and submit events neither clear an unfinished name nor save twice',t => {
  const {w, d, app}=boot(t);app.settings.saveAsPreset();
  const input=d.querySelector('#presetName');input.value='  轮班方案  ';
  app.settings.saveAsPreset();assert.equal(input.value,'  轮班方案  ');
  for (let i=0;i<2;i++) d.querySelector('#presetForm').dispatchEvent(new w.Event('submit',{bubbles:true,cancelable:true}));
  assert.equal(app.settings.presets.length,1);
  assert.equal(app.settings.presets[0].name,'轮班方案');
  assert.equal(d.querySelector('#presetModal').hidden,true);
});
test('unavailable or quota-blocked storage reports that a preset is only kept for the session',t => {
  for (const blocked of [true,false]) {
    const {w, d, app}=boot(t,{blocked});
    if (!blocked) w.Storage.prototype.setItem=()=>{throw new Error('Quota exceeded');};
    w.prompt=()=>{throw new Error('Prompt must not be used');};
    app.settings.saveAsPreset();d.querySelector('#presetName').value='临时方案';app.settings.submitPreset();
    assert.equal(app.settings.presets.length,1);
    assert.equal(d.querySelector('#presetModal').hidden,true);
    assert.equal(d.querySelector('#storageNotice').hidden,false);
    assert.equal(d.querySelector('#toast').textContent,'方案仅在本次打开期间有效');
    assert.equal(d.querySelector('.preset-btn').textContent,'临时方案');
    if (!blocked) assert.equal(w.localStorage.getItem('qingzheng-presets'),null);
  }
});
test('preset names remain text in buttons and descriptions after form submission',t => {
  const {w, d, app}=boot(t);const name='<img/src=x/onerror=alert(1)>';
  app.settings.saveAsPreset();d.querySelector('#presetName').value=name;app.settings.submitPreset();
  assert.equal(d.querySelector('.preset-btn').textContent,name);
  assert.equal(d.querySelector('.preset-btn').title,`应用方案「${name}」`);
  assert.equal(d.querySelectorAll('img').length,initialImageCount);
  assert.equal(app.settings.presets[0].name,name);
});
test('form-created presets keep the existing schema and restore the complete schedule on reload',t => {
  const {w, d, app}=boot(t);
  app.settings.openSettings();d.querySelector('[data-shift="白班"] [data-field="startTime"]').value='07:15';app.settings.saveSettings();
  const original=JSON.stringify(app.schedule.captureCurrentConfig());
  app.settings.saveAsPreset();d.querySelector('#presetName').value='早一点上班';app.settings.submitPreset();
  const saved=w.localStorage.getItem('qingzheng-presets');
  app.settings.commitConfig({anchor:'2026-10-16',cycle:['休息'],shiftDefs:{}});
  const config=w.localStorage.getItem('qingzheng-config');
  const reloaded=boot(t,{storage:{'qingzheng-presets':saved,'qingzheng-config':config}});
  assert.equal(reloaded.app.settings.presets.length,1);
  reloaded.d.querySelector('.preset-btn').click();
  assert.equal(JSON.stringify(reloaded.app.schedule.captureCurrentConfig()),original);
  assert.equal(reloaded.app.schedule.shiftDefs["白班"].startTime,'07:15');
});
test('preset dialogs trap keyboard focus and block calendar shortcuts without breaking other dialogs',t => {
  const {w, d, app}=boot(t);app.settings.saveAsPreset();
  const month=app.views.viewMonth,first=d.querySelector('[aria-label="关闭保存方案"]'),last=d.querySelector('#presetSubmit');
  last.focus();last.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Tab',bubbles:true,cancelable:true}));
  assert.equal(d.activeElement,first);
  first.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Tab',shiftKey:true,bubbles:true,cancelable:true}));
  assert.equal(d.activeElement,last);
  d.dispatchEvent(new w.KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true,cancelable:true}));
  assert.equal(app.views.viewMonth,month);
  d.querySelector('#presetName').focus();
  d.querySelector('#presetName').dispatchEvent(new w.KeyboardEvent('keydown',{key:'t',bubbles:true,cancelable:true}));
  assert.equal(app.views.viewMonth,month);
  app.settings.closePresetDialog();app.views.openPicker();
  d.querySelector('#pickerModal').click();assert.equal(d.querySelector('#pickerModal').hidden,true);
  app.settings.openSettings();d.querySelector('#settingsModal').click();
  assert.equal(d.querySelector('#settingsModal').hidden,true);assert.equal(app.settings.draftDefs,null);
});
test('Enter during Chinese input composition does not submit an unfinished preset name',t => {
  const {w, d, app}=boot(t);app.settings.saveAsPreset();
  const input=d.querySelector('#presetName');input.value='还在输入';
  for (const options of [{isComposing:true},{keyCode:229}]) {
    const event=new w.KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true,...options});
    input.dispatchEvent(event);assert.equal(event.defaultPrevented,true);
  }
  assert.equal(app.settings.presets.length,0);assert.equal(d.querySelector('#presetModal').hidden,false);
  const enter=new w.KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true});
  input.dispatchEvent(enter);assert.equal(enter.defaultPrevented,false);
});
test('preset naming supports reduced motion and theme changes while preserving the entered name',t => {
  const {w, d, app}=boot(t,{reducedMotion:true});
  app.views.nextMonth();const before=JSON.stringify(app.schedule.captureCurrentConfig());
  app.settings.saveAsPreset();d.querySelector('#presetName').value='双主题方案';
  for (const theme of ['dark','light']) {
    app.setTheme(theme);assert.equal(d.querySelector('#presetName').value,'双主题方案');
    assert.equal(d.querySelector('#presetModal').hidden,false);
  }
  app.settings.submitPreset();
  const saved=JSON.stringify(Core.normalizeConfig(app.settings.presets[0]));
  assert.equal(saved,before);
  assert.equal(d.querySelector('#presetModal').hidden,true);
});
test('dialogs have no entry animation and input pages wait for a deliberate input focus',t => {
  const {w, d, app}=boot(t,{reducedMotion:false}),animated=[];
  w.Element.prototype.animate = function() {
    animated.push(this);
    return {finished:Promise.resolve(),cancel(){}};
  };
  app.settings.saveAsPreset();assert.equal(d.activeElement.id,'presetForm');
  assert.equal(d.documentElement.classList.contains('input-view-open'),true);
  assert.equal(d.querySelector('#presetModal').classList.contains('input-view'),true);
  assert.equal(d.body.style.overflow,'');
  d.querySelector('#presetName').focus();assert.equal(d.activeElement.id,'presetName');
  app.settings.closePresetDialog();app.settings.openSettings();
  assert.equal(d.querySelector('#settingsModal').classList.contains('input-view'),true);
  assert.equal(d.activeElement.getAttribute('role'),'dialog');
  assert.deepEqual(animated,[]);
  app.settings.closeSettings();app.views.openPicker();
  assert.deepEqual(animated,[]);assert.equal(d.documentElement.classList.contains('input-view-open'),false);
});
test('opening a dialog cancels existing feedback and blocks scripted animations during input',t => {
  const {w, d, app}=boot(t,{reducedMotion:false});let started=0,cancelled=0;
  w.Element.prototype.animate = function() {
    started++;
    return {finished:Promise.resolve(),cancel(){cancelled++;}};
  };
  app.ui.animateElement(d.querySelector('#companionCharacter'),[{opacity:0},{opacity:1}]);
  assert.equal(started,1);assert.equal(app.ui.runningAnimations.size,1);
  app.settings.saveAsPreset();assert.equal(cancelled,1);assert.equal(app.ui.runningAnimations.size,0);
  app.ui.animateElement(d.querySelector('#presetForm'),[{opacity:0},{opacity:1}]);assert.equal(started,1);
  app.settings.closePresetDialog();app.ui.animateElement(d.querySelector('#companionCharacter'),[{opacity:0},{opacity:1}]);
  assert.equal(started,2);
});
test('keyboard events schedule no layout work, style writes or refocusing in the static input page',t => {
  const {w, d, app}=boot(t),{viewport,pending,flush}=keyboardViewport(w);
  const overlay=d.querySelector('#presetModal'),form=d.querySelector('#presetForm'),input=d.querySelector('#presetName');
  const focus=input.focus.bind(input);let focusCalls=0,writes=0;
  input.focus = options => {focusCalls++;focus(options);};
  const setProperty=overlay.style.setProperty.bind(overlay.style);
  overlay.style.setProperty = (...args) => {writes++;setProperty(...args);};
  app.settings.saveAsPreset();input.value='键盘动画期间的草稿';input.focus();
  form.getBoundingClientRect = input.getBoundingClientRect = () => {throw new Error('Keyboard events must not measure layout');};
  w.scrollTo = () => {throw new Error('Keyboard events must not force scrolling');};
  for (let frame=0;frame<60;frame++) {
    viewport.height=844-frame*6;viewport.offsetTop=frame/3;
    viewport.dispatchEvent(new w.Event('resize'));viewport.dispatchEvent(new w.Event('scroll'));
    w.dispatchEvent(new w.Event('resize'));
  }
  assert.equal(pending.size,0);flush();assert.equal(writes,0);
  assert.equal(overlay.style.cssText,'');assert.equal(focusCalls,1);assert.equal(d.activeElement,input);
  assert.equal(d.querySelector('#presetForm'),form);assert.equal(input.value,'键盘动画期间的草稿');
  viewport.dispatchEvent(new w.Event('resize'));flush();assert.equal(writes,0);
  input.blur();viewport.height=844;viewport.offsetTop=0;viewport.dispatchEvent(new w.Event('resize'));flush();
  assert.equal(pending.size,0);assert.equal(overlay.style.cssText,'');
  assert.equal(focusCalls,1);assert.equal(input.value,'键盘动画期间的草稿');
});
test('closing an input page removes its mode without leaving keyboard work for the next dialog',t => {
  const {w, d, app}=boot(t),{viewport,pending,flush}=keyboardViewport(w),overlay=d.querySelector('#presetModal');
  app.settings.saveAsPreset();viewport.height=430;viewport.dispatchEvent(new w.Event('resize'));
  assert.equal(pending.size,0);app.settings.closePresetDialog();
  assert.equal(overlay.classList.contains('input-view'),false);
  assert.equal(d.documentElement.classList.contains('input-view-open'),false);
  app.views.openPicker();
  viewport.dispatchEvent(new w.Event('resize'));viewport.dispatchEvent(new w.Event('scroll'));w.dispatchEvent(new w.Event('resize'));flush();
  assert.equal(pending.size,0);assert.equal(overlay.style.cssText,'');
  assert.equal(d.querySelector('#pickerModal').hidden,false);
});
test('the plain input page does not require or even read the Visual Viewport API',t => {
  const {w, d, app}=boot(t);
  Object.defineProperty(w,'visualViewport',{get(){throw new Error('No viewport access is needed');},configurable:true});
  app.settings.saveAsPreset();const input=d.querySelector('#presetName');input.value='旧版内嵌浏览器';input.focus();
  w.dispatchEvent(new w.Event('resize'));
  assert.equal(input.value,'旧版内嵌浏览器');assert.equal(d.activeElement,input);
  app.settings.submitPreset();assert.equal(d.querySelector('.preset-btn').textContent,'旧版内嵌浏览器');
});
test('rapid reopening and switching input pages retain only the active page and its draft',t => {
  const {w, d, app}=boot(t),{viewport,pending,flush}=keyboardViewport(w);
  app.settings.saveAsPreset();viewport.height=440;viewport.dispatchEvent(new w.Event('resize'));
  app.settings.closePresetDialog();viewport.height=700;app.settings.saveAsPreset();
  assert.equal(pending.size,0);flush();
  assert.equal(d.querySelector('#presetModal').classList.contains('input-view'),true);
  viewport.height=400;viewport.dispatchEvent(new w.Event('resize'));app.settings.openSettings();
  assert.equal(pending.size,0);assert.equal(d.querySelector('#presetModal').style.cssText,'');
  const overlay=d.querySelector('#settingsModal'),input=d.querySelector('#cyclePattern');
  assert.equal(overlay.classList.contains('input-view'),true);
  assert.equal(d.querySelector('#presetModal').classList.contains('input-view'),false);
  input.value='白班 休息';input.focus();viewport.height=300;viewport.dispatchEvent(new w.Event('resize'));flush();
  assert.equal(overlay.style.cssText,'');
  assert.equal(d.activeElement,input);assert.equal(input.value,'白班 休息');
  app.settings.closeSettings();assert.equal(overlay.style.cssText,'');
});
test('input pauses the live timer and background writes, then catches up when closed',t => {
  const {w, d, app, setNow}=boot(t),before=d.querySelector('.main-container').innerHTML,clock=d.querySelector('#liveClock').textContent;
  assert.notEqual(app.liveTimer,null);app.settings.saveAsPreset();assert.equal(app.liveTimer,null);
  d.querySelector('#presetName').value='不受秒表刷新影响';
  setNow('2026-10-16T12:00:15');app.updateLiveClockAndCountdown();
  assert.equal(d.querySelector('.main-container').innerHTML,before);
  assert.equal(d.querySelector('#liveClock').textContent,clock);
  assert.equal(d.querySelector('#presetName').value,'不受秒表刷新影响');
  app.settings.closePresetDialog();assert.notEqual(app.liveTimer,null);
  assert.notEqual(d.querySelector('#liveClock').textContent,clock);
});
test('midnight refresh is deferred while typing and applied after closing without losing the draft',t => {
  const {w, d, app, setNow}=boot(t,{now:'2026-10-16T23:59:59'});
  app.settings.saveAsPreset();d.querySelector('#presetName').value='跨过午夜';
  setNow('2026-10-17T00:00:01');app.updateLiveClockAndCountdown();
  assert.equal(app.lastDayKey,'2026-10-16');assert.equal(d.querySelector('#presetName').value,'跨过午夜');
  app.settings.submitPreset();assert.equal(app.lastDayKey,'2026-10-17');assert.equal(d.querySelector('.preset-btn').textContent,'跨过午夜');
});
test('input page cancellation restores the calendar scroll position and opening control',t => {
  const {w, d, app}=boot(t),opener=d.querySelector('[data-action="saveAsPreset"]'),scrolls=[];
  w.scrollX=0;w.scrollY=640;
  w.scrollTo = (x,y) => {scrolls.push([x,y]);w.scrollX=x;w.scrollY=y;};
  opener.focus();app.settings.saveAsPreset();assert.deepEqual(scrolls,[[0,0]]);
  w.scrollY=60;app.settings.closePresetDialog();assert.deepEqual(scrolls,[[0,0],[0,640]]);
  assert.equal(d.activeElement,opener);assert.equal(d.querySelector('.main-container').inert,false);
  assert.equal(d.documentElement.classList.contains('input-view-open'),false);
});
test('repeated input page opening and inactive closes do not create duplicate live timers',t => {
  const {w, app}=boot(t),timers=new Set(),clear=w.clearInterval.bind(w);let next=100;
  w.setInterval = () => {const id=next++;timers.add(id);return id;};
  w.clearInterval = id => {timers.delete(id);clear(id);};
  for(let round=0;round<5;round++) {
    app.settings.saveAsPreset();assert.equal(timers.size,0);assert.equal(app.liveTimer,null);
    app.settings.closePresetDialog();assert.equal(timers.size,1);
    app.settings.closeSettings();assert.equal(timers.size,1);
  }
});
test('all shift types are included in monthly statistics, including the fourth and later shifts',t => {
  const {w, d, app}=boot(t,{storage:{'qingzheng-cycle':'["白班","夜班","中班","休息"]'}});
  assert.equal(d.querySelectorAll('#statsContainer .stat-card').length,4);
  d.querySelector('#themeToggle').click(); assert.equal(d.documentElement.dataset.theme,'dark');
  assert.equal(w.localStorage.getItem('qingzheng-theme'),'dark');
});
test('solar terms use the corrected annual table and never fall back to 2026 for unknown years',t => {
  const {w, app}=boot(t);
  assert.equal(app.festivals.getSolarTerm(new w.Date(2026,2,5)),'惊蛰');
  assert.equal(app.festivals.getSolarTerm(new w.Date(2026,2,6)),null);
  assert.equal(app.festivals.getSolarTerm(new w.Date(2026,9,23)),'霜降');
  assert.equal(app.festivals.getSolarTerm(new w.Date(2040,9,23)),null);
});
test('midnight and the end of an overnight shift update the date, cycle and visible calendar',t => {
  const {w, d, setNow, app}=boot(t,{now:'2026-04-19T23:59:59'});
  assert.equal(d.querySelector('#todayCycle').textContent,'2');
  setNow('2026-04-20T00:00:00'); app.updateLiveClockAndCountdown();
  assert.ok(d.querySelector('#todayDate').textContent.includes('4月20日'));
  assert.equal(d.querySelector('.day-cell.today').dataset.date,'2026-04-20');
  assert.equal(d.querySelector('#todayCycle').textContent,'2');
  setNow('2026-04-20T08:00:00'); app.updateLiveClockAndCountdown();
  assert.equal(d.querySelector('#todayCycle').textContent,'3');
  assert.ok(d.querySelector('#todayShift').textContent.includes('休息'));
});
test('a write failure keeps session changes usable and displays the persistence limitation',t => {
  const {w, d, app}=boot(t,{blocked:true});
  app.settings.openSettings(); d.querySelector('#cyclePattern').value='休息'; app.settings.saveSettings();
  assert.equal(app.schedule.cycle.length,1);
  assert.equal(d.querySelector('#settingsModal').hidden,true);
  assert.equal(d.querySelector('#storageNotice').hidden,false);
  assert.ok(d.querySelector('#todayShift').textContent.includes('休息'));
});
test('removing an unused default definition survives saving and reload',t => {
  const {w, d, app}=boot(t); app.settings.openSettings(); app.settings.removeShiftDef('中班'); d.querySelector('#confirmAccept').click(); app.settings.saveSettings();
  const saved=w.localStorage.getItem('qingzheng-config');
  const restored=boot(t,{storage:{'qingzheng-config':saved}});
  assert.equal(Object.hasOwn(restored.app.schedule.shiftDefs,"中班"),false);
});
test('upcoming dates cross month boundaries and open the correct calendar day',t => {
  const {w, d, app}=boot(t,{now:'2026-10-29T12:00:00'});
  const days=[...d.querySelectorAll('#upcomingList button')];
  assert.equal(days.length,7);
  assert.equal(days[0].dataset.date,'2026-10-29');
  assert.equal(days[6].dataset.date,'2026-11-04');
  days[6].click();
  assert.equal(app.views.viewMonth,10);
  assert.equal(d.querySelector('#daysGrid .selected').dataset.date,'2026-11-04');
  assert.equal(d.querySelector('#upcomingPanel').hidden,true);
  assert.equal(d.querySelector('#detailPanel').classList.contains('show'),true);
  app.views.closeDetail();
  assert.equal(d.querySelector('#upcomingPanel').hidden,false);
  assert.equal(d.querySelectorAll('#upcomingList button').length,7);
});
test('date inspection swaps the sidebar without recreating the month grid',t => {
  const {w, d, app}=boot(t);
  const first=d.querySelector('#daysGrid button');
  first.click();
  assert.equal(first.isConnected,true);
  assert.equal(first.getAttribute('aria-pressed'),'true');
  assert.equal(d.querySelector('#detailHeading').textContent,'10月1日 · 周四');
  app.views.closeDetail();
  assert.equal(first.isConnected,true);
  assert.equal(first.getAttribute('aria-pressed'),'false');
});
test('automatic themes follow 07:00 and 19:00 while respecting manual choices',t => {
  const {w, d, setNow, app}=boot(t,{now:'2026-10-16T06:59:59'});
  assert.equal(d.documentElement.dataset.theme,'dark');
  assert.equal(d.querySelector('#themeMood').textContent,'蓝夜');
  assert.equal(d.querySelector('#themeAuto').getAttribute('aria-pressed'),'true');
  setNow('2026-10-16T07:00:00');app.updateLiveClockAndCountdown();
  assert.equal(d.documentElement.dataset.theme,'light');
  setNow('2026-10-16T19:00:00');app.updateLiveClockAndCountdown();
  assert.equal(d.documentElement.dataset.theme,'dark');
  d.querySelector('#themeToggle').click();
  assert.equal(d.documentElement.dataset.theme,'light');
  assert.equal(w.localStorage.getItem('qingzheng-theme-mode'),'manual');
  setNow('2026-10-16T20:00:00');app.updateLiveClockAndCountdown();
  assert.equal(d.documentElement.dataset.theme,'light');
  app.enableAutomaticTheme();
  assert.equal(d.documentElement.dataset.theme,'dark');
  assert.equal(w.localStorage.getItem('qingzheng-theme-mode'),'auto');
});
test('saved automatic preference takes precedence over an old manual theme value',t => {
  const {d, app}=boot(t,{now:'2026-10-16T22:00:00',storage:{'qingzheng-theme':'light','qingzheng-theme-mode':'auto'}});
  assert.equal(d.documentElement.dataset.theme,'dark');
  const old=boot(t,{storage:{'qingzheng-theme':'dark'}});
  assert.equal(old.d.documentElement.dataset.theme,'dark');
  assert.equal(old.d.querySelector('#themeAuto').getAttribute('aria-pressed'),'false');
});
test('the automatic theme button toggles off without changing the current theme and stays off after reload',t => {
  for (const [now,later,theme] of [
    ['2026-10-16T06:59:59','2026-10-16T07:00:00','dark'],
    ['2026-10-16T18:59:59','2026-10-16T19:00:00','light']
  ]) {
    const {d,w,app,setNow} = boot(t,{now});
    const button = d.querySelector('#themeAuto');
    assert.equal(button.getAttribute('aria-label'),'关闭昼夜自动主题');
    button.click();
    assert.equal(d.documentElement.dataset.theme,theme);
    assert.equal(button.getAttribute('aria-pressed'),'false');
    assert.equal(button.getAttribute('aria-label'),'启用昼夜自动主题');
    assert.match(button.title,/已关闭/);
    assert.match(d.querySelector('#toast').textContent,/已关闭自动主题，保持/);
    assert.equal(w.localStorage.getItem('qingzheng-theme-mode'),'manual');
    assert.equal(w.localStorage.getItem('qingzheng-theme'),theme);
    setNow(later); app.updateLiveClockAndCountdown();
    assert.equal(d.documentElement.dataset.theme,theme,'The time boundary no longer switches themes');
    const reloaded = boot(t,{now:later,storage:{'qingzheng-theme':theme,'qingzheng-theme-mode':w.localStorage.getItem('qingzheng-theme-mode')}});
    assert.equal(reloaded.d.documentElement.dataset.theme,theme);
    assert.equal(reloaded.d.querySelector('#themeAuto').getAttribute('aria-pressed'),'false');
    button.click();
    assert.equal(button.getAttribute('aria-pressed'),'true');
    assert.equal(button.getAttribute('aria-label'),'关闭昼夜自动主题');
    assert.match(button.title,/已开启/);
    assert.equal(w.localStorage.getItem('qingzheng-theme-mode'),'auto');
    assert.notEqual(d.documentElement.dataset.theme,theme,'Re-enabling immediately follows the actual time');
  }
});
test('automatic theme toggling respects manual theme changes and unavailable storage',t => {
  for (const blocked of [false,true]) {
    const {d,app,setNow} = boot(t,{now:'2026-10-16T12:00:00',blocked});
    const button = d.querySelector('#themeAuto');
    button.click(); button.click();
    assert.equal(button.getAttribute('aria-pressed'),'true');
    d.querySelector('#themeToggle').click();
    assert.equal(d.documentElement.dataset.theme,'dark');
    assert.equal(button.getAttribute('aria-pressed'),'false');
    assert.equal(button.getAttribute('aria-label'),'启用昼夜自动主题');
    button.click(); assert.equal(d.documentElement.dataset.theme,'light');
    button.click(); assert.equal(d.documentElement.dataset.theme,'light');
    setNow('2026-10-16T20:00:00'); app.updateLiveClockAndCountdown();
    assert.equal(d.documentElement.dataset.theme,'light');
    if (blocked) assert.equal(d.querySelector('#storageNotice').hidden,false);
  }
});
test('reduced motion keeps navigation, dialogs, details and year expansion fully usable',t => {
  const {w, d, app}=boot(t,{reducedMotion:true});
  app.views.nextMonth();app.views.prevMonth();app.views.showDetail(new w.Date());app.views.closeDetail();
  app.settings.openSettings();app.settings.closeSettings();app.views.openYearOverview();
  assert.equal(d.querySelector('#yearOverview').open,true);
  assert.equal(d.querySelectorAll('#daysGrid button').length,31);
});
test('festival decorations appear only on the real festival date and open an identified detail',t => {
  const cases = [
    ['2026-01-01','元旦'],['2026-02-16','除夕'],['2026-02-17','春节'],
    ['2026-03-03','元宵节'],['2026-04-05','清明'],['2026-05-01','劳动节'],
    ['2026-06-19','端午节'],['2026-09-25','中秋节'],['2026-10-01','国庆节'],['2026-10-18','重阳节']
  ];
  const {w, d, app} = boot(t);
  for (const [date,name] of cases) {
    const [year,month,day] = date.split('-').map(Number);
    app.views.navigateMonth(year,month-1);
    const cell = d.querySelector(`[data-date="${date}"].day-cell`);
    assert.equal(cell.querySelector('.holiday-badge').textContent,name,date);
    assert.ok(cell.getAttribute('aria-label').includes(name),date);
    for (const use of cell.querySelectorAll('.festival-art use')) assert.ok(d.querySelector(use.getAttribute('href')),date);
    cell.click();
    assert.equal(d.querySelector('#detailFestival').hidden,false,date);
    assert.equal(d.querySelector('#detailFestival strong').textContent,name,date);
    const next = new w.Date(year,month-1,day+1);
    app.views.showDetail(next);
    if (!app.festivals.getHoliday(next)) {
      assert.equal(d.querySelector('#detailFestival').hidden,true,date);
      assert.equal(d.querySelector('#detailFestival').childElementCount,0,date);
    }
    app.views.closeDetail();
    for (const ordinary of d.querySelectorAll('.day-cell:not(.is-festival)')) {
      assert.equal(ordinary.querySelector('.festival-art,.holiday-badge'),null);
    }
  }
});
test('today festival follows the civil date while an overnight shift keeps its original cycle day',t => {
  const {w, d, setNow, app} = boot(t,{now:'2026-09-24T23:59:59',storage:{'qingzheng-cycle':'["夜班"]'}});
  assert.equal(d.querySelector('#todayFestival').hidden,true);
  setNow('2026-09-25T00:00:00'); app.updateLiveClockAndCountdown();
  assert.equal(d.querySelector('#todayFestival').hidden,false);
  assert.equal(d.querySelector('#todayFestival strong').textContent,'中秋节');
  assert.ok(d.querySelector('#todayShiftTime').textContent.includes('昨日班次'));
  assert.equal(d.querySelector('.day-cell.today').dataset.date,'2026-09-25');
  setNow('2026-09-26T00:00:00'); app.updateLiveClockAndCountdown();
  assert.equal(d.querySelector('#todayFestival').hidden,true);
  assert.equal(d.querySelector('#todayFestival').childElementCount,0);
  assert.equal(d.querySelector('.today-card').classList.contains('has-festival'),false);
});
test('coinciding festivals preserve both names and illustrations when switching themes',t => {
  const {w, d, app} = boot(t,{now:'2020-10-01T12:00:00'});
  const cell=d.querySelector('.day-cell.today');
  assert.equal(cell.querySelector('.holiday-badge').textContent,'国庆节·中秋节');
  assert.equal(cell.querySelectorAll('.festival-art svg').length,2);
  cell.click();
  assert.equal(d.querySelector('#detailFestival strong').textContent,'国庆节·中秋节');
  const before = cell.querySelector('.festival-art').innerHTML;
  const palette = cell.dataset.festivalPalette;
  d.querySelector('#themeToggle').click();
  assert.equal(cell.querySelector('.festival-art').innerHTML,before);
  assert.equal(cell.dataset.festivalPalette,palette);
  assert.equal(d.querySelector('#todayFestival').querySelectorAll('.festival-art svg').length,2);
});
test('the coming week identifies Chongyang and all other supported festivals without decorating ordinary days',t => {
  const cases = [
    ['2026-10-16','2026-10-18','重阳节'],['2025-12-30','2026-01-01','元旦'],
    ['2026-02-14','2026-02-16','除夕'],['2026-02-15','2026-02-17','春节'],
    ['2026-03-01','2026-03-03','元宵节'],['2026-04-03','2026-04-05','清明'],
    ['2026-04-29','2026-05-01','劳动节'],['2026-06-17','2026-06-19','端午节'],
    ['2026-09-23','2026-09-25','中秋节'],['2026-09-29','2026-10-01','国庆节']
  ];
  for (const [now,date,festival] of cases) {
    const {d, w, app}=boot(t,{now:`${now}T12:00:00`});
    const row=d.querySelector(`#upcomingList [data-date="${date}"]`);
    assert.equal(d.querySelectorAll('#upcomingList button').length,7);
    assert.equal(row.querySelector('.upcoming-festival').textContent,festival,date);
    assert.ok(row.getAttribute('aria-label').includes(festival),date);
    assert.ok(row.querySelector('.upcoming-shift').textContent,date);
    for (const use of row.querySelectorAll('.festival-art use')) assert.ok(d.querySelector(use.getAttribute('href')),date);
    for (const other of d.querySelectorAll('#upcomingList button')) {
      const ordinary=Core.parseDate(other.dataset.date);
      if (!app.festivals.getHoliday(ordinary)) assert.equal(other.querySelector('.upcoming-festival'),null,other.dataset.date);
    }
    row.click();
    assert.equal(d.querySelector('#detailFestival strong').textContent,festival,date);
    assert.equal(d.querySelector('#daysGrid .selected').dataset.date,date);
  }
});
test('the coming week updates its festival rows at midnight and across the year boundary',t => {
  const {w, d, setNow, app}=boot(t,{now:'2026-12-31T23:59:59'});
  assert.equal(d.querySelector('#upcomingList .upcoming-festival').textContent,'元旦');
  setNow('2027-01-01T00:00:00'); app.updateLiveClockAndCountdown();
  const today=d.querySelector('#upcomingList .is-today');
  assert.equal(today.dataset.date,'2027-01-01');
  assert.equal(today.querySelector('.upcoming-festival').textContent,'元旦');
  today.click();
  assert.equal(d.querySelector('.day-cell.selected').dataset.date,'2027-01-01');
  setNow('2027-01-02T00:00:00'); app.updateLiveClockAndCountdown();
  assert.equal(d.querySelector('#upcomingList .upcoming-festival'),null);
});
test('the coming week keeps both coinciding festivals and the shared illustration palette in either theme',t => {
  const {w, d, app}=boot(t,{now:'2020-09-29T12:00:00'});
  const row=d.querySelector('#upcomingList [data-date="2020-10-01"]');
  assert.equal(row.querySelector('.upcoming-festival').textContent,'国庆节·中秋节');
  assert.equal(row.querySelectorAll('.festival-art svg').length,2);
  const before=row.querySelector('.upcoming-festival').innerHTML,palette=row.dataset.festivalPalette;
  d.querySelector('#themeToggle').click();
  assert.equal(row.querySelector('.upcoming-festival').innerHTML,before);
  assert.equal(row.dataset.festivalPalette,palette);
  row.click();
  assert.equal(d.querySelector('#detailFestival strong').textContent,'国庆节·中秋节');
  app.views.closeDetail();
  assert.equal(d.querySelector('#upcomingPanel').hidden,false);
});
test('companions use the matching theme artwork and reset their copy on theme changes',t => {
  const {w, d, setNow, app}=boot(t,{now:'2026-10-16T06:59:59'});
  assert.equal(d.querySelector('#companion').hidden,false);
  assert.equal(d.querySelector('#companionDay').hidden,true);
  assert.equal(d.querySelector('#companionNight').hidden,false);
  assert.equal(d.querySelector('#companionName').textContent,'大肥鱼');
  d.querySelector('#companionCharacter').click();
  assert.equal(d.querySelector('#companion').dataset.message,'quip');
  setNow('2026-10-16T07:00:00');app.updateLiveClockAndCountdown();
  assert.equal(d.querySelector('#companionDay').hidden,false);
  assert.equal(d.querySelector('#companionNight').hidden,true);
  assert.equal(d.querySelector('#companionName').textContent,'大肥龙');
  assert.equal(d.querySelector('#companion').dataset.message,'status');
  assert.ok(d.querySelector('#companionCharacter').getAttribute('aria-label').includes('大肥龙'));
  d.querySelector('#themeToggle').click();
  assert.equal(d.querySelector('#companionName').textContent,'大肥鱼');
});
test('the companion reading window is keyboard accessible and new messages start at the top',t => {
  const {d,app} = boot(t);
  const message = d.querySelector('#companionMessage');
  assert.equal(message.getAttribute('role'),'region');
  assert.equal(message.tabIndex,0);
  assert.ok(message.getAttribute('aria-label').includes('滚动'));
  message.scrollTop = 80;
  d.querySelector('#companionCharacter').click();
  assert.equal(message.scrollTop,0);
  message.scrollTop = 60;
  app.setTheme('dark');
  assert.equal(message.scrollTop,0);
});
test('quiet companion countdown updates do not reset the reading position',t => {
  const {d,app,setNow} = boot(t,{now:'2026-10-19T12:00:00'});
  const message = d.querySelector('#companionMessage');
  message.scrollTop = 40;
  setNow('2026-10-19T12:02:00');
  app.updateLiveClockAndCountdown();
  assert.equal(message.scrollTop,40);
  assert.equal(d.querySelector('#companionAnnouncement').textContent,'');
});
test('companion status follows the exact start and end of a shift, highlighting only the time',t => {
  const {w, d, setNow, app}=boot(t,{now:'2026-04-18T07:59:30'});
  const message=d.querySelector('#companionMessage');
  assert.ok(message.textContent.includes('距离下次上班'));
  assert.equal(message.querySelector('strong').textContent,'不到1分钟');
  setNow('2026-04-18T08:00:00');app.updateLiveClockAndCountdown();
  assert.ok(message.textContent.includes('离下班还有'));
  assert.equal(message.querySelector('.companion-time').textContent,'12小时');
  setNow('2026-04-18T19:59:59');app.updateLiveClockAndCountdown();
  assert.equal(message.querySelector('strong').textContent,'不到1分钟');
  setNow('2026-04-18T20:00:00');app.updateLiveClockAndCountdown();
  assert.ok(message.textContent.includes('距离下次上班'));
  assert.equal(message.querySelector('strong').textContent,'1天');
  assert.equal(d.querySelector('#companionAnnouncement').textContent,'');
});
test('overnight companion copy remains on the previous work shift until its real end',t => {
  const {w, d, setNow, app}=boot(t,{now:'2026-04-19T23:59:59',storage:{'qingzheng-theme':'light'}});
  const message=d.querySelector('#companionMessage');
  assert.ok(message.textContent.includes('离下班还有'));
  setNow('2026-04-20T00:00:00');app.updateLiveClockAndCountdown();
  assert.ok(message.textContent.includes('离下班还有'));
  assert.equal(message.querySelector('strong').textContent,'8小时');
  setNow('2026-04-20T08:00:00');app.updateLiveClockAndCountdown();
  assert.ok(message.textContent.includes('距离下次上班'));
  assert.equal(message.querySelector('strong').textContent,'1天');
});
test('clicks alternate a shuffled quip bag with one schedule fact without immediate repetition',t => {
  const {d, app}=boot(t);
  const button=d.querySelector('#companionCharacter'),message=d.querySelector('#companionMessage');
  const quips=[];let previous=message.textContent;
  for (let i=0;i<26;i++) {
    button.click();
    assert.notEqual(message.textContent,previous);
    assert.equal(d.querySelector('#companion').dataset.message === 'quip',i%2===0);
    assert.ok(message.querySelectorAll('strong').length<=1);
    assert.ok(d.querySelector('#companionAnnouncement').textContent.includes(message.textContent));
    if (i%2===0) quips.push(message.textContent);
    previous=message.textContent;
  }
  assert.equal(new Set(quips.slice(0,6)).size,6);
  assert.equal(new Set(quips.slice(6,12)).size,6);
  assert.notEqual(quips[5],quips[6]);
});
test('a chosen live fact refreshes quietly, while quips do not rotate on the clock',t => {
  const {w, d, setNow, app}=boot(t,{now:'2026-04-18T09:00:00'});
  const message=d.querySelector('#companionMessage');
  const original=message.firstChild;
  setNow('2026-04-18T09:00:01');app.updateLiveClockAndCountdown();
  assert.notEqual(message.firstChild,original);
  const sameMinute=message.firstChild;
  setNow('2026-04-18T09:00:02');app.updateLiveClockAndCountdown();
  assert.equal(message.firstChild,sameMinute);
  d.querySelector('#companionCharacter').click();
  const quip=message.textContent,announcement=d.querySelector('#companionAnnouncement').textContent;
  setNow('2026-04-18T10:05:00');app.updateLiveClockAndCountdown();
  assert.equal(message.textContent,quip);
  assert.equal(d.querySelector('#companionAnnouncement').textContent,announcement);
});
test('collapsing hides only the bubble; character activation restores it and refreshes copy',t => {
  const {d, app}=boot(t);
  const toggle=d.querySelector('#companionToggle'),bubble=d.querySelector('#companionBubble');
  toggle.click();
  assert.equal(bubble.hidden,true);
  assert.equal(toggle.getAttribute('aria-expanded'),'false');
  assert.equal(d.querySelector('#companion').classList.contains('is-collapsed'),true);
  const button=d.querySelector('#companionCharacter');button.focus();button.click();
  assert.equal(bubble.hidden,false);
  assert.equal(toggle.getAttribute('aria-expanded'),'true');
  assert.equal(d.querySelector('#companion').dataset.message,'quip');
  assert.equal(d.activeElement,button);
});
test('companion facts handle all-rest, no-rest and long cycles using the actual saved config',t => {
  const {w, d, app}=boot(t,{now:'2026-10-16T12:00:00'});
  app.settings.commitConfig({anchor:'2026-10-16',cycle:['休息'],shiftDefs:{}});
  assert.ok(d.querySelector('#companionMessage').textContent.includes('没有工作班'));
  assert.equal(d.querySelector('#companionMessage strong'),null);
  app.settings.commitConfig({anchor:'2026-10-16',cycle:[...Array(40).fill('休息'),'白班'],shiftDefs:{}});
  assert.equal(d.querySelector('#companionMessage strong').textContent,'39天21小时');
  app.settings.commitConfig({anchor:'2026-10-16',cycle:['白班'],shiftDefs:{}});
  assert.equal(app.companion.companionFacts(new w.Date(),app.schedule.getEffectiveShiftInfo(),app.schedule.getNextWorkDelay(new w.Date())).some(fact=>fact.id === "rest"),false);
  assert.ok(d.querySelector('#companionMessage').textContent.includes('下班'));
});
test('companion tomorrow facts use real civil dates at midnight and treat custom names as text',t => {
  const name='<img/src=x/onerror=alert(1)>';
  const {w, d, setNow, app}=boot(t,{now:'2026-10-16T23:59:59',storage:{'qingzheng-theme':'light'}});
  app.settings.commitConfig({anchor:'2026-10-16',cycle:['休息',name,'白班'],shiftDefs:{}});
  d.querySelector('#companionCharacter').click();d.querySelector('#companionCharacter').click();
  const message=d.querySelector('#companionMessage');
  assert.ok(message.textContent.includes(name));
  assert.equal(message.querySelector('strong').textContent,'09:00');
  assert.equal(message.querySelector('img'),null);
  setNow('2026-10-17T00:00:00');app.updateLiveClockAndCountdown();
  assert.ok(message.textContent.includes('白班'));
  assert.equal(message.textContent.includes(name),false);
  assert.equal(d.querySelectorAll('img').length,initialImageCount);
});
test('the last overnight shift is not mistaken for a full rest day by the companion',t => {
  const {w, d, setNow, app}=boot(t,{now:'2026-04-20T07:00:00',storage:{'qingzheng-theme':'light'}});
  for (let i=0;i<4;i++) d.querySelector('#companionCharacter').click();
  assert.equal(d.querySelector('#companion').dataset.message,'rest');
  assert.ok(d.querySelector('#companionMessage').textContent.includes('要等这班结束'));
  setNow('2026-04-20T08:00:00');app.updateLiveClockAndCountdown();
  assert.ok(d.querySelector('#companionMessage').textContent.includes('今天的排班是休息'));
  assert.equal(d.querySelector('#companionMessage strong'),null);
});
test('reduced-motion users can refresh and collapse companions without scripted animation',t => {
  const {d, app}=boot(t,{reducedMotion:true});
  for (let i=0;i<4;i++) d.querySelector('#companionCharacter').click();
  d.querySelector('#companionToggle').click();
  d.querySelector('#companionCharacter').click();
  assert.equal(d.querySelector('#companionBubble').hidden,false);
  assert.ok(d.querySelector('#companionMessage').textContent);
});
test('character taps produce a finite bounce, sparkles and copy transition',t => {
  const {w, d, app}=boot(t,{reducedMotion:false});
  const animations=[];
  w.Element.prototype.animate=function(frames,options) {
    animations.push({element:this,frames,options});
    return {finished:Promise.resolve(),cancel(){}};
  };
  d.querySelector('#companionCharacter').click();
  assert.equal(animations.filter(item=>item.element.id==='companionCharacter').length,1);
  assert.equal(animations.filter(item=>item.element.classList.contains('companion-spark')).length,2);
  assert.equal(animations.filter(item=>item.element.id==='companionMessage').length,1);
  assert.ok(animations.every(item=>item.options.duration<=520 && !item.options.iterations));
});

test('shift naming validates inside the static settings page without native dialogs',t => {
  const {app,d,w}=boot(t); app.settings.openSettings();
  const input=d.querySelector('#shiftName');
  for (const name of ['', '有 空格', '长'.repeat(41), '白班']) {
    input.value=name;d.querySelector('[data-action="addShiftDef"]').click();
    assert.equal(d.querySelector('#settingsError').hidden,false,name);
    assert.equal(d.activeElement,input);
  }
  d.querySelector('[data-shift="白班"] [data-field="startTime"]').value='06:45';
  input.value='  早班  ';
  input.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true}));
  assert.ok(d.querySelector('[data-shift="早班"]'));
  assert.equal(d.querySelector('[data-shift="白班"] [data-field="startTime"]').value,'06:45');
  assert.equal(input.value,'');
  assert.equal(d.querySelector('#settingsModal').hidden,false);
  assert.equal(w.localStorage.getItem('qingzheng-config'),null);
});
test('composing a Chinese shift name cannot add an unfinished definition',t => {
  const {app,d,w}=boot(t);app.settings.openSettings();
  const input=d.querySelector('#shiftName');input.value='新班';
  for (const options of [{isComposing:true},{keyCode:229}]) {
    const event=new w.KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true,...options});
    input.dispatchEvent(event);assert.equal(event.defaultPrevented,true);
    assert.equal(d.querySelector('[data-shift="新班"]'),null);
  }
});
test('nested shift deletion cancellation preserves all draft edits and focus',t => {
  for (const method of ['cancel','escape','backdrop']) {
    const {app,d,w}=boot(t);app.settings.openSettings();
    const input=d.querySelector('[data-shift="白班"] [data-field="startTime"]');input.value='07:10';
    const opener=d.querySelector('[aria-label="删除班次「夜班」"]');opener.focus();opener.click();
    assert.equal(app.ui.activeDialog,'confirmModal');assert.equal(app.liveTimer,null);
    assert.equal(d.querySelector('#settingsModal').hidden,true);
    assert.equal(d.querySelector('#settingsModal').inert,true);
    assert.equal(d.querySelector('#confirmModal').classList.contains('input-view'),true);
    if (method==='cancel') d.querySelector('#confirmCancel').click();
    else if (method==='escape') d.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true}));
    else d.querySelector('#confirmModal').click();
    assert.equal(app.ui.activeDialog,'settingsModal');assert.equal(d.activeElement,opener);
    assert.equal(app.liveTimer,null);assert.equal(input.value,'07:10');
    assert.ok(d.querySelector('[data-shift="夜班"]'));
    assert.ok(d.querySelector('#cyclePattern').value.includes('夜班'));
  }
});
test('confirming shift deletion changes only the draft until settings are saved',t => {
  const {app,d,w}=boot(t);app.settings.openSettings();
  d.querySelector('[data-shift="白班"] [data-field="startTime"]').value='07:20';
  app.settings.removeShiftDef('夜班');d.querySelector('#confirmAccept').click();
  assert.equal(app.ui.activeDialog,'settingsModal');assert.equal(app.liveTimer,null);
  assert.equal(d.querySelector('[data-shift="夜班"]'),null);
  assert.equal(d.querySelector('#cyclePattern').value.includes('夜班'),false);
  assert.ok(app.schedule.cycle.includes('夜班'));
  assert.equal(w.localStorage.getItem('qingzheng-config'),null);
  app.settings.saveSettings();
  assert.equal(app.schedule.cycle.includes('夜班'),false);
  assert.equal(app.schedule.shiftDefs['白班'].startTime,'07:20');
  assert.equal(app.ui.activeDialog,null);assert.notEqual(app.liveTimer,null);
});
test('confirmation traps focus and Escape does not also dismiss its parent settings page',t => {
  const {app,d,w}=boot(t);app.settings.openSettings();app.settings.removeShiftDef('夜班');
  const first=d.querySelector('#confirmCancel'),last=d.querySelector('#confirmAccept');last.focus();
  last.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Tab',bubbles:true,cancelable:true}));
  assert.equal(d.activeElement,first);
  first.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Tab',shiftKey:true,bubbles:true,cancelable:true}));
  assert.equal(d.activeElement,last);
  d.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true}));
  assert.equal(app.ui.activeDialog,'settingsModal');
  assert.equal(d.documentElement.classList.contains('input-view-open'),true);
});
test('nested confirmation restores the settings scroll separately from the calendar scroll',t => {
  const {app,d,w}=boot(t);const scrolls=[];
  w.scrollY=640;w.scrollTo=(x,y)=>{scrolls.push([x,y]);w.scrollX=x;w.scrollY=y;};
  app.settings.openSettings();w.scrollY=480;app.settings.removeShiftDef('夜班');
  d.querySelector('#confirmCancel').click();assert.equal(w.scrollY,480);
  app.settings.closeSettings();assert.equal(w.scrollY,640);
  assert.deepEqual(scrolls,[[0,0],[0,0],[0,480],[0,640]]);
});
test('preset deletion requires an in-page confirmation and keeps the current schedule',t => {
  const preset={id:'p',name:'待删除',anchor:'2026-04-18',cycle:['休息'],shiftDefs:{}};
  const {app,d,w}=boot(t,{storage:{'qingzheng-presets':JSON.stringify([preset])}});
  const before=JSON.stringify(app.schedule.captureCurrentConfig()),stored=w.localStorage.getItem('qingzheng-presets');
  const opener=d.querySelector('.preset-delete');opener.focus();opener.click();
  d.querySelector('#confirmCancel').click();
  assert.equal(w.localStorage.getItem('qingzheng-presets'),stored);
  assert.equal(d.activeElement,opener);
  opener.click();d.querySelector('#confirmAccept').click();d.querySelector('#confirmAccept').click();
  assert.equal(app.settings.presets.length,0);assert.equal(JSON.stringify(app.schedule.captureCurrentConfig()),before);
  assert.equal(w.localStorage.getItem('qingzheng-presets'),'[]');
  assert.equal(d.activeElement,d.querySelector('[data-action="saveAsPreset"]'));
});
test('steady seconds mutate only the live fields, not the today-card structure or ARIA',t => {
  const {app,d,w,setNow}=boot(t,{now:'2026-04-18T09:00:10'});
  const ids=['todayDate','todayShift','todayShiftTime','todayCycle','cycleLength','progressBar','cycleProgress','restCountdown'];
  const observer=new w.MutationObserver(()=>{});
  for (const id of ids) observer.observe(d.getElementById(id),{childList:true,attributes:true,characterData:true,subtree:true});
  const clock=d.querySelector('#liveClock').textContent;
  setNow('2026-04-18T09:00:11');app.updateLiveClockAndCountdown();
  assert.equal(observer.takeRecords().length,0);
  assert.notEqual(d.querySelector('#liveClock').textContent,clock);
  observer.disconnect();
});
test('today-card invalidation includes changes to other shifts that affect the next rest day',t => {
  const {app,d}=boot(t,{now:'2026-04-18T12:00:00'});
  const config=app.schedule.captureCurrentConfig();config.shiftDefs['夜班'].isWork=false;
  app.settings.commitConfig(config);
  assert.equal(d.querySelector('#restCountdown').textContent,'1 天后');
});
test('module instances do not expose application functions or state on window',t => {
  const first=boot(t),second=boot(t);
  assert.ok(Object.isFrozen(first.app.schedule.cycle));
  assert.ok(Object.isFrozen(first.app.schedule.shiftDefs));
  assert.ok(Object.isFrozen(first.app.schedule.shiftDefs['白班']));
  for (const name of ['openSettings','saveAsPreset','ScheduleCore','CalendarData','CYCLE','activeDialog']) {
    assert.equal(first.w[name],undefined,name);
  }
  first.app.settings.commitConfig({anchor:'2026-04-18',cycle:['休息'],shiftDefs:{}});
  first.app.settings.openSettings();
  assert.equal(second.app.schedule.cycle.length,8);
  assert.equal(second.app.ui.activeDialog,null);
});
test('only active-theme image sources are assigned until the visitor changes theme',t => {
  const {app,d}=boot(t,{storage:{'qingzheng-theme':'light'}});
  for (const image of d.querySelectorAll('[data-image-theme="light"]')) assert.ok(image.hasAttribute('src'));
  for (const image of d.querySelectorAll('[data-image-theme="dark"]')) assert.equal(image.hasAttribute('src'),false);
  app.setTheme('dark');
  for (const image of d.querySelectorAll('[data-image-theme="dark"]')) assert.ok(image.hasAttribute('src'));
});
test('an optional Android bridge failure cannot prevent a web configuration save',t => {
  const {app,w,d}=boot(t);w.Android={saveSettings(){throw new Error('Unavailable shell');}};
  assert.equal(app.settings.commitConfig({anchor:'2026-04-18',cycle:['休息'],shiftDefs:{}}),true);
  assert.deepEqual(JSON.parse(w.localStorage.getItem('qingzheng-config')).cycle,['休息']);
  assert.equal(d.querySelector('#toast').textContent,'网页设置已更新，原生应用同步暂时失败');
});
test('cycle quick entry, undo and the dated preview only change the settings draft',t => {
  const {app,d,w} = boot(t);
  const original = app.schedule.captureCurrentConfig();
  app.settings.openSettings();
  assert.equal(d.querySelectorAll('#cyclePreviewDays li').length,8);
  assert.match(d.querySelector('#cyclePreviewDays li').textContent,/4\/18/);
  assert.match(d.querySelector('#cycleSummary').textContent,/工作 4 天 · 休息 4 天/);
  d.querySelector('[aria-label="向周期末尾添加一天中班"]').click();
  assert.equal(d.querySelectorAll('#cyclePreviewDays li').length,9);
  d.querySelector('#cycleRemoveLast').click();
  assert.equal(d.querySelectorAll('#cyclePreviewDays li').length,8);
  assert.equal(w.localStorage.getItem('qingzheng-config'),null);
  assert.deepEqual(app.schedule.captureCurrentConfig(),original);
  app.settings.closeSettings();
  assert.deepEqual(app.schedule.captureCurrentConfig(),original);
});
test('cycle preview handles empty input, unknown names, work flags and bounded long cycles',t => {
  const {app,d,w} = boot(t);
  app.settings.openSettings();
  const input = d.querySelector('#cyclePattern');
  input.value = ''; input.dispatchEvent(new w.Event('input'));
  assert.equal(d.querySelector('#cycleRemoveLast').disabled,true);
  assert.equal(d.querySelectorAll('#cyclePreviewDays li').length,0);
  input.value = '休息 新班次'; input.dispatchEvent(new w.Event('input'));
  assert.match(d.querySelector('#cyclePreviewHint').textContent,/新班次/);
  assert.equal(d.querySelectorAll('.cycle-preview-day.is-undefined').length,1);
  input.value = Array(3660).fill('白班').join(' '); input.dispatchEvent(new w.Event('input'));
  assert.equal(d.querySelectorAll('#cyclePreviewDays li').length,16);
  assert.match(d.querySelector('#cycleSummary').textContent,/3660 天/);
  const work = d.querySelector('[data-shift="白班"] [data-field="isWork"]');
  work.checked = false; work.dispatchEvent(new w.Event('change',{bubbles:true}));
  assert.match(d.querySelector('#cycleSummary').textContent,/休息 3660 天/);
  const style = d.querySelector('[data-shift="白班"] select');
  style.value = 'evening'; style.dispatchEvent(new w.Event('change',{bubbles:true}));
  assert.equal(d.querySelector('#cycleQuickAdd [data-cycle-shift="白班"]').classList.contains('evening'),true);
  app.settings.appendCycleShift('白班');
  assert.equal(input.value.split(' ').length,3660);
});
test('detail navigation crosses months and years without changing the schedule or today',t => {
  const {app,d,w} = boot(t);
  app.views.navigateMonth(2026,11);
  d.querySelector('#daysGrid [data-date="2026-12-31"]').click();
  assert.equal(d.activeElement.id,'detailHeading');
  const next = d.querySelector('#detailNext'); next.focus(); next.click();
  assert.equal(app.views.viewYear,2027); assert.equal(app.views.viewMonth,0);
  assert.equal(d.querySelector('#daysGrid .selected').dataset.date,'2027-01-01');
  assert.equal(d.activeElement,next,'Navigation buttons retain focus');
  assert.match(d.querySelector('#detailAnnouncement').textContent,/2027年1月1日/);
  assert.match(d.querySelector('#todayDate').textContent,/10月16日/);
  d.querySelector('#detailPrev').click();
  assert.equal(app.views.viewYear,2026); assert.equal(app.views.viewMonth,11);
  app.views.navigateMonth(app.views.minYear(),0); app.views.showDetail(new w.Date(2025,0,1));
  assert.equal(d.querySelector('#detailPrev').disabled,true);
  app.views.moveDetail(-1); assert.match(d.querySelector('#detailHeading').textContent,/1月1日/);
  app.views.navigateMonth(app.views.maxYear(),11); app.views.showDetail(new w.Date(2027,11,31));
  assert.equal(d.querySelector('#detailNext').disabled,true);
});
test('closing details restores the original calendar or upcoming date and avoids misleading rest copy',t => {
  const {app,d} = boot(t);
  const cell = d.querySelector('#daysGrid [data-date="2026-10-01"]');
  cell.click(); d.querySelector('[data-action="closeDetail"]').click();
  assert.equal(d.activeElement,cell);
  const upcoming = d.querySelector('#upcomingList [data-date="2026-10-18"]');
  upcoming.click(); assert.match(d.querySelector('#detailExtra').textContent,/当天休息/);
  d.querySelector('#detailNext').click();
  d.querySelector('[data-action="closeDetail"]').click();
  assert.equal(d.activeElement.dataset.date,'2026-10-18');
  assert.equal(d.activeElement.closest('#upcomingList')?.id,'upcomingList');
});
test('keyboard scrolling inside the companion does not switch calendar months',t => {
  const {d,w,app} = boot(t);
  const message = d.querySelector('#companionMessage'); message.focus();
  for (const key of ['ArrowLeft','ArrowRight','t','End']) message.dispatchEvent(new w.KeyboardEvent('keydown',{key,bubbles:true,cancelable:true}));
  assert.equal(app.views.viewMonth,9);
  assert.equal(d.querySelector('#detailPanel').classList.contains('show'),false);
});
test('closing a detail after crossing a month keeps the current month and returns to its inspected day',t => {
  const {app,d} = boot(t);
  d.querySelector('#daysGrid [data-date="2026-10-31"]').click();
  d.querySelector('#detailNext').click();
  d.querySelector('.detail-back').click();
  assert.equal(app.views.viewMonth,10);
  assert.equal(d.activeElement.dataset.date,'2026-11-01');
});
test('backup dialog pauses live updates, traps focus and restores its opening control',t => {
  const {app,d,w} = boot(t);
  const opener = d.querySelector('[data-action="openDataTools"]'); opener.focus(); opener.click();
  assert.equal(app.ui.activeDialog,'dataModal');
  assert.equal(app.liveTimer,null);
  assert.equal(d.querySelector('#dataModal').classList.contains('input-view'),true);
  assert.equal(d.querySelector('#backupTextInput').maxLength,MAX_BACKUP_TEXT_LENGTH);
  d.activeElement.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Tab',bubbles:true,cancelable:true}));
  assert.equal(d.activeElement.getAttribute('aria-label'),'关闭备份与恢复');
  d.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true}));
  assert.equal(app.ui.activeDialog,null); assert.equal(d.activeElement,opener);
  assert.notEqual(app.liveTimer,null);
});
test('restoring a backup keeps the old schedule, presets and conflicting names with fresh IDs',t => {
  const {app,w} = boot(t);
  const original = app.schedule.captureCurrentConfig();
  app.settings.saveAsPreset(); w.document.querySelector('#presetName').value = '日常'; app.settings.submitPreset();
  const incoming = Core.normalizeConfig({anchor:'2026-01-01',cycle:['休息'],shiftDefs:{}});
  const backup = parseBackup(JSON.stringify(createBackup(incoming,[{...incoming,name:'日常'}],'now')));
  assert.equal(app.settings.restoreBackup(backup),true);
  assert.deepEqual(app.schedule.cycle,['休息']);
  assert.deepEqual(app.settings.presets.map(preset => preset.name),['日常','日常（1）']);
  assert.equal(new Set(app.settings.presets.map(preset => preset.id)).size,2);
  app.settings.applyPreset(app.settings.presets[0].id);
  assert.deepEqual(app.schedule.captureCurrentConfig(),original);
  app.settings.restoreBackup(backup);
  assert.equal(app.settings.presets.length,2,'Repeated imports also reuse a previously renamed conflicting preset');
  app.settings.applyPreset(app.settings.presets[0].id);
  const restored = boot(t,{storage:{'qingzheng-config':w.localStorage.getItem('qingzheng-config'),'qingzheng-presets':w.localStorage.getItem('qingzheng-presets')}});
  assert.deepEqual(restored.app.schedule.captureCurrentConfig(),original);
  assert.equal(restored.app.settings.presets.length,2);
});
test('restoring a backup preserves an unsaved current schedule and does not duplicate identical named presets',t => {
  const {app} = boot(t);
  const current = app.schedule.captureCurrentConfig();
  const incoming = Core.normalizeConfig({anchor:'2026-01-01',cycle:['休息'],shiftDefs:{}});
  const backup = {config:incoming,presets:[{...incoming,name:'休息方案'}]};
  app.settings.restoreBackup(backup);
  assert.equal(app.settings.presets.length,2);
  assert.deepEqual(Core.normalizeConfig(app.settings.presets[0]),current);
  app.settings.restoreBackup(backup);
  assert.equal(app.settings.presets.length,2);
});
test('a failed recovery-preset write never overwrites the previously persisted schedule',t => {
  const {app,w,d} = boot(t);
  app.settings.commitConfig(app.schedule.captureCurrentConfig());
  const old = w.localStorage.getItem('qingzheng-config'), setItem = w.Storage.prototype.setItem;
  w.Storage.prototype.setItem = function(key,value) {
    if (key === 'qingzheng-presets') throw new Error('Quota exceeded');
    return setItem.call(this,key,value);
  };
  const incoming = Core.normalizeConfig({anchor:'2026-01-01',cycle:['休息'],shiftDefs:{}});
  assert.equal(app.settings.restoreBackup({config:incoming,presets:[]}),false);
  assert.deepEqual(app.schedule.cycle,['休息'],'Session remains usable');
  assert.equal(w.localStorage.getItem('qingzheng-config'),old,'Persisted original remains safe');
  assert.equal(d.querySelector('#storageNotice').hidden,false);
});
test('file import previews first, cancellation is harmless and confirmation restores safely',async t => {
  const {app,w,d} = boot(t);
  app.dataTools.openDataTools();
  const original = app.schedule.captureCurrentConfig();
  const incoming = Core.normalizeConfig({anchor:'2026-01-01',cycle:['休息'],shiftDefs:{}});
  const file = new w.File([JSON.stringify(createBackup(incoming,[],'now'))],'backup.json',{type:'application/json'});
  Object.defineProperty(d.querySelector('#backupFile'),'files',{value:[file],configurable:true});
  await app.dataTools.readBackupFile();
  assert.equal(d.querySelector('#importPreview').hidden,false);
  assert.deepEqual(app.schedule.captureCurrentConfig(),original);
  app.dataTools.importBackup(); assert.equal(app.ui.activeDialog,'confirmModal');
  assert.equal(d.querySelector('#confirmAccept').textContent,'导入并应用');
  d.querySelector('#confirmCancel').click(); assert.equal(app.ui.activeDialog,'dataModal');
  assert.deepEqual(app.schedule.captureCurrentConfig(),original);
  app.dataTools.importBackup(); d.querySelector('#confirmAccept').click();
  assert.equal(app.ui.activeDialog,null); assert.deepEqual(app.schedule.cycle,['休息']);
  assert.deepEqual(Core.normalizeConfig(app.settings.presets[0]),original);
});
test('invalid files and a read finishing after closing never change stored data',async t => {
  const {app,w,d} = boot(t);
  app.dataTools.openDataTools();
  const input = d.querySelector('#backupFile');
  Object.defineProperty(input,'files',{value:[new w.File(['bad'],'bad.json')],configurable:true});
  await app.dataTools.readBackupFile();
  assert.equal(d.querySelector('#backupError').hidden,false);
  assert.equal(d.querySelector('#importPreview').hidden,true);
  const readers = [];
  w.FileReader = class { constructor(){readers.push(this);} readAsText(){} };
  const reading = app.dataTools.readBackupFile(); app.dataTools.closeDataTools();
  readers[0].result = JSON.stringify(createBackup(app.schedule.captureCurrentConfig(),[],'now')); readers[0].onload();
  await reading;
  assert.equal(d.querySelector('#importPreview').hidden,true);
  assert.equal(w.localStorage.getItem('qingzheng-config'),null);
});
test('imported display names are text in the preview and all restored views',async t => {
  const {app,w,d} = boot(t);
  const name = '<img/onerror=alert(1)>';
  const config = Core.normalizeConfig({anchor:'2026-10-16',cycle:[name],shiftDefs:{}});
  const content = JSON.stringify(createBackup(config,[{...config,name:'<b>备份方案</b>'}],'now'));
  app.dataTools.openDataTools();
  Object.defineProperty(d.querySelector('#backupFile'),'files',{value:[new w.File([content],'backup.json')],configurable:true});
  await app.dataTools.readBackupFile();
  assert.equal(d.querySelector('#importCycle').textContent,name);
  app.dataTools.importBackup(); d.querySelector('#confirmAccept').click();
  assert.equal(d.querySelectorAll('img').length,initialImageCount);
  assert.equal(d.querySelector('#todayShift').textContent.includes(name),true);
  assert.ok([...d.querySelectorAll('.preset-btn')].some(button => button.textContent === '<b>备份方案</b>'));
});
test('backup downloads the current session without changing local data and reports unsupported downloads',async t => {
  const {app,w,d} = boot(t);
  app.dataTools.openDataTools();
  const before = {...w.localStorage}, links = [];
  let blob;
  w.URL.createObjectURL = value => {blob=value;return 'blob:local-backup';};
  w.HTMLAnchorElement.prototype.click = function(){links.push(this.download);};
  app.dataTools.exportBackup();
  const text = await new Promise(resolve => {const reader = new w.FileReader();reader.onload=()=>resolve(reader.result);reader.readAsText(blob);});
  assert.deepEqual(parseBackup(text).config,app.schedule.captureCurrentConfig());
  assert.match(links[0],/2026-10-16\.json$/);
  assert.deepEqual({...w.localStorage},before);
  assert.match(d.querySelector('#backupStatus').textContent,/下载/);
  w.URL.createObjectURL = () => {throw new Error('Unsupported');};
  app.dataTools.exportBackup(); assert.equal(d.querySelector('#backupError').hidden,false);
});
test('copying backup text exports the entire current session without writing data or reading the clipboard',async t => {
  const {app,w,d} = boot(t); let copied;
  app.settings.saveAsPreset(); d.querySelector('#presetName').value = '🐟我的方案'; app.settings.submitPreset();
  Object.defineProperty(w.navigator,'clipboard',{value:{writeText:async text=>{copied=text;},readText:()=>{throw new Error('Never read automatically');}}});
  const before = {...w.localStorage};
  app.dataTools.openDataTools(); await app.dataTools.copyBackupText();
  const parsed = parseBackupText(copied);
  assert.deepEqual(parsed.config,app.schedule.captureCurrentConfig());
  assert.equal(parsed.presets.length,1); assert.equal(parsed.presets[0].name,'🐟我的方案');
  assert.equal(d.querySelector('#backupTextOutput').value,copied);
  assert.equal(d.querySelector('#backupTextOutput').readOnly,true);
  assert.equal(d.querySelector('#backupTextExport').hidden,false);
  assert.match(d.querySelector('#backupTextSize').textContent,/未加密/);
  assert.match(d.querySelector('#backupTextCopyStatus').textContent,/已复制/);
  assert.deepEqual({...w.localStorage},before);
  app.dataTools.selectBackupText();
  assert.equal(d.activeElement,d.querySelector('#backupTextOutput'));
  assert.equal(d.activeElement.selectionStart,0); assert.equal(d.activeElement.selectionEnd,copied.length);
  app.dataTools.closeDataTools();
  assert.equal(d.querySelector('#backupTextOutput').value,'');
});
test('unavailable or denied clipboard access leaves a complete selectable manual backup',async t => {
  for (const denied of [false,true]) {
    const {app,w,d} = boot(t);
    if (denied) Object.defineProperty(w.navigator,'clipboard',{value:{writeText:async()=>{throw new Error('Permission denied');}}});
    app.dataTools.openDataTools(); await app.dataTools.copyBackupText();
    const output = d.querySelector('#backupTextOutput');
    assert.deepEqual(parseBackupText(output.value).config,app.schedule.captureCurrentConfig());
    assert.equal(d.activeElement,output); assert.equal(output.selectionEnd,output.value.length);
    assert.match(d.querySelector('#backupTextCopyStatus').textContent,/未允许自动复制/);
    assert.equal(d.querySelector('#backupError').hidden,true);
    assert.equal(w.localStorage.getItem('qingzheng-config'),null);
  }
});
test('late clipboard callbacks cannot update a closed, reopened or newer export',async t => {
  const {app,w,d} = boot(t), writes = [];
  Object.defineProperty(w.navigator,'clipboard',{value:{writeText:()=>new Promise((resolve,reject)=>writes.push({resolve,reject}))}});
  app.dataTools.openDataTools(); const first = app.dataTools.copyBackupText();
  app.dataTools.closeDataTools(); app.dataTools.openDataTools();
  writes[0].reject(new Error('Late denial')); await first;
  assert.equal(d.querySelector('#backupTextExport').hidden,true);
  assert.equal(d.querySelector('#backupTextCopyStatus').textContent,'');
  const second = app.dataTools.copyBackupText(), third = app.dataTools.copyBackupText();
  writes[2].resolve(); await third; const status = d.querySelector('#backupTextCopyStatus').textContent;
  writes[1].reject(new Error('Older request')); await second;
  assert.equal(d.querySelector('#backupTextCopyStatus').textContent,status);
});
test('a clipboard denial during nested confirmation reports a fallback without stealing confirmation focus',async t => {
  const {app,w,d} = boot(t); let reject;
  Object.defineProperty(w.navigator,'clipboard',{value:{writeText:()=>new Promise((resolve,fail)=>{reject=fail;})}});
  app.dataTools.openDataTools();
  const copying = app.dataTools.copyBackupText();
  d.querySelector('#backupTextInput').value = createBackupText(createBackup(app.schedule.captureCurrentConfig(),[],'now'));
  app.dataTools.previewBackupText(); app.dataTools.importBackup();
  const focus = d.activeElement;
  reject(new Error('Late denial')); await copying;
  assert.equal(app.ui.activeDialog,'confirmModal'); assert.equal(d.activeElement,focus);
  d.querySelector('#confirmCancel').click();
  assert.match(d.querySelector('#backupTextCopyStatus').textContent,/未允许自动复制/);
  assert.doesNotMatch(d.querySelector('#backupTextCopyStatus').textContent,/正在/);
});
test('text restore requires explicit preview and confirmation, preserves the original and persists after reload',t => {
  const {app,w,d} = boot(t), original = app.schedule.captureCurrentConfig(), before = {...w.localStorage};
  const incoming = Core.normalizeConfig({anchor:'2026-01-01',cycle:['休息'],shiftDefs:{}});
  app.dataTools.openDataTools();
  const input = d.querySelector('#backupTextInput');
  input.value = createBackupText(createBackup(incoming,[{...incoming,name:'手机备份🐟'}],'now'));
  input.dispatchEvent(new w.Event('input',{bubbles:true}));
  assert.equal(d.querySelector('#importPreview').hidden,true);
  app.dataTools.importBackup(); assert.equal(app.ui.activeDialog,'dataModal');
  d.querySelector('[data-action="previewBackupText"]').click();
  assert.equal(d.querySelector('#importPreview').hidden,false);
  assert.equal(d.activeElement,d.querySelector('#importPreviewTitle'));
  assert.match(d.querySelector('#importSource').textContent,/粘贴文本/);
  assert.deepEqual({...w.localStorage},before);
  app.dataTools.importBackup(); d.querySelector('#confirmCancel').click();
  assert.deepEqual(app.schedule.captureCurrentConfig(),original);
  app.dataTools.importBackup(); d.querySelector('#confirmAccept').click();
  assert.deepEqual(app.schedule.cycle,['休息']); assert.equal(app.settings.presets.length,2);
  assert.deepEqual(Core.normalizeConfig(app.settings.presets[0]),original);
  assert.equal(input.value,'');
  const reloaded = boot(t,{storage:{...w.localStorage}});
  assert.deepEqual(reloaded.app.schedule.cycle,['休息']); assert.equal(reloaded.app.settings.presets.length,2);
});
test('editing text after preview or while confirming invalidates the pending restore',t => {
  const {app,w,d} = boot(t), original = app.schedule.captureCurrentConfig();
  const incoming = Core.normalizeConfig({anchor:'2026-01-01',cycle:['休息'],shiftDefs:{}});
  app.dataTools.openDataTools(); const input = d.querySelector('#backupTextInput');
  const text = createBackupText(createBackup(incoming,[],'now'));
  input.value = text; app.dataTools.previewBackupText();
  input.value += 'broken'; input.dispatchEvent(new w.Event('input',{bubbles:true}));
  assert.equal(d.querySelector('#importPreview').hidden,true);
  app.dataTools.importBackup(); assert.equal(app.ui.activeDialog,'dataModal');
  input.value = text; app.dataTools.previewBackupText(); app.dataTools.importBackup();
  input.value += ' '; // Programmatic changes without input events must also be checked on confirmation.
  d.querySelector('#confirmAccept').click();
  assert.equal(app.ui.activeDialog,'dataModal'); assert.match(d.querySelector('#backupError').textContent,/重新预览/);
  assert.deepEqual(app.schedule.captureCurrentConfig(),original);
  assert.equal(w.localStorage.getItem('qingzheng-config'),null);
});
test('invalid or future text hides a previous preview and cannot import or persist anything',t => {
  const {app,w,d} = boot(t), original = app.schedule.captureCurrentConfig();
  app.dataTools.openDataTools(); const input = d.querySelector('#backupTextInput');
  const text = createBackupText(createBackup(original,[],'now'));
  for (const invalid of ['',text.slice(0,-1),text.replace('QZB1.','QZB2.')]) {
    input.value = text; app.dataTools.previewBackupText();
    assert.equal(d.querySelector('#importPreview').hidden,false);
    input.value = invalid; app.dataTools.previewBackupText();
    assert.equal(d.querySelector('#importPreview').hidden,true); assert.equal(d.querySelector('#backupError').hidden,false);
    assert.equal(d.activeElement,d.querySelector('#backupError'));
    app.dataTools.importBackup(); assert.equal(app.ui.activeDialog,'dataModal');
  }
  assert.deepEqual(app.schedule.captureCurrentConfig(),original);
  assert.equal(w.localStorage.getItem('qingzheng-config'),null);
});
test('pasting raw JSON uses the same preview and storage-failure recovery as files',t => {
  const {app,w,d} = boot(t), original = app.schedule.captureCurrentConfig();
  app.settings.commitConfig(original); const persisted = w.localStorage.getItem('qingzheng-config');
  const setItem = w.Storage.prototype.setItem;
  w.Storage.prototype.setItem = function(key,value) {
    if (key === 'qingzheng-presets') throw new Error('Full storage');
    setItem.call(this,key,value);
  };
  const incoming = Core.normalizeConfig({anchor:'2026-01-01',cycle:['休息'],shiftDefs:{}});
  app.dataTools.openDataTools(); d.querySelector('#backupTextInput').value = JSON.stringify(createBackup(incoming,[],'now'));
  app.dataTools.previewBackupText(); app.dataTools.importBackup(); d.querySelector('#confirmAccept').click();
  assert.deepEqual(app.schedule.cycle,['休息']); assert.equal(w.localStorage.getItem('qingzheng-config'),persisted);
  assert.match(d.querySelector('#toast').textContent,/未能完整保存/);
});
test('switching from an unfinished file read to pasted text discards stale success and error callbacks',async t => {
  for (const fail of [false,true]) {
    const {app,w,d} = boot(t), readers = [];
    w.FileReader = class { constructor(){readers.push(this);} readAsText(){} };
    app.dataTools.openDataTools();
    Object.defineProperty(d.querySelector('#backupFile'),'files',{value:[new w.File(['old'],'backup.json')],configurable:true});
    const reading = app.dataTools.readBackupFile();
    const incoming = Core.normalizeConfig({anchor:'2026-01-01',cycle:['休息'],shiftDefs:{}});
    const input = d.querySelector('#backupTextInput'); input.value = createBackupText(createBackup(incoming,[],'now'));
    input.dispatchEvent(new w.Event('input',{bubbles:true})); app.dataTools.previewBackupText();
    if (fail) readers[0].onerror();
    else { readers[0].result = JSON.stringify(createBackup(app.schedule.captureCurrentConfig(),[],'old')); readers[0].onload(); }
    await reading;
    assert.match(d.querySelector('#importSource').textContent,/粘贴文本/);
    assert.equal(d.querySelector('#backupError').hidden,true);
    app.dataTools.importBackup(); d.querySelector('#confirmAccept').click();
    assert.deepEqual(app.schedule.cycle,['休息']);
  }
});
test('saving a preset appends only its own controls and preserves all existing preset buttons',t => {
  const config = Core.normalizeConfig({anchor:'2026-04-18',cycle:['休息'],shiftDefs:{}});
  const presets = Array.from({length:100},(_,index) => ({...config,id:`preset_${index}`,name:`方案${index}`}));
  const {app,d,w} = boot(t,{storage:{'qingzheng-config':JSON.stringify(config),'qingzheng-presets':JSON.stringify(presets)}});
  const bar = d.querySelector('#presetBar'), originals = [...bar.querySelectorAll('.preset-group')];
  const observer = new w.MutationObserver(()=>{}); observer.observe(bar,{subtree:true,childList:true,attributes:true});
  app.settings.saveAsPreset(); d.querySelector('#presetName').value = '新增方案'; app.settings.submitPreset();
  const records = observer.takeRecords();
  assert.equal(records.flatMap(record=>[...record.removedNodes]).length,0,'Saving must not rebuild the existing bar');
  assert.equal(records.flatMap(record=>[...record.addedNodes]).filter(node=>node.classList?.contains('preset-group')).length,1);
  assert.deepEqual([...bar.querySelectorAll('.preset-group')].slice(0,100),originals);
  assert.equal(bar.querySelectorAll('.preset-group.active').length,1);
  assert.equal(bar.querySelector('.preset-group.active .preset-btn').textContent,'新增方案');
  app.settings.renderPresetBar();
  assert.deepEqual(observer.takeRecords(),[],'A no-op refresh must not write any DOM');
  assert.equal(JSON.parse(w.localStorage.getItem('qingzheng-presets')).length,101);
  originals[0].querySelector('.preset-btn').click();
  assert.equal(bar.querySelector('.preset-group.active'),originals[0]);
  assert.equal(originals.every(node=>node.isConnected),true);
  observer.disconnect();
});
test('deleting one preset leaves the remaining controls connected and functional',t => {
  const {app,d} = boot(t);
  for (const name of ['保留方案','删除方案']) {
    app.settings.saveAsPreset(); d.querySelector('#presetName').value = name; app.settings.submitPreset();
  }
  const retained = d.querySelector('.preset-group'), removedId = app.settings.presets[1].id;
  app.settings.deletePreset(removedId); d.querySelector('#confirmAccept').click();
  assert.equal(d.querySelectorAll('.preset-group').length,1);
  assert.equal(d.querySelector('.preset-group'),retained);
  assert.equal(retained.querySelector('.preset-btn').textContent,'保留方案');
  assert.equal(d.querySelector('.preset-label').textContent,'自定义排班');
  retained.querySelector('.preset-btn').click();
  assert.equal(d.querySelector('.preset-label'),null);
  assert.equal(retained.classList.contains('active'),true);
});

const localAvatar = 'data:image/webp;base64,YWJjZA==';
const profileRecord = (title = '我的生活日历',avatar = localAvatar) => JSON.stringify({version:1,title,avatar});
function chooseAvatar(w,d,bytes = [137,80,78,71,13,10,26,10]) {
  const file = new w.File([Uint8Array.from(bytes)],'photo.png',{type:'image/png'});
  Object.defineProperty(d.querySelector('#avatarFile'),'files',{value:[file],configurable:true});
  return file;
}
function mockAvatarCanvas(w,{deferred = false,width = 640,height = 480} = {}) {
  const images = [], draws = [];
  w.FileReader = class {
    readAsArrayBuffer() { this.result = Uint8Array.from([137,80,78,71,13,10,26,10]).buffer; queueMicrotask(() => this.onload()); }
    readAsDataURL() { this.result = 'data:image/png;base64,YWJjZA=='; queueMicrotask(() => this.onload()); }
  };
  w.Image = class {
    constructor() { this.naturalWidth = width; this.naturalHeight = height; images.push(this); }
    set src(value) { this.data = value; if (!deferred) queueMicrotask(() => this.onload()); }
  };
  w.HTMLCanvasElement.prototype.getContext = () => ({drawImage(...args) { draws.push(args); }});
  w.HTMLCanvasElement.prototype.toDataURL = function(type,quality) {
    assert.equal(this.width,256); assert.equal(this.height,256);
    assert.equal(type,'image/webp'); assert.equal(quality,0.86); return localAvatar;
  };
  return {images,draws};
}
test('personalization is an isolated static draft and cancel, Escape and backdrop discard it',t => {
  const {app,d,w} = boot(t), original = app.schedule.captureCurrentConfig();
  const opener = d.querySelector('[data-action="openPersonalization"]');
  for (const close of ['button','escape','backdrop']) {
    opener.focus(); opener.click();
    assert.equal(app.ui.activeDialog,'personalizationModal'); assert.equal(app.liveTimer,null);
    assert.equal(d.querySelector('#personalizationModal').classList.contains('input-view'),true);
    assert.equal(d.activeElement.id,'personalizationForm','Opening must not summon a mobile keyboard');
    const input = d.querySelector('#personalizationTitle'); input.value = '草稿标题';
    input.dispatchEvent(new w.Event('input',{bubbles:true}));
    assert.equal(d.querySelector('#personalizationPreviewTitle').textContent,'草稿标题');
    assert.equal(d.querySelector('#brandTitle').textContent,'清蒸排班');
    if (close === 'button') d.querySelector('[data-action="closePersonalization"]').click();
    if (close === 'escape') input.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true}));
    if (close === 'backdrop') d.querySelector('#personalizationModal').click();
    assert.equal(app.ui.activeDialog,null); assert.notEqual(app.liveTimer,null); assert.equal(d.activeElement,opener);
    assert.equal(w.localStorage.length,0); assert.deepEqual(app.schedule.captureCurrentConfig(),original);
  }
});
test('saving an arbitrary title uses plain text on every brand surface and survives reload',t => {
  const {app,d,w} = boot(t), hero = d.querySelector('#pageTitle').textContent;
  app.personalization.openPersonalization();
  const title = '<b>小林的日历 🐟</b>';
  d.querySelector('#personalizationTitle').value = ' '+title+' ';
  d.querySelector('#personalizationForm').dispatchEvent(new w.Event('submit',{bubbles:true,cancelable:true}));
  for (const id of ['brandTitle','footerBrand']) assert.equal(d.querySelector('#'+id).textContent,title);
  assert.equal(d.querySelector('#brandTitle b'),null); assert.equal(d.title,title);
  assert.equal(d.querySelector('#pageTitle').textContent,hero);
  assert.equal(d.querySelector('.brand').getAttribute('aria-label'),title+'首页');
  const stored = w.localStorage.getItem('qingzheng-personalization');
  assert.deepEqual(JSON.parse(stored),{version:1,title,avatar:null});
  const reloaded = boot(t,{storage:{'qingzheng-personalization':stored}});
  assert.equal(reloaded.app.personalization.title,title); assert.equal(reloaded.d.title,title);
  reloaded.app.personalization.openPersonalization(); reloaded.app.personalization.resetTitle();
  assert.equal(reloaded.app.personalization.title,title,'Reset is still a draft');
  reloaded.app.personalization.savePersonalization();
  assert.equal(reloaded.d.querySelector('#brandTitle .brand-light').textContent,'排班');
  assert.equal(reloaded.d.title,html.match(/<title>(.*?)<\/title>/)[1]);
});
test('personalization validates title and IME Enter without closing or modifying saved data',t => {
  const {app,d,w} = boot(t);
  app.personalization.openPersonalization(); const input = d.querySelector('#personalizationTitle');
  const composing = new w.KeyboardEvent('keydown',{key:'Enter',isComposing:true,bubbles:true,cancelable:true});
  input.dispatchEvent(composing); assert.equal(composing.defaultPrevented,true);
  for (const title of ['',' '.repeat(3),'x'.repeat(41),'bad\u0000title']) {
    input.value = title; app.personalization.savePersonalization();
    assert.equal(app.ui.activeDialog,'personalizationModal'); assert.equal(w.localStorage.length,0);
    assert.equal(input.getAttribute('aria-invalid'),'true'); assert.equal(d.activeElement,input);
  }
});
test('local personalization loads without writes and invalid records retain original storage',t => {
  const valid = boot(t,{storage:{'qingzheng-personalization':profileRecord()}});
  assert.equal(valid.d.querySelector('#brandAvatar').getAttribute('src'),localAvatar);
  assert.equal(valid.w.localStorage.getItem('qingzheng-personalization'),profileRecord());
  for (const value of ['{bad','[]','{"version":2,"title":"未来"}',profileRecord('', 'https://bad.test/avatar.svg')]) {
    const {app,d,w} = boot(t,{storage:{'qingzheng-personalization':value}});
    assert.equal(app.personalization.title,'清蒸排班'); assert.equal(app.personalization.avatar,null);
    assert.equal(w.localStorage.getItem('qingzheng-personalization'),value);
    assert.equal(d.querySelector('#storageNotice').hidden,false);
  }
});
test('a failed personalization save preserves stored data and reports session-only application',t => {
  const {app,d,w} = boot(t,{storage:{'qingzheng-personalization':profileRecord()}});
  const setItem = w.Storage.prototype.setItem;
  w.Storage.prototype.setItem = function(key,value) {
    if (key === 'qingzheng-personalization') throw new Error('Quota');
    return setItem.call(this,key,value);
  };
  app.personalization.openPersonalization(); app.personalization.resetAvatar();
  d.querySelector('#personalizationTitle').value = '本次标题'; app.personalization.savePersonalization();
  assert.equal(app.personalization.title,'本次标题'); assert.equal(app.personalization.avatar,null);
  assert.equal(w.localStorage.getItem('qingzheng-personalization'),profileRecord());
  assert.match(d.querySelector('#toast').textContent,/未能保存/);
});
test('avatar conversion center-crops to a bounded static image and persists only on save',async t => {
  const {app,d,w} = boot(t), {draws} = mockAvatarCanvas(w);
  const defaultSrc = d.querySelector('#brandAvatar').getAttribute('src');
  app.personalization.openPersonalization(); chooseAvatar(w,d); await app.personalization.readAvatarFile();
  assert.deepEqual(draws[0].slice(1),[80,0,480,480,0,0,256,256]);
  assert.equal(d.querySelector('#personalizationPreviewAvatar').getAttribute('src'),localAvatar);
  assert.equal(d.querySelector('#brandAvatar').getAttribute('src'),defaultSrc); assert.equal(w.localStorage.length,0);
  app.personalization.savePersonalization(); assert.equal(app.personalization.avatar,localAvatar);
  const stored = w.localStorage.getItem('qingzheng-personalization');
  assert.equal(boot(t,{storage:{'qingzheng-personalization':stored}}).app.personalization.avatar,localAvatar);
  app.personalization.openPersonalization(); app.personalization.resetAvatar(); app.personalization.closePersonalization();
  assert.equal(app.personalization.avatar,localAvatar,'Cancelled reset keeps the saved avatar');
  app.personalization.openPersonalization(); app.personalization.resetAvatar(); app.personalization.savePersonalization();
  assert.equal(app.personalization.avatar,null); assert.equal(d.querySelector('#brandAvatar').getAttribute('src'),defaultSrc);
});
test('invalid avatar files, oversize input, decoding and canvas errors leave the original avatar intact',async t => {
  for (const mode of ['signature','bytes','pixels','decode','canvas']) {
    const {app,d,w} = boot(t,{storage:{'qingzheng-personalization':profileRecord()}});
    app.personalization.openPersonalization();
    if (mode === 'signature') chooseAvatar(w,d,[60,115,118,103,62]);
    else {
      const file = chooseAvatar(w,d);
      if (mode === 'bytes') Object.defineProperty(file,'size',{value:10*1024*1024+1});
      else {
        mockAvatarCanvas(w,mode === 'pixels' ? {width:10000,height:10000} : {});
        if (mode === 'decode') w.Image = class { set src(_) { queueMicrotask(() => this.onerror()); } };
        if (mode === 'canvas') w.HTMLCanvasElement.prototype.getContext = () => null;
      }
    }
    await app.personalization.readAvatarFile();
    assert.equal(d.querySelector('#personalizationError').hidden,false,mode);
    assert.equal(d.querySelector('#personalizationSave').disabled,false);
    assert.equal(d.querySelector('#personalizationPreviewAvatar').getAttribute('src'),localAvatar);
    assert.equal(w.localStorage.getItem('qingzheng-personalization'),profileRecord());
  }
});
test('stale avatar decoding cannot overwrite a reset, a reopened draft or a newer upload',async t => {
  for (const mode of ['reset','reopen','newer']) {
    const {app,d,w} = boot(t), {images} = mockAvatarCanvas(w,{deferred:true});
    app.personalization.openPersonalization(); chooseAvatar(w,d);
    const first = app.personalization.readAvatarFile(); await new Promise(setImmediate);
    assert.equal(d.querySelector('#personalizationSave').disabled,true);
    app.personalization.savePersonalization(); assert.equal(app.ui.activeDialog,'personalizationModal');
    if (mode === 'reset') app.personalization.resetAvatar();
    if (mode === 'reopen') { app.personalization.closePersonalization(); app.personalization.openPersonalization(); }
    let second;
    if (mode === 'newer') { chooseAvatar(w,d); second = app.personalization.readAvatarFile(); await new Promise(setImmediate); }
    images[0].onload(); await first;
    assert.notEqual(d.querySelector('#personalizationPreviewAvatar').getAttribute('src'),localAvatar);
    if (mode === 'newer') {
      assert.equal(d.querySelector('#personalizationSave').disabled,true,'Stale completion must not clear a newer busy state');
      images[1].onload(); await second;
      assert.equal(d.querySelector('#personalizationPreviewAvatar').getAttribute('src'),localAvatar);
    }
    assert.equal(w.localStorage.length,0);
  }
});
test('both export routes contain the saved title and no avatar data',async t => {
  const {app,d,w} = boot(t,{storage:{'qingzheng-personalization':profileRecord()}});
  app.dataTools.openDataTools(); await app.dataTools.copyBackupText();
  const text = d.querySelector('#backupTextOutput').value;
  assert.deepEqual(parseBackupText(text).personalization,{title:'我的生活日历'});
  let blob; w.URL.createObjectURL = value => { blob = value; return 'blob:local'; };
  w.HTMLAnchorElement.prototype.click = () => {}; app.dataTools.exportBackup();
  const json = await new Promise(resolve => { const reader = new w.FileReader(); reader.onload = () => resolve(reader.result); reader.readAsText(blob); });
  assert.deepEqual(parseBackup(json).personalization,{title:'我的生活日历'});
  assert.doesNotMatch(json,/avatar|YWJjZA/);
  assert.equal(w.localStorage.getItem('qingzheng-personalization'),profileRecord());
});
test('file and text import preview title safely, cancel without writes and preserve local avatar on confirm',async t => {
  for (const source of ['file','text']) {
    const {app,d,w} = boot(t,{storage:{'qingzheng-personalization':profileRecord()}});
    const incoming = createBackup(Core.normalizeConfig({anchor:'2026-01-01',cycle:['休息'],shiftDefs:{}}),[],'now',{title:'<b>换机标题</b>'});
    incoming.personalization.avatar = 'data:image/png;base64,SHOULDNOTIMPORT';
    app.dataTools.openDataTools();
    if (source === 'text') { d.querySelector('#backupTextInput').value = createBackupText(incoming); app.dataTools.previewBackupText(); }
    else {
      Object.defineProperty(d.querySelector('#backupFile'),'files',{value:[new w.File([JSON.stringify(incoming)],'backup.json')],configurable:true});
      await app.dataTools.readBackupFile();
    }
    assert.match(d.querySelector('#importTitlePreview').textContent,/<b>换机标题<\/b>/);
    assert.equal(d.querySelector('#importTitlePreview b'),null);
    app.dataTools.importBackup(); d.querySelector('#confirmCancel').click();
    assert.equal(w.localStorage.getItem('qingzheng-personalization'),profileRecord());
    app.dataTools.importBackup(); d.querySelector('#confirmAccept').click();
    assert.equal(app.personalization.title,'<b>换机标题</b>'); assert.equal(app.personalization.avatar,localAvatar);
    assert.deepEqual(JSON.parse(w.localStorage.getItem('qingzheng-personalization')),{version:1,title:'<b>换机标题</b>',avatar:localAvatar});
  }
});
test('failed schedule recovery also preserves the persisted personalization and reports incomplete import',t => {
  const {app,d,w} = boot(t,{storage:{'qingzheng-personalization':profileRecord()}});
  app.settings.commitConfig(app.schedule.captureCurrentConfig());
  const setItem = w.Storage.prototype.setItem;
  w.Storage.prototype.setItem = function(key,value) {
    if (key === 'qingzheng-presets') throw new Error('Quota');
    return setItem.call(this,key,value);
  };
  app.dataTools.openDataTools();
  d.querySelector('#backupTextInput').value = createBackupText(createBackup(app.schedule.captureCurrentConfig(),[],'now',{title:'导入标题'}));
  app.dataTools.previewBackupText(); app.dataTools.importBackup(); d.querySelector('#confirmAccept').click();
  assert.equal(app.personalization.title,'导入标题'); assert.equal(app.personalization.avatar,localAvatar);
  assert.equal(w.localStorage.getItem('qingzheng-personalization'),profileRecord());
  assert.match(d.querySelector('#toast').textContent,/未能完整保存/);
});
