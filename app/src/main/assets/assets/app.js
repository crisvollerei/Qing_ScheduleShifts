'use strict';

const Core = ScheduleCore;
Core.setLunarCalendar(CalendarData);
const $ = id => document.getElementById(id);
const CSS_PRESETS = ['day', 'night', 'rest', 'noon', 'morning', 'evening'];
const STYLE_NAMES = { day: '白班蓝', night: '夜班紫', rest: '休息绿', noon: '午后金', morning: '清晨青', evening: '晚霞粉' };
const PRESET_ICONS = { day: '☀️', night: '🌙', rest: '🌿', noon: '🌤️', morning: '🌅', evening: '🌆' };
const DEFAULT_SHIFT_DEFS = {
  '白班': { emoji: '☀️', cssClass: 'day', startTime: '08:00', endTime: '20:00', isWork: true },
  '中班': { emoji: '🌤️', cssClass: 'noon', startTime: '14:00', endTime: '22:00', isWork: true },
  '夜班': { emoji: '🌙', cssClass: 'night', startTime: '20:00', endTime: '08:00', crossDay: true, isWork: true },
  '休息': { emoji: '🌿', cssClass: 'rest', startTime: null, endTime: null, isWork: false }
};
const DEFAULT_CYCLE = ['白班', '夜班', '休息', '白班', '夜班', '休息', '休息', '休息'];
const FESTIVAL_ART = Object.freeze({
  '元旦': {symbol:'new-year',palette:'gold',message:'新的一年，也把日常过得闪闪发光。'},
  '除夕': {symbol:'eve',palette:'coral',message:'一餐团圆，辞旧迎新。'},
  '春节': {symbol:'spring',palette:'coral',message:'灯火照新岁，愿诸事顺遂。'},
  '元宵节': {symbol:'lantern',palette:'coral',message:'花灯映圆月，日子暖而甜。'},
  '清明': {symbol:'qingming',palette:'jade',message:'风起清明，寄一份思念，赴一场春光。'},
  '劳动节': {symbol:'labor',palette:'blue',message:'每一份认真，都值得被看见。'},
  '端午节': {symbol:'dragon-boat',palette:'jade',message:'粽叶飘香，愿你端午安康。'},
  '中秋节': {symbol:'moon',palette:'gold',message:'月满人团圆，所念皆安好。'},
  '国庆节': {symbol:'national',palette:'coral',message:'山河锦绣，日常也有好风景。'},
  '重阳节': {symbol:'chrysanthemum',palette:'gold',message:'菊香秋意长，愿岁岁安康。'}
});
let storageFailed = false;
let recoveredData = false;
let toastTimer;

function storageGet(key) {
  try { return localStorage.getItem(key); }
  catch (_) { storageFailed = true; return null; }
}
function storageSet(key, value) {
  try { localStorage.setItem(key, value); return true; }
  catch (_) { storageFailed = true; updateStorageNotice(); return false; }
}
function readJSON(key) {
  const raw = storageGet(key);
  if (raw === null) return null;
  try { return JSON.parse(raw); }
  catch (_) { recoveredData = true; return null; }
}
// ===== Android 桥：与桌面小部件共用的设置存储 =====
// 网页把排班写入 SharedPreferences 供小部件读取；这里补上“反向读取”，
// 使两边的数据源保持一致，而不是各存一份、各显示一份。
const AndroidBridge = (typeof Android !== 'undefined' && Android && typeof Android.getSettings === 'function') ? Android : null;

function pushBridgeConfig(config) {
  if (!AndroidBridge || typeof AndroidBridge.saveSettings !== 'function') return false;
  try {
    AndroidBridge.saveSettings(JSON.stringify(config.cycle), config.anchor, JSON.stringify(config.shiftDefs));
    return true;
  } catch (_) {
    return false;
  }
}

function readBridgeConfig() {
  if (!AndroidBridge) return null;
  let raw;
  try { raw = AndroidBridge.getSettings(); }
  catch (_) { return null; }
  if (typeof raw !== 'string' || !raw) return null;
  let data;
  try { data = JSON.parse(raw); }
  catch (_) { return null; }
  if (!data || typeof data !== 'object') return null;
  // 三项都为空表示原生侧从未保存过排班。
  if (!data.cycle || !data.anchorDate) return null;
  let cycle, shiftDefs;
  try { cycle = JSON.parse(data.cycle); }
  catch (_) { return null; }
  try { shiftDefs = data.shiftDefs ? JSON.parse(data.shiftDefs) : {}; }
  catch (_) { shiftDefs = {}; }
  return Core.normalizeConfig({
    anchor: data.anchorDate,
    cycle,
    shiftDefs: shiftDefs && typeof shiftDefs === 'object' && !Array.isArray(shiftDefs) ? shiftDefs : {}
  });
}

