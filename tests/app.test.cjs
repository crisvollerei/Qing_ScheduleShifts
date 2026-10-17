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
const initialImageCount = (html.match(/<img\b/g) || []).length;
function boot(t,{storage={},blocked=false,now='2026-10-16T12:00:00',reducedMotion=null}={}) {
  const dom = new JSDOM(html,{url:'http://localhost/',runScripts:'outside-only',pretendToBeVisual:true});
  t.after(()=>dom.window.close());
  const w = dom.window, NativeDate = w.Date;
  let instant = new NativeDate(now).getTime();
  w.Date = class extends NativeDate { constructor(...args) { super(...(args.length ? args : [instant])); } static now() { return instant; } };
  w.confirm = () => true; w.prompt = () => null;
  if (reducedMotion !== null) {
    w.matchMedia = () => ({matches:reducedMotion,addEventListener(){}});
    if (reducedMotion) w.Element.prototype.animate = () => {throw new Error('Reduced-motion users must not receive scripted animations');};
  }
  for (const [key,value] of Object.entries(storage)) w.localStorage.setItem(key,value);
  if (blocked) Object.defineProperty(w,'localStorage',{get(){throw new Error('Storage is disabled');}});
  const context = dom.getInternalVMContext();
  for (const filename of ['schedule-core.js','calendar-data.js','app.js']) vm.runInContext(fs.readFileSync(path.join(root,'assets',filename),'utf8'),context,{filename});
  return {w,d:w.document,read:expression=>vm.runInContext(expression,context),setNow:value=>{instant=new NativeDate(value).getTime();}};
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
test('default initialization renders a full month and twelve accessible month buttons',t => {
  const {d} = boot(t);
  assert.equal(d.querySelectorAll('#daysGrid button').length,31);
  assert.equal(d.querySelectorAll('#heatmapGrid button').length,12);
  assert.equal(d.querySelectorAll('#statsContainer .stat-card').length,3);
  assert.equal(d.querySelector('#workHours').textContent,'192');
  assert.equal(d.querySelector('#storageNotice').hidden,true);
});
test('broken JSON, empty cycles and blocked storage all retain a usable calendar',t => {
  for (const options of [{storage:{'qingzheng-cycle':'not JSON'}},{storage:{'qingzheng-cycle':'[]','qingzheng-anchor':'2026-02-31'}},{blocked:true}]) {
    const {d} = boot(t,options);
    assert.equal(d.querySelectorAll('#daysGrid button').length,31);
    assert.equal(d.querySelector('#storageNotice').hidden,false);
  }
});
test('legacy settings and presets migrate without an active preset overwriting current settings',t => {
  const preset = {id:'old',name:'旧方案',anchor:'2026-04-18',cycle:['白班'],shiftDefs:{}};
  const {read,d} = boot(t,{storage:{'qingzheng-cycle':'["休息"]','qingzheng-presets':JSON.stringify([preset]),'qingzheng-active-preset':'old'}});
  assert.equal(read('CYCLE[0]'),'休息');
  assert.equal(d.querySelector('#todayShift').textContent.includes('休息'),true);
});
test('settings cancellation discards additions, removals and in-form edits',t => {
  const {w,read,d} = boot(t); const before = read('JSON.stringify(captureCurrentConfig())');
  w.openSettings();
  d.querySelector('[data-shift="白班"] [data-field="startTime"]').value='07:30';
  w.prompt=()=> '新班'; w.addShiftDef();
  assert.equal(d.querySelector('[data-shift="白班"] [data-field="startTime"]').value,'07:30');
  w.removeShiftDef('夜班'); w.closeSettings();
  assert.equal(read('JSON.stringify(captureCurrentConfig())'),before);
  w.openSettings(); assert.equal(d.querySelector('[data-shift="白班"] [data-field="startTime"]').value,'08:00');
  assert.equal(d.querySelector('[data-shift="新班"]'),null);
});
test('settings validate work times and save all fields in one canonical record',t => {
  const {w,d,read} = boot(t); w.openSettings();
  const input = d.querySelector('[data-shift="白班"] [data-field="startTime"]');
  input.value=''; w.saveSettings(); assert.equal(d.querySelector('#settingsModal').hidden,false);
  assert.equal(d.querySelector('#settingsError').hidden,false);
  input.value='07:30'; w.saveSettings(); assert.equal(d.querySelector('#settingsModal').hidden,true);
  const saved = JSON.parse(w.localStorage.getItem('qingzheng-config'));
  assert.equal(saved.shiftDefs['白班'].startTime,'07:30');
  assert.equal(read('SHIFT_DEFS["白班"].startTime'),'07:30');
  const reloaded = boot(t,{storage:{'qingzheng-config':JSON.stringify(saved)}});
  assert.equal(reloaded.read('SHIFT_DEFS["白班"].startTime'),'07:30');
});
test('saving new names first presents their generated shift times for review',t => {
  const {w,d,read}=boot(t); w.openSettings(); d.querySelector('#cyclePattern').value='新班 休息';
  w.saveSettings(); assert.equal(d.querySelector('#settingsModal').hidden,false);
  assert.ok(d.querySelector('[data-shift="新班"]'));
  assert.equal(read('CYCLE.length'),8);
  w.saveSettings(); assert.equal(read('CYCLE.join(" ")'),'新班 休息');
});
test('custom names are text across the settings, today, statistics and detail views',t => {
  const name = '<img/src=x/onerror=alert(1)>';
  const {w,d,read}=boot(t,{storage:{'qingzheng-cycle':JSON.stringify([name,'constructor','__proto__'])}});
  w.openSettings();
  assert.ok(d.querySelector('#shiftDefsEditor').textContent.includes(name));
  assert.equal(d.querySelectorAll('img').length,initialImageCount);
  w.closeSettings(); w.showDetail(new w.Date());
  assert.equal(d.querySelectorAll('img').length,initialImageCount);
  assert.equal(Object.values(read('calculateMonthlyStats(2026,9)')).reduce((a,b)=>a+b,0),31);
});
test('long cycles search the complete cycle and no-rest cycles display a useful message',t => {
  const {w,read,d} = boot(t);
  w.eval(`CYCLE = [...Array(40).fill('白班'),'休息']; ANCHOR_DATE = new Date(2026,9,16);`);
  assert.equal(read('getDaysToWeekend(new Date(2026,9,16))'),40);
  w.eval(`CYCLE = [...Array(40).fill('休息'),'白班'];`);
  assert.ok(read('getNextWorkCountdown(new Date(2026,9,16))') > 39*86400000);
  assert.equal(read('calculateNextShifts(new Date(2026,9,16))["白班"]'),40);
  w.eval(`CYCLE = ['白班'];`); w.render(); w.showDetail(new w.Date());
  assert.equal(d.querySelector('#restCountdown').textContent,'周期内无休息日');
  assert.ok(d.querySelector('#detailExtra').textContent.includes('整个周期'));
  assert.equal(d.querySelector('#detailExtra').textContent.includes('-1'),false);
});
test('overnight card keeps the previous shift date and cycle position until its exact end',t => {
  const {read,w,d}=boot(t,{now:'2026-04-20T07:59:59'});
  const current = read('getEffectiveShiftInfo()');
  assert.equal(current.shift,'夜班'); assert.equal(current.date.getDate(),19);
  assert.equal(d.querySelector('#todayCycle').textContent,'2');
  assert.ok(d.querySelector('#todayShiftTime').textContent.includes('昨日班次'));
  assert.equal(d.querySelector('#restCountdown').textContent,'本班结束后');
  assert.equal(read('getEffectiveShiftInfo(new Date(2026,3,20,8,0)).shift'),'休息');
  assert.equal(w.formatCountdown(40*86400000),'40天 00:00:00');
});
test('navigation bounds apply to direct calls, keyboard navigation and picker years',t => {
  const {w,d,read}=boot(t);
  w.navigateMonth(2025,0); w.prevMonth(); assert.equal(read('viewYear'),2025); assert.equal(read('viewMonth'),0);
  w.navigateMonth(2027,11); w.nextMonth(); assert.equal(read('viewYear'),2027); assert.equal(read('viewMonth'),11);
  d.dispatchEvent(new w.KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true})); assert.equal(read('viewYear'),2027);
  w.openPicker(); w.pickerNextYear(); assert.equal(read('pickerYear'),2027);
  const before=read('viewMonth'); d.dispatchEvent(new w.KeyboardEvent('keydown',{key:'ArrowLeft',bubbles:true})); assert.equal(read('viewMonth'),before);
});
test('dialogs trap focus, restore their opener and close with Escape',t => {
  const {w,d}=boot(t); const opener=d.querySelector('[aria-label="打开排班设置"]'); opener.focus(); w.openSettings();
  assert.equal(d.querySelector('.main-container').inert,true);
  const save=d.querySelector('.btn-group .button-primary'); save.focus();
  save.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Tab',bubbles:true,cancelable:true}));
  assert.equal(d.activeElement.getAttribute('aria-label'),'关闭排班设置');
  d.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Escape',bubbles:true}));
  assert.equal(d.querySelector('#settingsModal').hidden,true); assert.equal(d.activeElement,opener);
});
test('preset add/apply/delete preserves live settings and restores the saved configuration',t => {
  const {w,d,read}=boot(t); w.saveAsPreset();
  d.querySelector('#presetName').value='测试方案';
  d.querySelector('#presetForm').dispatchEvent(new w.Event('submit',{bubbles:true,cancelable:true}));
  assert.equal(d.querySelectorAll('.preset-group').length,1);
  assert.equal(d.querySelectorAll('.preset-btn button').length,0);
  const id=read('PRESETS[0].id'), before=read('JSON.stringify(captureCurrentConfig())');
  w.openSettings(); d.querySelector('#cyclePattern').value='休息'; w.saveSettings();
  w.applyPreset(id); assert.equal(read('JSON.stringify(captureCurrentConfig())'),before);
  w.deletePreset(id); assert.equal(read('CYCLE.length'),8); assert.equal(d.querySelectorAll('.preset-group').length,0);
});
test('saving presets works when native WebView prompts return null, throw or are missing',t => {
  for (const prompt of [()=>null,()=>{throw new Error('Native dialogs are disabled');},undefined]) {
    const {w,d,read}=boot(t);
    w.prompt=prompt;w.confirm=()=>{throw new Error('Native confirmation is disabled');};
    const opener=d.querySelector('[onclick="saveAsPreset()"]');opener.focus();read(opener.getAttribute('onclick'));
    assert.equal(d.querySelector('#presetModal').hidden,false);
    assert.equal(d.activeElement.id,'presetForm');
    assert.equal(d.querySelector('.main-container').inert,true);
    assert.equal(read('PRESETS.length'),0);
    d.querySelector('#presetName').value='应用内方案';
    const event=new w.Event('submit',{bubbles:true,cancelable:true});
    d.querySelector('#presetForm').dispatchEvent(event);
    assert.equal(event.defaultPrevented,true);
    assert.equal(d.querySelector('#presetModal').hidden,true);
    assert.equal(read('PRESETS.length'),1);
    assert.equal(JSON.parse(w.localStorage.getItem('qingzheng-presets'))[0].name,'应用内方案');
    assert.equal(d.querySelector('#toast').textContent,'当前排班已保存为方案');
    assert.equal(d.activeElement,opener);
    assert.equal(w.location.href,'http://localhost/');
  }
});
test('preset naming validates blank, long and duplicate names inside the dialog',t => {
  const preset={id:'existing',name:'已有方案',anchor:'2026-04-18',cycle:['白班'],shiftDefs:{}};
  const {w,d,read}=boot(t,{storage:{'qingzheng-presets':JSON.stringify([preset])}});
  const previous=w.localStorage.getItem('qingzheng-presets');
  w.saveAsPreset();
  const input=d.querySelector('#presetName'),error=d.querySelector('#presetError');
  for (const [name,expected] of [['   ','请填写'],['长'.repeat(41),'最多 40'],[' 已有方案 ','同名']]) {
    input.value=name;w.submitPreset();
    assert.equal(d.querySelector('#presetModal').hidden,false);
    assert.equal(error.hidden,false);assert.ok(error.textContent.includes(expected));
    assert.equal(input.getAttribute('aria-invalid'),'true');
    assert.equal(d.activeElement,input);
    assert.equal(read('PRESETS.length'),1);
    assert.equal(w.localStorage.getItem('qingzheng-presets'),previous);
    input.dispatchEvent(new w.Event('input',{bubbles:true}));
    assert.equal(error.hidden,true);assert.equal(input.getAttribute('aria-invalid'),'false');
  }
  input.value='新'.repeat(40);w.submitPreset();
  assert.equal(read('PRESETS.length'),2);
  assert.equal(d.querySelector('#presetModal').hidden,true);
});
test('cancel, close, Escape and backdrop dismiss preset naming without modifying storage',t => {
  for (const method of ['cancel','close','escape','backdrop']) {
    const {w,d,read}=boot(t);
    const opener=d.querySelector('[onclick="saveAsPreset()"]');opener.focus();read(opener.getAttribute('onclick'));
    d.querySelector('#presetName').value='还没确认';
    if (method==='cancel') read(d.querySelector('#presetModal .btn-group button[type="button"]').getAttribute('onclick'));
    else if (method==='close') read(d.querySelector('[aria-label="关闭保存方案"]').getAttribute('onclick'));
    else if (method==='escape') d.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true}));
    else d.querySelector('#presetModal').click();
    assert.equal(d.querySelector('#presetModal').hidden,true,method);
    assert.equal(read('PRESETS.length'),0,method);
    assert.equal(w.localStorage.getItem('qingzheng-presets'),null,method);
    assert.equal(d.querySelector('.main-container').inert,false,method);
    assert.equal(d.body.style.overflow,'',method);
    assert.equal(d.activeElement,opener,method);
    read(opener.getAttribute('onclick'));assert.equal(d.querySelector('#presetName').value,'',method);
    assert.equal(d.querySelector('#presetError').hidden,true,method);
  }
});
test('repeated open and submit events neither clear an unfinished name nor save twice',t => {
  const {w,d,read}=boot(t);w.saveAsPreset();
  const input=d.querySelector('#presetName');input.value='  轮班方案  ';
  w.saveAsPreset();assert.equal(input.value,'  轮班方案  ');
  for (let i=0;i<2;i++) d.querySelector('#presetForm').dispatchEvent(new w.Event('submit',{bubbles:true,cancelable:true}));
  assert.equal(read('PRESETS.length'),1);
  assert.equal(read('PRESETS[0].name'),'轮班方案');
  assert.equal(d.querySelector('#presetModal').hidden,true);
});
test('unavailable or quota-blocked storage reports that a preset is only kept for the session',t => {
  for (const blocked of [true,false]) {
    const {w,d,read}=boot(t,{blocked});
    if (!blocked) w.Storage.prototype.setItem=()=>{throw new Error('Quota exceeded');};
    w.prompt=()=>{throw new Error('Prompt must not be used');};
    w.saveAsPreset();d.querySelector('#presetName').value='临时方案';w.submitPreset();
    assert.equal(read('PRESETS.length'),1);
    assert.equal(d.querySelector('#presetModal').hidden,true);
    assert.equal(d.querySelector('#storageNotice').hidden,false);
    assert.equal(d.querySelector('#toast').textContent,'方案仅在本次打开期间有效');
    assert.equal(d.querySelector('.preset-btn').textContent,'临时方案');
    if (!blocked) assert.equal(w.localStorage.getItem('qingzheng-presets'),null);
  }
});
test('preset names remain text in buttons and descriptions after form submission',t => {
  const {w,d,read}=boot(t);const name='<img/src=x/onerror=alert(1)>';
  w.saveAsPreset();d.querySelector('#presetName').value=name;w.submitPreset();
  assert.equal(d.querySelector('.preset-btn').textContent,name);
  assert.equal(d.querySelector('.preset-btn').title,`应用方案「${name}」`);
  assert.equal(d.querySelectorAll('img').length,initialImageCount);
  assert.equal(read('PRESETS[0].name'),name);
});
test('form-created presets keep the existing schema and restore the complete schedule on reload',t => {
  const {w,d,read}=boot(t);
  w.openSettings();d.querySelector('[data-shift="白班"] [data-field="startTime"]').value='07:15';w.saveSettings();
  const original=read('JSON.stringify(captureCurrentConfig())');
  w.saveAsPreset();d.querySelector('#presetName').value='早一点上班';w.submitPreset();
  const saved=w.localStorage.getItem('qingzheng-presets');
  w.commitConfig({anchor:'2026-10-16',cycle:['休息'],shiftDefs:{}});
  const config=w.localStorage.getItem('qingzheng-config');
  const reloaded=boot(t,{storage:{'qingzheng-presets':saved,'qingzheng-config':config}});
  assert.equal(reloaded.read('PRESETS.length'),1);
  reloaded.d.querySelector('.preset-btn').click();
  assert.equal(reloaded.read('JSON.stringify(captureCurrentConfig())'),original);
  assert.equal(reloaded.read('SHIFT_DEFS["白班"].startTime'),'07:15');
});
test('preset dialogs trap keyboard focus and block calendar shortcuts without breaking other dialogs',t => {
  const {w,d,read}=boot(t);w.saveAsPreset();
  const month=read('viewMonth'),first=d.querySelector('[aria-label="关闭保存方案"]'),last=d.querySelector('#presetSubmit');
  last.focus();last.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Tab',bubbles:true,cancelable:true}));
  assert.equal(d.activeElement,first);
  first.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Tab',shiftKey:true,bubbles:true,cancelable:true}));
  assert.equal(d.activeElement,last);
  d.dispatchEvent(new w.KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true,cancelable:true}));
  assert.equal(read('viewMonth'),month);
  d.querySelector('#presetName').focus();
  d.querySelector('#presetName').dispatchEvent(new w.KeyboardEvent('keydown',{key:'t',bubbles:true,cancelable:true}));
  assert.equal(read('viewMonth'),month);
  w.closePresetDialog();w.openPicker();
  d.querySelector('#pickerModal').click();assert.equal(d.querySelector('#pickerModal').hidden,true);
  w.openSettings();d.querySelector('#settingsModal').click();
  assert.equal(d.querySelector('#settingsModal').hidden,true);assert.equal(read('draftDefs'),null);
});
test('Enter during Chinese input composition does not submit an unfinished preset name',t => {
  const {w,d,read}=boot(t);w.saveAsPreset();
  const input=d.querySelector('#presetName');input.value='还在输入';
  for (const options of [{isComposing:true},{keyCode:229}]) {
    const event=new w.KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true,...options});
    input.dispatchEvent(event);assert.equal(event.defaultPrevented,true);
  }
  assert.equal(read('PRESETS.length'),0);assert.equal(d.querySelector('#presetModal').hidden,false);
  const enter=new w.KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true});
  input.dispatchEvent(enter);assert.equal(enter.defaultPrevented,false);
});
test('preset naming supports reduced motion and theme changes while preserving the entered name',t => {
  const {w,d,read}=boot(t,{reducedMotion:true});
  w.nextMonth();const before=read('JSON.stringify(captureCurrentConfig())');
  w.saveAsPreset();d.querySelector('#presetName').value='双主题方案';
  for (const theme of ['dark','light']) {
    w.setTheme(theme);assert.equal(d.querySelector('#presetName').value,'双主题方案');
    assert.equal(d.querySelector('#presetModal').hidden,false);
  }
  w.submitPreset();
  const saved=read('JSON.stringify(Core.normalizeConfig(PRESETS[0]))');
  assert.equal(saved,before);
  assert.equal(d.querySelector('#presetModal').hidden,true);
});
test('dialogs have no entry animation and input pages wait for a deliberate input focus',t => {
  const {w,d}=boot(t,{reducedMotion:false}),animated=[];
  w.Element.prototype.animate = function() {
    animated.push(this);
    return {finished:Promise.resolve(),cancel(){}};
  };
  w.saveAsPreset();assert.equal(d.activeElement.id,'presetForm');
  assert.equal(d.documentElement.classList.contains('input-view-open'),true);
  assert.equal(d.querySelector('#presetModal').classList.contains('input-view'),true);
  assert.equal(d.body.style.overflow,'');
  d.querySelector('#presetName').focus();assert.equal(d.activeElement.id,'presetName');
  w.closePresetDialog();w.openSettings();
  assert.equal(d.querySelector('#settingsModal').classList.contains('input-view'),true);
  assert.equal(d.activeElement.getAttribute('role'),'dialog');
  assert.deepEqual(animated,[]);
  w.closeSettings();w.openPicker();
  assert.deepEqual(animated,[]);assert.equal(d.documentElement.classList.contains('input-view-open'),false);
});
test('opening a dialog cancels existing feedback and blocks scripted animations during input',t => {
  const {w,d,read}=boot(t,{reducedMotion:false});let started=0,cancelled=0;
  w.Element.prototype.animate = function() {
    started++;
    return {finished:Promise.resolve(),cancel(){cancelled++;}};
  };
  w.animateElement(d.querySelector('#companionCharacter'),[{opacity:0},{opacity:1}]);
  assert.equal(started,1);assert.equal(read('runningAnimations.size'),1);
  w.saveAsPreset();assert.equal(cancelled,1);assert.equal(read('runningAnimations.size'),0);
  w.animateElement(d.querySelector('#presetForm'),[{opacity:0},{opacity:1}]);assert.equal(started,1);
  w.closePresetDialog();w.animateElement(d.querySelector('#companionCharacter'),[{opacity:0},{opacity:1}]);
  assert.equal(started,2);
});
test('keyboard events schedule no layout work, style writes or refocusing in the static input page',t => {
  const {w,d}=boot(t),{viewport,pending,flush}=keyboardViewport(w);
  const overlay=d.querySelector('#presetModal'),form=d.querySelector('#presetForm'),input=d.querySelector('#presetName');
  const focus=input.focus.bind(input);let focusCalls=0,writes=0;
  input.focus = options => {focusCalls++;focus(options);};
  const setProperty=overlay.style.setProperty.bind(overlay.style);
  overlay.style.setProperty = (...args) => {writes++;setProperty(...args);};
  w.saveAsPreset();input.value='键盘动画期间的草稿';input.focus();
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
  const {w,d}=boot(t),{viewport,pending,flush}=keyboardViewport(w),overlay=d.querySelector('#presetModal');
  w.saveAsPreset();viewport.height=430;viewport.dispatchEvent(new w.Event('resize'));
  assert.equal(pending.size,0);w.closePresetDialog();
  assert.equal(overlay.classList.contains('input-view'),false);
  assert.equal(d.documentElement.classList.contains('input-view-open'),false);
  w.openPicker();
  viewport.dispatchEvent(new w.Event('resize'));viewport.dispatchEvent(new w.Event('scroll'));w.dispatchEvent(new w.Event('resize'));flush();
  assert.equal(pending.size,0);assert.equal(overlay.style.cssText,'');
  assert.equal(d.querySelector('#pickerModal').hidden,false);
});
test('the plain input page does not require or even read the Visual Viewport API',t => {
  const {w,d}=boot(t);
  Object.defineProperty(w,'visualViewport',{get(){throw new Error('No viewport access is needed');},configurable:true});
  w.saveAsPreset();const input=d.querySelector('#presetName');input.value='旧版内嵌浏览器';input.focus();
  w.dispatchEvent(new w.Event('resize'));
  assert.equal(input.value,'旧版内嵌浏览器');assert.equal(d.activeElement,input);
  w.submitPreset();assert.equal(d.querySelector('.preset-btn').textContent,'旧版内嵌浏览器');
});
test('rapid reopening and switching input pages retain only the active page and its draft',t => {
  const {w,d}=boot(t),{viewport,pending,flush}=keyboardViewport(w);
  w.saveAsPreset();viewport.height=440;viewport.dispatchEvent(new w.Event('resize'));
  w.closePresetDialog();viewport.height=700;w.saveAsPreset();
  assert.equal(pending.size,0);flush();
  assert.equal(d.querySelector('#presetModal').classList.contains('input-view'),true);
  viewport.height=400;viewport.dispatchEvent(new w.Event('resize'));w.openSettings();
  assert.equal(pending.size,0);assert.equal(d.querySelector('#presetModal').style.cssText,'');
  const overlay=d.querySelector('#settingsModal'),input=d.querySelector('#cyclePattern');
  assert.equal(overlay.classList.contains('input-view'),true);
  assert.equal(d.querySelector('#presetModal').classList.contains('input-view'),false);
  input.value='白班 休息';input.focus();viewport.height=300;viewport.dispatchEvent(new w.Event('resize'));flush();
  assert.equal(overlay.style.cssText,'');
  assert.equal(d.activeElement,input);assert.equal(input.value,'白班 休息');
  w.closeSettings();assert.equal(overlay.style.cssText,'');
});
test('input pauses the live timer and background writes, then catches up when closed',t => {
  const {w,d,read,setNow}=boot(t),before=d.querySelector('.main-container').innerHTML,clock=d.querySelector('#liveClock').textContent;
  assert.notEqual(read('liveTimer'),null);w.saveAsPreset();assert.equal(read('liveTimer'),null);
  d.querySelector('#presetName').value='不受秒表刷新影响';
  setNow('2026-10-16T12:00:15');w.updateLiveClockAndCountdown();
  assert.equal(d.querySelector('.main-container').innerHTML,before);
  assert.equal(d.querySelector('#liveClock').textContent,clock);
  assert.equal(d.querySelector('#presetName').value,'不受秒表刷新影响');
  w.closePresetDialog();assert.notEqual(read('liveTimer'),null);
  assert.notEqual(d.querySelector('#liveClock').textContent,clock);
});
test('midnight refresh is deferred while typing and applied after closing without losing the draft',t => {
  const {w,d,read,setNow}=boot(t,{now:'2026-10-16T23:59:59'});
  w.saveAsPreset();d.querySelector('#presetName').value='跨过午夜';
  setNow('2026-10-17T00:00:01');w.updateLiveClockAndCountdown();
  assert.equal(read('lastDayKey'),'2026-10-16');assert.equal(d.querySelector('#presetName').value,'跨过午夜');
  w.submitPreset();assert.equal(read('lastDayKey'),'2026-10-17');assert.equal(d.querySelector('.preset-btn').textContent,'跨过午夜');
});
test('input page cancellation restores the calendar scroll position and opening control',t => {
  const {w,d}=boot(t),opener=d.querySelector('[onclick="saveAsPreset()"]'),scrolls=[];
  w.scrollX=0;w.scrollY=640;
  w.scrollTo = (x,y) => {scrolls.push([x,y]);w.scrollX=x;w.scrollY=y;};
  opener.focus();w.saveAsPreset();assert.deepEqual(scrolls,[[0,0]]);
  w.scrollY=60;w.closePresetDialog();assert.deepEqual(scrolls,[[0,0],[0,640]]);
  assert.equal(d.activeElement,opener);assert.equal(d.querySelector('.main-container').inert,false);
  assert.equal(d.documentElement.classList.contains('input-view-open'),false);
});
test('repeated input page opening and inactive closes do not create duplicate live timers',t => {
  const {w,read}=boot(t),timers=new Set(),clear=w.clearInterval.bind(w);let next=100;
  w.setInterval = () => {const id=next++;timers.add(id);return id;};
  w.clearInterval = id => {timers.delete(id);clear(id);};
  for(let round=0;round<5;round++) {
    w.saveAsPreset();assert.equal(timers.size,0);assert.equal(read('liveTimer'),null);
    w.closePresetDialog();assert.equal(timers.size,1);
    w.closeSettings();assert.equal(timers.size,1);
  }
});
test('all shift types are included in monthly statistics, including the fourth and later shifts',t => {
  const {w,d}=boot(t,{storage:{'qingzheng-cycle':'["白班","夜班","中班","休息"]'}});
  assert.equal(d.querySelectorAll('#statsContainer .stat-card').length,4);
  d.querySelector('#themeToggle').click(); assert.equal(d.documentElement.dataset.theme,'dark');
  assert.equal(w.localStorage.getItem('qingzheng-theme'),'dark');
});
test('solar terms use the corrected annual table and never fall back to 2026 for unknown years',t => {
  const {read}=boot(t);
  assert.equal(read('getSolarTerm(new Date(2026,2,5))'),'惊蛰');
  assert.equal(read('getSolarTerm(new Date(2026,2,6))'),null);
  assert.equal(read('getSolarTerm(new Date(2026,9,23))'),'霜降');
  assert.equal(read('getSolarTerm(new Date(2040,9,23))'),null);
});
test('midnight and the end of an overnight shift update the date, cycle and visible calendar',t => {
  const {w,d,setNow}=boot(t,{now:'2026-04-19T23:59:59'});
  assert.equal(d.querySelector('#todayCycle').textContent,'2');
  setNow('2026-04-20T00:00:00'); w.updateLiveClockAndCountdown();
  assert.ok(d.querySelector('#todayDate').textContent.includes('4月20日'));
  assert.equal(d.querySelector('.day-cell.today').dataset.date,'2026-04-20');
  assert.equal(d.querySelector('#todayCycle').textContent,'2');
  setNow('2026-04-20T08:00:00'); w.updateLiveClockAndCountdown();
  assert.equal(d.querySelector('#todayCycle').textContent,'3');
  assert.ok(d.querySelector('#todayShift').textContent.includes('休息'));
});
test('a write failure keeps session changes usable and displays the persistence limitation',t => {
  const {w,d,read}=boot(t,{blocked:true});
  w.openSettings(); d.querySelector('#cyclePattern').value='休息'; w.saveSettings();
  assert.equal(read('CYCLE.length'),1);
  assert.equal(d.querySelector('#settingsModal').hidden,true);
  assert.equal(d.querySelector('#storageNotice').hidden,false);
  assert.ok(d.querySelector('#todayShift').textContent.includes('休息'));
});
test('removing an unused default definition survives saving and reload',t => {
  const {w}=boot(t); w.openSettings(); w.removeShiftDef('中班'); w.saveSettings();
  const saved=w.localStorage.getItem('qingzheng-config');
  const restored=boot(t,{storage:{'qingzheng-config':saved}});
  assert.equal(restored.read('Object.hasOwn(SHIFT_DEFS,"中班")'),false);
});
test('upcoming dates cross month boundaries and open the correct calendar day',t => {
  const {w,d,read}=boot(t,{now:'2026-10-29T12:00:00'});
  const days=[...d.querySelectorAll('#upcomingList button')];
  assert.equal(days.length,7);
  assert.equal(days[0].dataset.date,'2026-10-29');
  assert.equal(days[6].dataset.date,'2026-11-04');
  days[6].click();
  assert.equal(read('viewMonth'),10);
  assert.equal(d.querySelector('#daysGrid .selected').dataset.date,'2026-11-04');
  assert.equal(d.querySelector('#upcomingPanel').hidden,true);
  assert.equal(d.querySelector('#detailPanel').classList.contains('show'),true);
  w.closeDetail();
  assert.equal(d.querySelector('#upcomingPanel').hidden,false);
  assert.equal(d.querySelectorAll('#upcomingList button').length,7);
});
test('date inspection swaps the sidebar without recreating the month grid',t => {
  const {w,d}=boot(t);
  const first=d.querySelector('#daysGrid button');
  first.click();
  assert.equal(first.isConnected,true);
  assert.equal(first.getAttribute('aria-pressed'),'true');
  assert.equal(d.querySelector('#detailHeading').textContent,'10月1日 · 周四');
  w.closeDetail();
  assert.equal(first.isConnected,true);
  assert.equal(first.getAttribute('aria-pressed'),'false');
});
test('automatic themes follow 07:00 and 19:00 while respecting manual choices',t => {
  const {w,d,setNow}=boot(t,{now:'2026-10-16T06:59:59'});
  assert.equal(d.documentElement.dataset.theme,'dark');
  assert.equal(d.querySelector('#themeMood').textContent,'蓝夜');
  assert.equal(d.querySelector('#themeAuto').getAttribute('aria-pressed'),'true');
  setNow('2026-10-16T07:00:00');w.updateLiveClockAndCountdown();
  assert.equal(d.documentElement.dataset.theme,'light');
  setNow('2026-10-16T19:00:00');w.updateLiveClockAndCountdown();
  assert.equal(d.documentElement.dataset.theme,'dark');
  d.querySelector('#themeToggle').click();
  assert.equal(d.documentElement.dataset.theme,'light');
  assert.equal(w.localStorage.getItem('qingzheng-theme-mode'),'manual');
  setNow('2026-10-16T20:00:00');w.updateLiveClockAndCountdown();
  assert.equal(d.documentElement.dataset.theme,'light');
  w.enableAutomaticTheme();
  assert.equal(d.documentElement.dataset.theme,'dark');
  assert.equal(w.localStorage.getItem('qingzheng-theme-mode'),'auto');
});
test('saved automatic preference takes precedence over an old manual theme value',t => {
  const {d}=boot(t,{now:'2026-10-16T22:00:00',storage:{'qingzheng-theme':'light','qingzheng-theme-mode':'auto'}});
  assert.equal(d.documentElement.dataset.theme,'dark');
  const old=boot(t,{storage:{'qingzheng-theme':'dark'}});
  assert.equal(old.d.documentElement.dataset.theme,'dark');
  assert.equal(old.d.querySelector('#themeAuto').getAttribute('aria-pressed'),'false');
});
test('reduced motion keeps navigation, dialogs, details and year expansion fully usable',t => {
  const {w,d}=boot(t,{reducedMotion:true});
  w.nextMonth();w.prevMonth();w.showDetail(new w.Date());w.closeDetail();
  w.openSettings();w.closeSettings();w.openYearOverview();
  assert.equal(d.querySelector('#yearOverview').open,true);
  assert.equal(d.querySelectorAll('#daysGrid button').length,31);
});
test('festival decorations appear only on the real festival date and open an identified detail',t => {
  const cases = [
    ['2026-01-01','元旦'],['2026-02-16','除夕'],['2026-02-17','春节'],
    ['2026-03-03','元宵节'],['2026-04-05','清明'],['2026-05-01','劳动节'],
    ['2026-06-19','端午节'],['2026-09-25','中秋节'],['2026-10-01','国庆节'],['2026-10-18','重阳节']
  ];
  const {w,d} = boot(t);
  for (const [date,name] of cases) {
    const [year,month,day] = date.split('-').map(Number);
    w.navigateMonth(year,month-1);
    const cell = d.querySelector(`[data-date="${date}"].day-cell`);
    assert.equal(cell.querySelector('.holiday-badge').textContent,name,date);
    assert.ok(cell.getAttribute('aria-label').includes(name),date);
    for (const use of cell.querySelectorAll('.festival-art use')) assert.ok(d.querySelector(use.getAttribute('href')),date);
    cell.click();
    assert.equal(d.querySelector('#detailFestival').hidden,false,date);
    assert.equal(d.querySelector('#detailFestival strong').textContent,name,date);
    const next = new w.Date(year,month-1,day+1);
    w.showDetail(next);
    if (!w.getHoliday(next)) {
      assert.equal(d.querySelector('#detailFestival').hidden,true,date);
      assert.equal(d.querySelector('#detailFestival').childElementCount,0,date);
    }
    w.closeDetail();
    for (const ordinary of d.querySelectorAll('.day-cell:not(.is-festival)')) {
      assert.equal(ordinary.querySelector('.festival-art,.holiday-badge'),null);
    }
  }
});
test('today festival follows the civil date while an overnight shift keeps its original cycle day',t => {
  const {w,d,setNow} = boot(t,{now:'2026-09-24T23:59:59',storage:{'qingzheng-cycle':'["夜班"]'}});
  assert.equal(d.querySelector('#todayFestival').hidden,true);
  setNow('2026-09-25T00:00:00'); w.updateLiveClockAndCountdown();
  assert.equal(d.querySelector('#todayFestival').hidden,false);
  assert.equal(d.querySelector('#todayFestival strong').textContent,'中秋节');
  assert.ok(d.querySelector('#todayShiftTime').textContent.includes('昨日班次'));
  assert.equal(d.querySelector('.day-cell.today').dataset.date,'2026-09-25');
  setNow('2026-09-26T00:00:00'); w.updateLiveClockAndCountdown();
  assert.equal(d.querySelector('#todayFestival').hidden,true);
  assert.equal(d.querySelector('#todayFestival').childElementCount,0);
  assert.equal(d.querySelector('.today-card').classList.contains('has-festival'),false);
});
test('coinciding festivals preserve both names and illustrations when switching themes',t => {
  const {w,d} = boot(t,{now:'2020-10-01T12:00:00'});
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
    const {d,w}=boot(t,{now:`${now}T12:00:00`});
    const row=d.querySelector(`#upcomingList [data-date="${date}"]`);
    assert.equal(d.querySelectorAll('#upcomingList button').length,7);
    assert.equal(row.querySelector('.upcoming-festival').textContent,festival,date);
    assert.ok(row.getAttribute('aria-label').includes(festival),date);
    assert.ok(row.querySelector('.upcoming-shift').textContent,date);
    for (const use of row.querySelectorAll('.festival-art use')) assert.ok(d.querySelector(use.getAttribute('href')),date);
    for (const other of d.querySelectorAll('#upcomingList button')) {
      const ordinary=w.ScheduleCore.parseDate(other.dataset.date);
      if (!w.getHoliday(ordinary)) assert.equal(other.querySelector('.upcoming-festival'),null,other.dataset.date);
    }
    row.click();
    assert.equal(d.querySelector('#detailFestival strong').textContent,festival,date);
    assert.equal(d.querySelector('#daysGrid .selected').dataset.date,date);
  }
});
test('the coming week updates its festival rows at midnight and across the year boundary',t => {
  const {w,d,setNow}=boot(t,{now:'2026-12-31T23:59:59'});
  assert.equal(d.querySelector('#upcomingList .upcoming-festival').textContent,'元旦');
  setNow('2027-01-01T00:00:00'); w.updateLiveClockAndCountdown();
  const today=d.querySelector('#upcomingList .is-today');
  assert.equal(today.dataset.date,'2027-01-01');
  assert.equal(today.querySelector('.upcoming-festival').textContent,'元旦');
  today.click();
  assert.equal(d.querySelector('.day-cell.selected').dataset.date,'2027-01-01');
  setNow('2027-01-02T00:00:00'); w.updateLiveClockAndCountdown();
  assert.equal(d.querySelector('#upcomingList .upcoming-festival'),null);
});
test('the coming week keeps both coinciding festivals and the shared illustration palette in either theme',t => {
  const {w,d}=boot(t,{now:'2020-09-29T12:00:00'});
  const row=d.querySelector('#upcomingList [data-date="2020-10-01"]');
  assert.equal(row.querySelector('.upcoming-festival').textContent,'国庆节·中秋节');
  assert.equal(row.querySelectorAll('.festival-art svg').length,2);
  const before=row.querySelector('.upcoming-festival').innerHTML,palette=row.dataset.festivalPalette;
  d.querySelector('#themeToggle').click();
  assert.equal(row.querySelector('.upcoming-festival').innerHTML,before);
  assert.equal(row.dataset.festivalPalette,palette);
  row.click();
  assert.equal(d.querySelector('#detailFestival strong').textContent,'国庆节·中秋节');
  w.closeDetail();
  assert.equal(d.querySelector('#upcomingPanel').hidden,false);
});
test('companions use the matching theme artwork and reset their copy on theme changes',t => {
  const {w,d,setNow}=boot(t,{now:'2026-10-16T06:59:59'});
  assert.equal(d.querySelector('#companion').hidden,false);
  assert.equal(d.querySelector('#companionDay').hidden,true);
  assert.equal(d.querySelector('#companionNight').hidden,false);
  assert.equal(d.querySelector('#companionName').textContent,'大肥鱼');
  d.querySelector('#companionCharacter').click();
  assert.equal(d.querySelector('#companion').dataset.message,'quip');
  setNow('2026-10-16T07:00:00');w.updateLiveClockAndCountdown();
  assert.equal(d.querySelector('#companionDay').hidden,false);
  assert.equal(d.querySelector('#companionNight').hidden,true);
  assert.equal(d.querySelector('#companionName').textContent,'大肥龙');
  assert.equal(d.querySelector('#companion').dataset.message,'status');
  assert.ok(d.querySelector('#companionCharacter').getAttribute('aria-label').includes('大肥龙'));
  d.querySelector('#themeToggle').click();
  assert.equal(d.querySelector('#companionName').textContent,'大肥鱼');
});
test('companion status follows the exact start and end of a shift, highlighting only the time',t => {
  const {w,d,setNow}=boot(t,{now:'2026-04-18T07:59:30'});
  const message=d.querySelector('#companionMessage');
  assert.ok(message.textContent.includes('距离下次上班'));
  assert.equal(message.querySelector('strong').textContent,'不到1分钟');
  setNow('2026-04-18T08:00:00');w.updateLiveClockAndCountdown();
  assert.ok(message.textContent.includes('离下班还有'));
  assert.equal(message.querySelector('.companion-time').textContent,'12小时');
  setNow('2026-04-18T19:59:59');w.updateLiveClockAndCountdown();
  assert.equal(message.querySelector('strong').textContent,'不到1分钟');
  setNow('2026-04-18T20:00:00');w.updateLiveClockAndCountdown();
  assert.ok(message.textContent.includes('距离下次上班'));
  assert.equal(message.querySelector('strong').textContent,'1天');
  assert.equal(d.querySelector('#companionAnnouncement').textContent,'');
});
test('overnight companion copy remains on the previous work shift until its real end',t => {
  const {w,d,setNow}=boot(t,{now:'2026-04-19T23:59:59',storage:{'qingzheng-theme':'light'}});
  const message=d.querySelector('#companionMessage');
  assert.ok(message.textContent.includes('离下班还有'));
  setNow('2026-04-20T00:00:00');w.updateLiveClockAndCountdown();
  assert.ok(message.textContent.includes('离下班还有'));
  assert.equal(message.querySelector('strong').textContent,'8小时');
  setNow('2026-04-20T08:00:00');w.updateLiveClockAndCountdown();
  assert.ok(message.textContent.includes('距离下次上班'));
  assert.equal(message.querySelector('strong').textContent,'1天');
});
test('clicks alternate a shuffled quip bag with one schedule fact without immediate repetition',t => {
  const {d}=boot(t);
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
  const {w,d,setNow}=boot(t,{now:'2026-04-18T09:00:00'});
  const message=d.querySelector('#companionMessage');
  const original=message.firstChild;
  setNow('2026-04-18T09:00:01');w.updateLiveClockAndCountdown();
  assert.notEqual(message.firstChild,original);
  const sameMinute=message.firstChild;
  setNow('2026-04-18T09:00:02');w.updateLiveClockAndCountdown();
  assert.equal(message.firstChild,sameMinute);
  d.querySelector('#companionCharacter').click();
  const quip=message.textContent,announcement=d.querySelector('#companionAnnouncement').textContent;
  setNow('2026-04-18T10:05:00');w.updateLiveClockAndCountdown();
  assert.equal(message.textContent,quip);
  assert.equal(d.querySelector('#companionAnnouncement').textContent,announcement);
});
test('collapsing hides only the bubble; character activation restores it and refreshes copy',t => {
  const {d}=boot(t);
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
  const {w,d,read}=boot(t,{now:'2026-10-16T12:00:00'});
  w.commitConfig({anchor:'2026-10-16',cycle:['休息'],shiftDefs:{}});
  assert.ok(d.querySelector('#companionMessage').textContent.includes('没有工作班'));
  assert.equal(d.querySelector('#companionMessage strong'),null);
  w.commitConfig({anchor:'2026-10-16',cycle:[...Array(40).fill('休息'),'白班'],shiftDefs:{}});
  assert.equal(d.querySelector('#companionMessage strong').textContent,'39天21小时');
  w.commitConfig({anchor:'2026-10-16',cycle:['白班'],shiftDefs:{}});
  assert.equal(read('companionFacts(new Date(),getEffectiveShiftInfo(),getNextWorkCountdown(new Date())).some(fact=>fact.id === "rest")'),false);
  assert.ok(d.querySelector('#companionMessage').textContent.includes('下班'));
});
test('companion tomorrow facts use real civil dates at midnight and treat custom names as text',t => {
  const name='<img/src=x/onerror=alert(1)>';
  const {w,d,setNow}=boot(t,{now:'2026-10-16T23:59:59',storage:{'qingzheng-theme':'light'}});
  w.commitConfig({anchor:'2026-10-16',cycle:['休息',name,'白班'],shiftDefs:{}});
  d.querySelector('#companionCharacter').click();d.querySelector('#companionCharacter').click();
  const message=d.querySelector('#companionMessage');
  assert.ok(message.textContent.includes(name));
  assert.equal(message.querySelector('strong').textContent,'09:00');
  assert.equal(message.querySelector('img'),null);
  setNow('2026-10-17T00:00:00');w.updateLiveClockAndCountdown();
  assert.ok(message.textContent.includes('白班'));
  assert.equal(message.textContent.includes(name),false);
  assert.equal(d.querySelectorAll('img').length,initialImageCount);
});
test('the last overnight shift is not mistaken for a full rest day by the companion',t => {
  const {w,d,setNow}=boot(t,{now:'2026-04-20T07:00:00',storage:{'qingzheng-theme':'light'}});
  for (let i=0;i<4;i++) d.querySelector('#companionCharacter').click();
  assert.equal(d.querySelector('#companion').dataset.message,'rest');
  assert.ok(d.querySelector('#companionMessage').textContent.includes('要等这班结束'));
  setNow('2026-04-20T08:00:00');w.updateLiveClockAndCountdown();
  assert.ok(d.querySelector('#companionMessage').textContent.includes('今天的排班是休息'));
  assert.equal(d.querySelector('#companionMessage strong'),null);
});
test('reduced-motion users can refresh and collapse companions without scripted animation',t => {
  const {d}=boot(t,{reducedMotion:true});
  for (let i=0;i<4;i++) d.querySelector('#companionCharacter').click();
  d.querySelector('#companionToggle').click();
  d.querySelector('#companionCharacter').click();
  assert.equal(d.querySelector('#companionBubble').hidden,false);
  assert.ok(d.querySelector('#companionMessage').textContent);
});
test('character taps produce a finite bounce, sparkles and copy transition',t => {
  const {w,d}=boot(t,{reducedMotion:false});
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
