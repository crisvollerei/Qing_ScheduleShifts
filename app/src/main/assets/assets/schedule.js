import Core from './schedule-core.js?v=fc384b3c941b';
import CalendarData from './calendar-data.js?v=3defcf37fd3d';
import { DEFAULT_ANCHOR, DEFAULT_CYCLE, DEFAULT_SHIFT_DEFS } from './config.js?v=68f942b55cf8';
Core.setLunarCalendar(CalendarData);
export function createSchedule(storage, Date = globalThis.Date) {
  const { get: storageGet, set: storageSet, readJSON } = storage;
  function loadConfig() {
    const current = readJSON('qingzheng-config');
    const normalized = Core.normalizeConfig(current);
    if (normalized) return normalized;
    if (current !== null) storage.markRecovered();
    const legacyCycle = readJSON('qingzheng-cycle');
    const legacyAnchor = storageGet('qingzheng-anchor');
    const legacyDefs = readJSON('qingzheng-shift-defs');
    if (legacyCycle !== null && !Core.validCycle(legacyCycle)) storage.markRecovered();
    if (legacyAnchor !== null && !Core.parseDate(legacyAnchor)) storage.markRecovered();
    return Core.normalizeConfig({
      anchor: Core.parseDate(legacyAnchor) ? legacyAnchor : DEFAULT_ANCHOR,
      cycle: Core.validCycle(legacyCycle) ? legacyCycle : DEFAULT_CYCLE,
      shiftDefs: legacyDefs && typeof legacyDefs === 'object' && !Array.isArray(legacyDefs) ? legacyDefs : DEFAULT_SHIFT_DEFS
    });
  }
  function freezeConfig(config) {
    for (const def of Object.values(config.shiftDefs)) Object.freeze(def);
    Object.freeze(config.shiftDefs); Object.freeze(config.cycle);
    return config;
  }
  const initialConfig = freezeConfig(loadConfig());
  let revision = 0;
  let CYCLE = initialConfig.cycle;
  let ANCHOR_DATE = Core.parseDate(initialConfig.anchor);
  let SHIFT_DEFS = initialConfig.shiftDefs;
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
  function getNextWorkDelay(now) {
    for (let i = 0; i <= CYCLE.length; i++) {
      const times = getShiftTimes(addDays(now, i));
      if (times && times.start > now) return times.start - now;
    }
    return null;
  }
  function getDaysToRest(date) {
    for (let i = 0; i < CYCLE.length; i++) if (isRestShift(getShift(addDays(date, i)))) return i;
    return -1;
  }
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
  function captureCurrentConfig() { return Core.normalizeConfig({ anchor:formatDateISO(ANCHOR_DATE),cycle:CYCLE,shiftDefs:SHIFT_DEFS }); }
  function commitConfig(config, {persist = true} = {}) {
    const normalized = Core.normalizeConfig(config);
    if (!normalized) return false;
    freezeConfig(normalized);
    const saved = persist && storageSet('qingzheng-config',JSON.stringify(normalized));
    revision++; CYCLE = normalized.cycle; SHIFT_DEFS = normalized.shiftDefs; ANCHOR_DATE = Core.parseDate(normalized.anchor);
    return saved;
  }
  return { get revision() { return revision; }, loadConfig, getShiftDef, isWorkShift, isRestShift, isCrossDayShift, getShiftDurationHours, getCycleIndex, getShift, getShiftTimes, getLunarDate, formatDateISO, formatDate, getWeekdayCn, isSameDay, addDays, shiftTimeText, getEffectiveShiftInfo, formatCountdown, getNextWorkDelay, getDaysToRest, getUniqueShiftsInCycle, getWorkShifts, calculateNextShifts, calculateMonthlyStats, calculateWorkHours, getConsecutiveShiftDays, captureCurrentConfig, commitConfig, get cycle() { return CYCLE; }, get shiftDefs() { return SHIFT_DEFS; } };
}