function updateStorageNotice() {
  const notice = $('storageNotice');
  notice.hidden = !storageFailed && !recoveredData;
  notice.textContent = storageFailed ? '浏览器暂时无法保存数据。你仍可使用排班表，本次修改在关闭页面后可能丢失。' : '部分本地配置无法读取，已使用可用配置或默认值。原始数据仍保留在浏览器中，请检查排班设置。';
}
function toast(message) {
  clearTimeout(toastTimer);
  $('toast').textContent = message;
  $('toast').hidden = false;
  toastTimer = setTimeout(() => { $('toast').hidden = true; }, 3200);
}
function loadConfig() {
  const current = readJSON('qingzheng-config');
  const normalized = Core.normalizeConfig(current);
  if (normalized) return normalized;
  if (current !== null) recoveredData = true;
  const legacyCycle = readJSON('qingzheng-cycle');
  const legacyAnchor = storageGet('qingzheng-anchor');
  const legacyDefs = readJSON('qingzheng-shift-defs');
  if (legacyCycle !== null && !Core.validCycle(legacyCycle)) recoveredData = true;
  if (legacyAnchor !== null && !Core.parseDate(legacyAnchor)) recoveredData = true;
  const legacy = Core.normalizeConfig({
    anchor: Core.parseDate(legacyAnchor) ? legacyAnchor : '2026-04-18',
    cycle: Core.validCycle(legacyCycle) ? legacyCycle : DEFAULT_CYCLE,
    shiftDefs: legacyDefs && typeof legacyDefs === 'object' && !Array.isArray(legacyDefs) ? legacyDefs : DEFAULT_SHIFT_DEFS
  });
  // 网页侧没有任何可用配置时，采用原生侧（小部件）已保存的排班，
  // 避免 WebView 数据被清除后小部件与页面显示两套班表。
  const hasLocalConfig = normalized || legacyCycle !== null || legacyAnchor !== null;
  if (!hasLocalConfig) {
    const bridged = readBridgeConfig();
    if (bridged) return bridged;
  }
  return legacy;
}
const initialConfig = loadConfig();
let CYCLE = initialConfig.cycle;
let ANCHOR_DATE = Core.parseDate(initialConfig.anchor);
let SHIFT_DEFS = initialConfig.shiftDefs;
let PRESETS = [];
let activePresetId = null;
let viewYear, viewMonth, selectedDate = null;
let draftDefs = null;
let pickerYear;
let activeDialog = null;
let dialogTrigger = null;
let lastDayKey = '';
let lastTodayShiftKey = '';
let lastRenderedMonth = null;
let automaticTheme = false;
let lastTodayFestival = null;
const runningAnimations = new Set();
const elementAnimations = new WeakMap();
const motionPreference = typeof window.matchMedia === 'function' ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;

function motionAllowed() { return !!motionPreference && !motionPreference.matches; }
function animateElement(element, frames, options = {}) {
  if (!element || typeof element.animate !== 'function' || !motionAllowed()) return;
  elementAnimations.get(element)?.cancel();
  const animation = element.animate(frames,{duration:260,easing:'cubic-bezier(.16,1,.3,1)',...options});
  elementAnimations.set(element,animation);
  runningAnimations.add(animation);
  animation.finished.catch(() => {}).finally(() => runningAnimations.delete(animation));
}
motionPreference?.addEventListener?.('change',event => {
  if (event.matches) for (const animation of runningAnimations) animation.cancel();
});
function reveal(element) {
  animateElement(element,[{opacity:0,transform:'translateY(9px)'},{opacity:1,transform:'translateY(0)'}],{duration:330});
}

function mk(tag, options = {}) {
  const element = document.createElement(tag);
  if (options.cls) element.className = options.cls;
  if (options.text !== undefined) element.textContent = options.text;
  if (options.onclick) element.addEventListener('click', options.onclick);
  if (tag === 'button') element.type = 'button';
  return element;
}
function getShiftDef(name) { return Core.own(SHIFT_DEFS, name) ? SHIFT_DEFS[name] : Core.defaultDef(name); }
function isWorkShift(name) { return getShiftDef(name).isWork; }
function isRestShift(name) { return !isWorkShift(name); }
function isCrossDayShift(name) { return Core.isCrossDay(getShiftDef(name)); }
function getShiftDurationHours(name) { return Core.duration(getShiftDef(name)); }
function getCycleIndex(date) { return Core.cycleIndex(date, ANCHOR_DATE, CYCLE); }
function getShift(date) { return CYCLE[getCycleIndex(date)]; }
function getShiftTimes(date) { return Core.shiftTimes(date, getShiftDef(getShift(date))); }
function getLunarDate(date) { return Core.lunarDate(date); }
function formatDateISO(date) { return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}`; }
function formatDate(date) { return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日`; }
function getWeekdayCn(date) { return ['周日','周一','周二','周三','周四','周五','周六'][date.getDay()]; }
function isSameDay(a, b) { return Core.dayNumber(a) === Core.dayNumber(b); }
function addDays(date, amount) { const result = new Date(date); result.setDate(result.getDate() + amount); return result; }
function getSolarTerm(date) {
  const dates = CalendarData.dates[date.getFullYear()];
  if (!dates) return null;
  const index = dates.indexOf(formatDateISO(date).slice(5));
  return index >= 0 ? CalendarData.terms[index] : null;
}
function getHoliday(date) { return Core.festival(date, getSolarTerm(date)); }
function festivalParts(festival) {
  return (festival || '').split('·').filter(name => Core.own(FESTIVAL_ART,name));
}
function makeFestivalArt(festival) {
  const ornament = mk('span',{cls:'festival-art'});
  ornament.setAttribute('aria-hidden','true');
  for (const name of festivalParts(festival)) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg','svg');
    const use = document.createElementNS('http://www.w3.org/2000/svg','use');
    svg.setAttribute('viewBox','0 0 80 80');
    svg.setAttribute('focusable','false');
    use.setAttribute('href',`#festival-${FESTIVAL_ART[name].symbol}`);
    svg.append(use); ornament.append(svg);
  }
  return ornament;
}
function renderFestivalNotice(container, festival) {
  const parts = festivalParts(festival);
  container.hidden = !parts.length;
  container.replaceChildren();
  if (!parts.length) { delete container.dataset.festivalPalette; return; }
  container.dataset.festivalPalette = FESTIVAL_ART[parts[0]].palette;
  const copy = mk('div',{cls:'festival-copy'});
  copy.append(mk('strong',{text:festival}),mk('p',{text:parts.map(name => FESTIVAL_ART[name].message).join(' ')}));
  container.append(makeFestivalArt(festival),copy);
}
function shiftTimeText(name) {
  const def = getShiftDef(name);
  return def.isWork ? `${def.startTime} — ${isCrossDayShift(name) ? '次日 ' : ''}${def.endTime}` : '不赶时间，好好休息';
}
function getEffectiveShiftInfo(now = new Date()) {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const yesterday = addDays(today, -1);
  const previous = getShiftTimes(yesterday);
  if (previous && now >= previous.start && now < previous.end) return { shift: getShift(yesterday), date: yesterday, isCrossDay: true, ...previous };
  const times = getShiftTimes(today);
  return { shift: getShift(today), date: today, isCrossDay: false, start: times?.start ?? null, end: times?.end ?? null };
}
function formatCountdown(ms) {
  const seconds = Math.max(0, Math.floor(ms / 1000));
  const hours = Math.floor(seconds / 3600);
  const days = Math.floor(hours / 24);
  const clock = `${String(hours % 24).padStart(2,'0')}:${String(Math.floor(seconds / 60) % 60).padStart(2,'0')}:${String(seconds % 60).padStart(2,'0')}`;
  return days ? `${days}天 ${clock}` : clock;
}
function getNextWorkCountdown(now) {
  for (let i = 0; i <= CYCLE.length; i++) {
    const times = getShiftTimes(addDays(now, i));
    if (times && times.start > now) return times.start - now;
  }
  return null;
}
function getDaysToWeekend(date) {
  for (let i = 0; i < CYCLE.length; i++) if (isRestShift(getShift(addDays(date, i)))) return i;
  return -1;
}
function getDaysToNextRest() { return getDaysToWeekend(new Date()); }
function getUniqueShiftsInCycle() { return [...new Set(CYCLE)]; }
function getWorkShifts() { return getUniqueShiftsInCycle().filter(isWorkShift); }
function calculateNextShifts(now = new Date()) {
  const result = Object.create(null);
  for (const name of getWorkShifts()) result[name] = -1;
  for (let i = 1; i <= CYCLE.length; i++) {
    const shift = getShift(addDays(now, i));
    if (Core.own(result, shift) && result[shift] === -1) result[shift] = i;
  }
  return result;
}
function calculateMonthlyStats(year, month) {
  const counts = Object.create(null);
  for (let d = 1; d <= new Date(year, month + 1, 0).getDate(); d++) {
    const name = getShift(new Date(year, month, d));
    counts[name] = (counts[name] || 0) + 1;
  }
  return counts;
}
function calculateWorkHours(year, month) {
  const total = Object.entries(calculateMonthlyStats(year, month)).reduce((sum, [name, count]) => sum + count * getShiftDurationHours(name), 0);
  return Math.round(total * 100) / 100;
}
function getConsecutiveShiftDays(date) {
  const name = getShift(date);
  if (CYCLE.every(shift => shift === name)) return null;
  let count = 1;
  while (count < CYCLE.length && getShift(addDays(date, -count)) === name) count++;
  return count;
}

