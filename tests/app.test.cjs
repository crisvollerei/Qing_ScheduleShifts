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
  const {w,d,read}=boot(t); w.prompt=()=> '测试方案'; w.saveAsPreset();
  assert.equal(d.querySelectorAll('.preset-group').length,1);
  assert.equal(d.querySelectorAll('.preset-btn button').length,0);
  const id=read('PRESETS[0].id'), before=read('JSON.stringify(captureCurrentConfig())');
  w.openSettings(); d.querySelector('#cyclePattern').value='休息'; w.saveSettings();
  w.applyPreset(id); assert.equal(read('JSON.stringify(captureCurrentConfig())'),before);
  w.deletePreset(id); assert.equal(read('CYCLE.length'),8); assert.equal(d.querySelectorAll('.preset-group').length,0);
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