// Keep the effective shift, its anchor day and the progress bar consistent at midnight.
function updateTodayCardDisplay(info, now = new Date()) {
  const def = getShiftDef(info.shift);
  const shiftKey = JSON.stringify([info.shift,def.emoji,def.cssClass]);
  if (lastTodayShiftKey !== shiftKey) {
    $('todayShift').replaceChildren(mk('span', { cls:'today-shift-emoji', text:def.emoji }), document.createTextNode(info.shift));
    $('todayShift').className = `today-shift ${def.cssClass}`;
    lastTodayShiftKey = shiftKey;
    reveal($('todayShift'));
  }
  const lunar = getLunarDate(now);
  $('todayDate').textContent = `${now.getMonth() + 1}月${now.getDate()}日 ${getWeekdayCn(now)}${lunar.day ? ` · 农历${lunar.monthStr}${lunar.dayStr}` : ''}`;
  const festival = getHoliday(now);
  if (festival !== lastTodayFestival) {
    renderFestivalNotice($('todayFestival'),festival);
    document.querySelector('.today-card').classList.toggle('has-festival',!!festival);
    lastTodayFestival = festival;
  }
  $('todayShiftTime').textContent = `${info.isCrossDay ? '昨日班次 · ' : ''}${shiftTimeText(info.shift)}`;
  const position = getCycleIndex(info.date) + 1;
  $('todayCycle').textContent = position;
  $('cycleLength').textContent = CYCLE.length;
  const progress = Math.round(position / CYCLE.length * 100);
  $('progressBar').style.width = `${progress}%`;
  $('cycleProgress').setAttribute('aria-valuenow', progress);
  $('cycleProgress').setAttribute('aria-valuetext', `周期第${position}天，共${CYCLE.length}天`);
  const days = getDaysToWeekend(now);
  $('restCountdown').textContent = days === 0 ? (info.isCrossDay ? '本班结束后' : '就是今天') : days < 0 ? '周期内无休息日' : `${days} 天后`;
}
function updateLiveClockAndCountdown() {
  const now = new Date();
  if (automaticTheme) {
    const theme = themeForTime(now);
    if (document.documentElement.dataset.theme !== theme) setTheme(theme,false);
  }
  const dayKey = formatDateISO(now);
  if (lastDayKey && lastDayKey !== dayKey) {
    lastDayKey = dayKey;
    render();
    return;
  }
  lastDayKey = dayKey;
  $('liveClock').textContent = now.toLocaleTimeString('zh-CN', {hour12:false});
  $('liveClock').dateTime = now.toISOString();
  const info = getEffectiveShiftInfo(now);
  const next = getNextWorkCountdown(now);
  $('countdownToWork').textContent = info.start && now >= info.start && now < info.end ? '正在上班' : next === null ? '暂无工作班' : formatCountdown(next);
  $('countdownToOff').textContent = !info.start ? '今日休息' : now < info.end ? formatCountdown(info.end - now) : '已下班';
  updateTodayCardDisplay(info, now);
}

function renderStats() {
  const stats = calculateMonthlyStats(viewYear, viewMonth);
  const previous = calculateMonthlyStats(viewYear, viewMonth - 1);
  const shifts = getUniqueShiftsInCycle();
  const totalDays = new Date(viewYear, viewMonth + 1, 0).getDate();
  $('statsMonth').textContent = `${viewYear}.${String(viewMonth + 1).padStart(2,'0')}`;
  $('statsTitle').textContent = `${viewMonth + 1}月概览`;
  const cards = shifts.map(name => {
    const def = getShiftDef(name), count = stats[name] || 0, difference = count - (previous[name] || 0);
    const card = mk('div', { cls:`stat-card ${def.cssClass}` });
    const change = difference === 0 ? '较上月持平' : `较上月 ${difference > 0 ? '+' : ''}${difference} 天`;
    card.append(mk('span', { cls:'stat-icon', text:def.emoji }), mk('div', { cls:'stat-value', text:count }), mk('div', { cls:'stat-label', text:`${name} / 天` }), mk('div', { cls:`stat-trend ${difference > 0 ? 'up' : difference < 0 ? 'down' : 'same'}`, text:change }));
    return card;
  });
  $('statsContainer').replaceChildren(...cards);
  $('statsContainer').style.gridTemplateColumns = `repeat(${Math.min(shifts.length, 3)},minmax(0,1fr))`;
  $('workHours').textContent = calculateWorkHours(viewYear, viewMonth);
  let cumulative = 0;
  const gradient = shifts.map(name => {
    const from = cumulative; cumulative += (stats[name] || 0) / totalDays * 100;
    return `var(--${getShiftDef(name).cssClass}-color) ${from}% ${cumulative}%`;
  });
  $('ringChart').style.background = `conic-gradient(${gradient.join(',')})`;
  const workDays = shifts.reduce((sum, name) => sum + (isWorkShift(name) ? stats[name] || 0 : 0), 0);
  $('ringChartInner').textContent = `${Math.round(workDays / totalDays * 100)}%`;
  $('ringChart').setAttribute('aria-label', `${viewMonth + 1}月工作${workDays}天，占${Math.round(workDays / totalDays * 100)}%`);
  $('monthMeta').textContent = `共 ${totalDays} 天 · 工作 ${workDays} 天`;
  $('ringLegend').replaceChildren(...shifts.map(name => {
    const item = mk('div', {cls:'ring-legend-item'});
    const dot = mk('span', {cls:'ring-legend-dot'}); dot.style.background = `var(--${getShiftDef(name).cssClass}-color)`;
    item.append(dot, mk('span', {text:`${name} ${stats[name] || 0}天`})); return item;
  }));
}
function renderLegend() {
  $('legendContainer').replaceChildren(...getUniqueShiftsInCycle().map(name => {
    const def = getShiftDef(name), item = mk('div', {cls:'legend-item'});
    item.title = `${name} · ${shiftTimeText(name)}`;
    item.append(mk('span', {cls:`legend-dot ${def.cssClass}`}), mk('span', {text:name})); return item;
  }));
}
function renderCalendar() {
  $('monthTitle').textContent = `${viewYear} 年 ${viewMonth + 1} 月`;
  $('prevBtn').disabled = viewYear === minYear() && viewMonth === 0;
  $('nextBtn').disabled = viewYear === maxYear() && viewMonth === 11;
  const fragment = document.createDocumentFragment();
  const firstDow = (new Date(viewYear, viewMonth, 1).getDay() + 6) % 7;
  const daysInMonth = new Date(viewYear,viewMonth + 1,0).getDate();
  $('daysGrid').style.setProperty('--week-rows',Math.ceil((firstDow + daysInMonth) / 7));
  for (let i = 0; i < firstDow; i++) { const empty = mk('div',{cls:'day-cell empty'}); empty.setAttribute('aria-hidden','true'); fragment.append(empty); }
  for (let d = 1; d <= new Date(viewYear, viewMonth + 1, 0).getDate(); d++) {
    const date = new Date(viewYear, viewMonth, d), name = getShift(date), def = getShiftDef(name);
    const today = isSameDay(date, new Date()), selected = selectedDate && isSameDay(date, selectedDate);
    const lunar = getLunarDate(date), term = getSolarTerm(date), festival = getHoliday(date);
    const cell = mk('button', {cls:`day-cell shift-${def.cssClass}${today ? ' today' : ''}${selected ? ' selected' : ''}`, onclick:() => showDetail(date)});
    cell.dataset.date = formatDateISO(date);
    cell.setAttribute('aria-label', `${formatDate(date)} ${getWeekdayCn(date)} ${name} ${shiftTimeText(name)}${lunar.day ? ` 农历${lunar.monthStr}${lunar.dayStr}` : ''}${term ? ` ${term}` : ''}${festival ? ` ${festival}` : ''}`);
    cell.setAttribute('aria-pressed', String(!!selected));
    if (today) cell.setAttribute('aria-current','date');
    const heading = mk('span',{cls:'day-heading'});
    heading.append(mk('span',{cls:'day-num',text:d}));
    if (festival) {
      cell.classList.add('is-festival');
      cell.dataset.festival = festival;
      const parts = festivalParts(festival);
      cell.classList.toggle('festival-combined',parts.length > 1);
      if (parts.length) cell.dataset.festivalPalette = FESTIVAL_ART[parts[0]].palette;
      heading.append(makeFestivalArt(festival));
    }
    const label = mk('span', {cls:'day-shift'});
    label.append(mk('span',{cls:`shift-dot ${def.cssClass}`}),mk('span',{cls:'day-shift-label',text:name}));
    cell.append(heading,label,mk('span',{cls:`lunar-date${term ? ' solar-term' : ''}`,text:term || (lunar.day === 1 ? lunar.monthStr : lunar.dayStr)}));
    if (festival) cell.append(mk('span',{cls:'holiday-badge',text:festival}));
    fragment.append(cell);
  }
  $('daysGrid').replaceChildren(fragment);
  const monthKey = viewYear * 12 + viewMonth;
  if (lastRenderedMonth !== null && lastRenderedMonth !== monthKey) {
    const direction = monthKey > lastRenderedMonth ? 1 : -1;
    animateElement($('daysGrid'),[{opacity:.15,transform:`translateX(${direction * 14}px)`},{opacity:1,transform:'translateX(0)'}]);
    animateElement($('statsContainer'),[{opacity:.3,transform:'translateY(5px)'},{opacity:1,transform:'translateY(0)'}]);
  }
  lastRenderedMonth = monthKey;
}
function renderHeatmap() {
  $('heatmapTitle').textContent = `${viewYear} 年度排班总览`;
  const months = [];
  for (let m = 0; m < 12; m++) {
    const month = mk('button',{cls:`heatmap-month${m === viewMonth ? ' viewed-month' : ''}`,onclick:event => {
      const day = event.target.closest('[data-date]')?.dataset.date;
      navigateMonth(viewYear,m);
      if (day) showDetail(Core.parseDate(day));
      if (!day || window.innerWidth > 640) $('calendarSection').scrollIntoView?.({behavior:motionAllowed() ? 'smooth' : 'auto',block:'start'});
    }});
    month.setAttribute('aria-label',`查看${viewYear}年${m + 1}月排班`);
    month.setAttribute('aria-pressed',String(m === viewMonth));
    const weekdays = mk('span',{cls:'mini-cal-weekdays'}); weekdays.setAttribute('aria-hidden','true');
    for (const label of ['一','二','三','四','五','六','日']) weekdays.append(mk('span',{cls:'mini-cal-wd',text:label}));
    const grid = mk('span',{cls:'mini-cal-grid'}); grid.setAttribute('aria-hidden','true');
    const start = (new Date(viewYear,m,1).getDay() + 6) % 7;
    for (let i = 0; i < start; i++) grid.append(mk('span',{cls:'mini-cal-day empty'}));
    for (let d = 1; d <= new Date(viewYear,m + 1,0).getDate(); d++) {
      const date = new Date(viewYear,m,d);
      const day = mk('span',{cls:`mini-cal-day shift-${getShiftDef(getShift(date)).cssClass}${isSameDay(date,new Date()) ? ' today-marker' : ''}`});
      day.dataset.date = formatDateISO(date);
      day.title = `${m + 1}月${d}日 · ${getShift(date)}`;
      grid.append(day);
    }
    month.append(weekdays,grid,mk('span',{cls:'heatmap-month-label',text:`${m + 1}月`})); months.push(month);
  }
  $('heatmapGrid').replaceChildren(...months);
}
function renderNextShifts() {
  const upcoming = calculateNextShifts();
  $('nextShiftContainer').replaceChildren(...getWorkShifts().map(name => {
    const row = mk('div',{cls:'next-shift-item'});
    row.append(mk('span',{text:`下次${name}`}),mk('span',{text:`${upcoming[name]} 天后`})); return row;
  }));
  $('nextShiftContainer').hidden = getWorkShifts().length === 0;
}
function renderUpcoming() {
  const today = new Date();
  const last = addDays(today,6);
  $('weekRange').textContent = `${today.getMonth()+1}.${String(today.getDate()).padStart(2,'0')} — ${last.getMonth()+1}.${String(last.getDate()).padStart(2,'0')}`;
  $('upcomingList').replaceChildren(...Array.from({length:7},(_,index) => {
    const date = addDays(today,index), name = getShift(date), def = getShiftDef(name);
    const festival = getHoliday(date);
    const row = mk('button',{cls:`upcoming-day${index === 0 ? ' is-today' : ''}`,onclick:() => {
      navigateMonth(date.getFullYear(),date.getMonth());
      showDetail(date);
    }});
    row.dataset.date = formatDateISO(date);
    row.setAttribute('aria-label',`${formatDate(date)} ${getWeekdayCn(date)}${festival ? ` ${festival}` : ''} ${name} 查看详情`);
    const dateLabel = mk('span',{cls:'upcoming-date'});
    dateLabel.append(mk('strong',{text:`${String(date.getMonth()+1).padStart(2,'0')}.${String(date.getDate()).padStart(2,'0')}`}),mk('small',{text:index === 0 ? '今天' : index === 1 ? '明天' : getWeekdayCn(date)}));
    const shift = mk('span',{cls:`upcoming-shift ${def.cssClass}`});
    shift.append(mk('span',{cls:`shift-dot ${def.cssClass}`}),mk('span',{text:name}));
    row.title = [festival,shiftTimeText(name)].filter(Boolean).join(' · ');
    row.append(dateLabel,shift);
    if (festival) {
      row.classList.add('is-festival');
      const parts = festivalParts(festival);
      if (parts.length) row.dataset.festivalPalette = FESTIVAL_ART[parts[0]].palette;
      const tag = mk('span',{cls:'upcoming-festival'});
      tag.append(makeFestivalArt(festival),mk('span',{text:festival}));
      row.append(tag);
    }
    return row;
  }));
}
function render() {
  const today = new Date();
  viewYear = Math.min(maxYear(),Math.max(minYear(),viewYear));
  $('subtitle').textContent = `${formatDate(today)} · ${getWeekdayCn(today)}`;
  $('footerHint').textContent = `配置仅保存在当前设备 · ${CYCLE.length} 天一循环`;
  renderStats(); renderLegend(); renderCalendar(); renderHeatmap(); renderNextShifts(); renderUpcoming(); updateLiveClockAndCountdown();
  if (selectedDate) renderDetail(selectedDate);
}
function renderDetail(date) {
  const name = getShift(date), def = getShiftDef(name), lunar = getLunarDate(date);
  const festival = getHoliday(date), term = getSolarTerm(date);
  renderFestivalNotice($('detailFestival'),festival);
  $('detailHeading').textContent = `${date.getMonth()+1}月${date.getDate()}日 · ${getWeekdayCn(date)}`;
  $('detailDate').textContent = `${formatDate(date)} ${getWeekdayCn(date)}${lunar.day ? ` · 农历${lunar.yearGanZhi}年 [${lunar.zodiac}] ${lunar.monthStr}${lunar.dayStr}` : ''}${festival ? ` · ${festival}` : ''}${term && term !== festival ? ` · ${term}` : ''}`;
  $('detailShift').textContent = `${def.emoji} ${name}`;
  $('detailShift').className = `detail-shift ${def.cssClass}`;
  $('detailTime').textContent = shiftTimeText(name);
  $('detailCycle').textContent = getCycleIndex(date) + 1;
  const rest = getDaysToWeekend(date), consecutive = getConsecutiveShiftDays(date);
  $('detailExtra').replaceChildren(...[
    ['连续同班',consecutive === null ? '整个周期' : `${consecutive} 天`],
    ['当月计划工时',`${calculateWorkHours(date.getFullYear(),date.getMonth())} h`],
    ['距休息日',rest < 0 ? '周期内无休息日' : rest === 0 ? '就是今天' : `${rest} 天`],
    ['周期位置',`${getCycleIndex(date) + 1} / ${CYCLE.length}`]
  ].map(([label,value]) => { const item = mk('div',{cls:'detail-extra-item'}); item.append(mk('p',{cls:'detail-extra-label',text:label}),mk('p',{cls:'detail-extra-value',text:value})); return item; }));
}
function showDetail(date) {
  selectedDate = date; renderDetail(date);
  $('upcomingPanel').hidden = true;
  $('detailPanel').classList.add('show');
  reveal($('detailPanel'));
  updateSelection();
  if (window.innerWidth <= 640) $('detailPanel').scrollIntoView?.({behavior:motionAllowed() ? 'smooth' : 'auto',block:'nearest'});
}
function updateSelection() {
  for (const cell of $('daysGrid').querySelectorAll('button')) {
    const selected = !!selectedDate && cell.dataset.date === formatDateISO(selectedDate);
    cell.classList.toggle('selected',selected); cell.setAttribute('aria-pressed',String(selected));
  }
}
function closeDetail() {
  const wasOpen = $('detailPanel').classList.contains('show');
  $('detailPanel').classList.remove('show');
  $('upcomingPanel').hidden = false;
  selectedDate = null; updateSelection();
  if (wasOpen) reveal($('upcomingPanel'));
}

function openYearOverview() {
  $('yearOverview').open = true;
  $('yearOverview').scrollIntoView?.({behavior:motionAllowed() ? 'smooth' : 'auto',block:'start'});
}
$('yearOverview').addEventListener('toggle',event => {
  if (event.currentTarget.open) reveal(event.currentTarget.querySelector('.year-content'));
});
document.querySelector('.view-link[href="#calendarSection"]').addEventListener('click',event => {
  event.preventDefault();
  $('calendarSection').scrollIntoView?.({behavior:motionAllowed() ? 'smooth' : 'auto',block:'start'});
});

// A single navigation boundary applies to buttons, keyboard shortcuts and the picker.
function minYear() { return Math.max(1900,new Date().getFullYear() - 1); }
function maxYear() { return Math.min(2100,new Date().getFullYear() + 1); }
function navigateMonth(year, month) {
  const target = new Date(year,month,1);
  if (target.getFullYear() < minYear() || target.getFullYear() > maxYear()) return;
  viewYear = target.getFullYear(); viewMonth = target.getMonth(); closeDetail(); render();
}
function prevMonth() { navigateMonth(viewYear,viewMonth - 1); }
function nextMonth() { navigateMonth(viewYear,viewMonth + 1); }
function backToToday() { const today = new Date(); navigateMonth(today.getFullYear(),today.getMonth()); showDetail(today); }
function openPicker() { pickerYear = viewYear; renderPicker(); openDialog('pickerModal'); }
function closePicker() { closeDialog('pickerModal'); }
function renderPicker() {
  $('pickerYearLabel').textContent = `${pickerYear} 年`;
  $('pickerPrev').disabled = pickerYear <= minYear(); $('pickerNext').disabled = pickerYear >= maxYear();
  $('pickerMonths').replaceChildren(...Array.from({length:12},(_,m) => {
    const button = mk('button',{cls:`picker-month-btn${pickerYear === viewYear && m === viewMonth ? ' active' : ''}`,text:`${m + 1}月`,onclick:() => { navigateMonth(pickerYear,m); closePicker(); }});
    button.setAttribute('aria-pressed',String(pickerYear === viewYear && m === viewMonth)); return button;
  }));
}
function pickerPrevYear() { if (pickerYear > minYear()) { pickerYear--; renderPicker(); } }
function pickerNextYear() { if (pickerYear < maxYear()) { pickerYear++; renderPicker(); } }

function captureCurrentConfig() { return Core.normalizeConfig({ anchor:formatDateISO(ANCHOR_DATE),cycle:CYCLE,shiftDefs:SHIFT_DEFS }); }
function configKey(config) { return JSON.stringify([config.anchor,config.cycle,Object.entries(config.shiftDefs).sort(([a],[b])=>a.localeCompare(b))]); }
function commitConfig(config) {
  const normalized = Core.normalizeConfig(config);
  if (!normalized) return false;
  const persisted = storageSet('qingzheng-config',JSON.stringify(normalized));
  CYCLE = normalized.cycle; SHIFT_DEFS = normalized.shiftDefs; ANCHOR_DATE = Core.parseDate(normalized.anchor);
  syncActivePreset(); closeDetail(); render();
  // 同步给桌面小部件；原生侧失败不影响网页已保存的设置。
  if (!pushBridgeConfig(normalized)) {
    if (AndroidBridge) toast('网页设置已更新，原生应用同步暂时失败');
  }
  return persisted;
}
function loadPresets() {
  const saved = readJSON('qingzheng-presets');
  if (saved !== null && !Array.isArray(saved)) recoveredData = true;
  const seen = new Set();
  PRESETS = (Array.isArray(saved) ? saved : []).flatMap(preset => {
    const config = Core.normalizeConfig(preset);
    if (!config || typeof preset.id !== 'string' || typeof preset.name !== 'string' || !preset.name.trim() || seen.has(preset.id)) { recoveredData = true; return []; }
    seen.add(preset.id);
    return [{...config,id:preset.id,name:preset.name,createdAt:preset.createdAt}];
  });
  syncActivePreset();
}
function savePresets() { return storageSet('qingzheng-presets',JSON.stringify(PRESETS)); }
function syncActivePreset() {
  const current = configKey(captureCurrentConfig());
  activePresetId = PRESETS.find(preset => configKey(preset) === current)?.id || null;
  renderPresetBar();
}
function saveAsPreset() {
  const input = prompt('给当前排班方案起个名字：');
  if (!input?.trim()) return;
  const name = input.trim();
  if (name.length > 40) { toast('方案名称最多 40 个字符'); return; }
  if (PRESETS.some(preset => preset.name === name)) { toast('已有同名方案，请换一个名字'); return; }
  const preset = {...captureCurrentConfig(),id:`preset_${Date.now()}_${Math.random().toString(36).slice(2,8)}`,name,createdAt:new Date().toISOString()};
  PRESETS.push(preset); activePresetId = preset.id;
  const saved = savePresets(); renderPresetBar();
  toast(saved ? '当前排班已保存为方案' : '方案仅在本次打开期间有效');
}
function applyPreset(id) {
  const preset = PRESETS.find(value => value.id === id);
  if (!preset) return;
  const saved = commitConfig(preset); closeSettings();
  toast(saved ? `已切换到「${preset.name}」` : '方案已应用，本次修改暂时无法保存');
}
function deletePreset(id) {
  const preset = PRESETS.find(value => value.id === id);
  if (!preset || !confirm(`删除方案「${preset.name}」？当前排班不会改变。`)) return;
  PRESETS = PRESETS.filter(value => value.id !== id);
  if (activePresetId === id) activePresetId = null;
  const saved = savePresets(); renderPresetBar(); toast(saved ? '方案已删除，当前排班保留' : '本次已移除方案，关闭后可能恢复');
}
function renderPresetBar() {
  const items = [];
  if (!activePresetId) items.push(mk('span',{cls:'preset-label',text:PRESETS.length ? '自定义排班' : '当前排班'}));
  for (const preset of PRESETS) {
    const group = mk('div',{cls:`preset-group${preset.id === activePresetId ? ' active' : ''}`});
    const apply = mk('button',{cls:'preset-btn',text:preset.name,onclick:()=>applyPreset(preset.id)});
    apply.title = `应用方案「${preset.name}」`; apply.setAttribute('aria-pressed',String(preset.id === activePresetId));
    const remove = mk('button',{cls:'preset-delete',text:'×',onclick:()=>deletePreset(preset.id)});
    remove.setAttribute('aria-label',`删除方案「${preset.name}」`);
    group.append(apply,remove); items.push(group);
  }
  $('presetBar').replaceChildren(...items);
}

// Settings are a separate draft. Add/remove/re-render never mutate the live schedule.
function settingsError(message) { $('settingsError').textContent = message; $('settingsError').hidden = !message; }
function openSettings() {
  draftDefs = Core.normalizeDefs(SHIFT_DEFS);
  $('anchorDate').value = formatDateISO(ANCHOR_DATE); $('cyclePattern').value = CYCLE.join(' ');
  settingsError(''); renderShiftDefsEditor(); openDialog('settingsModal');
}
function closeSettings() { draftDefs = null; closeDialog('settingsModal'); }
function readDraftRows() {
  const defs = Object.create(null);
  for (const row of $('shiftDefsEditor').querySelectorAll('.shift-def-row')) {
    const name = row.dataset.shift, style = row.querySelector('select').value;
    const work = row.querySelector('[data-field="isWork"]').checked;
    const oldDef = draftDefs && Core.own(draftDefs,name) ? draftDefs[name] : null;
    defs[name] = { emoji:oldDef?.cssClass === style ? oldDef.emoji : PRESET_ICONS[style],cssClass:style,isWork:work,startTime:work ? row.querySelector('[data-field="startTime"]').value : null,endTime:work ? row.querySelector('[data-field="endTime"]').value : null,crossDay:work && row.querySelector('[data-field="crossDay"]').checked };
  }
  return defs;
}
function labeledInput(label, type, name, field, value) {
  const wrapper = mk('label',{text:label}), input = mk('input');
  input.type = type; input.dataset.field = field; input.setAttribute('aria-label',`${name} ${label}`);
  if (type === 'checkbox') input.checked = !!value; else input.value = value || '';
  wrapper.append(input); return [wrapper,input];
}
function renderShiftDefsEditor() {
  const rows = [];
  for (const [name,def] of Object.entries(draftDefs || {})) {
    const row = mk('div',{cls:'shift-def-row'}); row.dataset.shift = name;
    const select = mk('select'); select.setAttribute('aria-label',`${name} 颜色样式`);
    for (const style of CSS_PRESETS) { const option = mk('option',{text:`${PRESET_ICONS[style]} ${STYLE_NAMES[style]}`}); option.value = style; select.append(option); } select.value = def.cssClass;
    const remove = mk('button',{cls:'shift-def-delete',text:'×',onclick:()=>removeShiftDef(name)}); remove.setAttribute('aria-label',`删除班次「${name}」`);
    row.append(mk('span',{cls:'shift-def-name',text:`${def.emoji} ${name}`}),select,remove);
    const controls = mk('div',{cls:'shift-def-controls'});
    const [startLabel,start] = labeledInput('上班','time',name,'startTime',def.startTime);
    const [endLabel,end] = labeledInput('下班','time',name,'endTime',def.endTime);
    const [workLabel,work] = labeledInput('工作班次','checkbox',name,'isWork',def.isWork);
    const [crossLabel,cross] = labeledInput('跨日','checkbox',name,'crossDay',def.crossDay);
    start.disabled = end.disabled = cross.disabled = !def.isWork;
    work.addEventListener('change',() => { start.disabled = end.disabled = cross.disabled = !work.checked; if (work.checked) { start.value ||= '09:00'; end.value ||= '17:00'; } });
    controls.append(startLabel,endLabel,workLabel,crossLabel); row.append(controls); rows.push(row);
  }
  $('shiftDefsEditor').replaceChildren(...rows);
}
function addShiftDef() {
  if (!draftDefs) return;
  const input = prompt('新班次名称（例如早班、值班，不含空格）：');
  if (!input?.trim()) return;
  const name = input.trim();
  if (!Core.validName(name)) { settingsError('班次名称不能包含空格，且长度应为 1–40 个字符。'); return; }
  draftDefs = readDraftRows();
  if (Core.own(draftDefs,name)) { settingsError('这个班次已存在。'); return; }
  draftDefs[name] = Core.defaultDef(name); settingsError(''); renderShiftDefsEditor();
}
function removeShiftDef(name) {
  if (!draftDefs || !confirm(`移除班次「${name}」及周期中的对应项？保存设置后生效。`)) return;
  draftDefs = readDraftRows(); delete draftDefs[name];
  $('cyclePattern').value = $('cyclePattern').value.split(/\s+/).filter(value=>value && value !== name).join(' ');
  renderShiftDefsEditor();
}
function saveSettings() {
  if (!draftDefs) return;
  const anchor = $('anchorDate').value, cycle = $('cyclePattern').value.trim().split(/\s+/).filter(Boolean);
  if (!Core.parseDate(anchor)) { settingsError('请选择有效的周期起始日期（1900–2100 年）。'); return; }
  if (!Core.validCycle(cycle)) { settingsError('周期不能为空；最多 3660 天，每个班次名称最多 40 个字符且不含空格。'); return; }
  const defs = readDraftRows();
  for (const [name,def] of Object.entries(defs)) if (def.isWork && (!Core.validTime(def.startTime) || !Core.validTime(def.endTime))) { settingsError(`请填写「${name}」的完整上下班时间。`); return; }
  const missing = [];
  for (const name of cycle) if (!Core.own(defs,name)) { defs[name] = Core.own(DEFAULT_SHIFT_DEFS,name) ? DEFAULT_SHIFT_DEFS[name] : Core.defaultDef(name); missing.push(name); }
  if (missing.length) {
    draftDefs = Core.normalizeDefs(defs); renderShiftDefsEditor(); settingsError(`已为新班次「${[...new Set(missing)].join('、')}」补充时间定义，请确认后再次保存。`); return;
  }
  const persisted = commitConfig({anchor,cycle,shiftDefs:defs}); closeSettings();
  toast(persisted ? '排班设置已保存' : '排班已更新，但浏览器暂时无法保存');
}

// Dialogs retain keyboard focus, close on Escape/backdrop, and never navigate the month behind them.
function openDialog(id) {
  if (activeDialog) closeDialog(activeDialog);
  dialogTrigger = document.activeElement; activeDialog = id; $(id).hidden = false;
  document.querySelector('.main-container').inert = true; document.body.style.overflow = 'hidden';
  const content = $(id).querySelector('[role="dialog"]');
  content.focus({preventScroll:true});
  content.scrollTop = 0;
  animateElement(content,[{opacity:0,transform:'translateY(10px) scale(.98)'},{opacity:1,transform:'translateY(0) scale(1)'}],{duration:280});
}
function closeDialog(id) {
  $(id).hidden = true;
  if (activeDialog !== id) return;
  activeDialog = null; document.querySelector('.main-container').inert = false; document.body.style.overflow = '';
  if (dialogTrigger?.isConnected) dialogTrigger.focus(); dialogTrigger = null;
}
for (const id of ['settingsModal','pickerModal']) $(id).addEventListener('click',event => { if (event.target === $(id)) id === 'settingsModal' ? closeSettings() : closePicker(); });
document.addEventListener('keydown',event => {
  if (activeDialog) {
    if (event.key === 'Escape') { event.preventDefault(); activeDialog === 'settingsModal' ? closeSettings() : closePicker(); }
    if (event.key === 'Tab') {
      const nodes = [...$(activeDialog).querySelectorAll('button,input,textarea,select')]
        .filter(node => !node.disabled)
        .sort((a,b) => a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1);
      const first = nodes[0], last = nodes[nodes.length - 1];
      if (event.shiftKey && (document.activeElement === first || document.activeElement.getAttribute('role') === 'dialog')) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && (document.activeElement === last || document.activeElement.getAttribute('role') === 'dialog')) { event.preventDefault(); first?.focus(); }
    }
    return;
  }
  if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || event.target.closest?.('input,textarea,select,[contenteditable="true"]')) return;
  // Arrow keys activate only the calendar shortcuts; native buttons still handle Enter/Space.
  if (event.key === 'ArrowLeft') { event.preventDefault(); prevMonth(); }
  else if (event.key === 'ArrowRight') { event.preventDefault(); nextMonth(); }
  else if (event.key.toLowerCase() === 't') { event.preventDefault(); backToToday(); }
  else if (event.key === 'Escape') closeDetail();
});
function setTheme(theme, save = true) {
  document.documentElement.dataset.theme = theme;
  $('themeIcon').textContent = theme === 'light' ? '☾' : '☀';
  $('themeToggle').setAttribute('aria-label',`切换为${theme === 'light' ? '深色' : '浅色'}主题`);
  document.querySelector('meta[name="theme-color"]').content = theme === 'light' ? '#f0f0f7' : '#101b2d';
  $('themeMood').textContent = theme === 'light' ? '银昼' : '蓝夜';
  if (save) {
    automaticTheme = false;
    storageSet('qingzheng-theme',theme);
    storageSet('qingzheng-theme-mode','manual');
  }
  $('themeAuto').setAttribute('aria-pressed',String(automaticTheme));
}
function themeForTime(date) { return date.getHours() >= 7 && date.getHours() < 19 ? 'light' : 'dark'; }
function enableAutomaticTheme() {
  automaticTheme = true;
  storageSet('qingzheng-theme-mode','auto');
  setTheme(themeForTime(new Date()),false);
  toast('已启用昼夜主题，07:00–19:00 为银昼，其余时间为蓝夜');
}
$('themeToggle').addEventListener('click',() => setTheme(document.documentElement.dataset.theme === 'light' ? 'dark' : 'light'));
$('backToToday').addEventListener('click',backToToday);
document.addEventListener('visibilitychange',() => { if (!document.hidden) updateLiveClockAndCountdown(); });

function init() {
  const now = new Date(); viewYear = now.getFullYear(); viewMonth = now.getMonth();
  const savedTheme = storageGet('qingzheng-theme');
  const savedMode = storageGet('qingzheng-theme-mode');
  automaticTheme = savedMode === 'auto' || !['light','dark'].includes(savedTheme);
  setTheme(automaticTheme ? themeForTime(now) : savedTheme === 'dark' ? 'dark' : 'light',false);
  loadPresets(); updateStorageNotice(); render();
  setInterval(updateLiveClockAndCountdown,1000);
}
init();
