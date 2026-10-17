/* Shared, dependency-free scheduling and configuration rules. */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ScheduleCore = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  const styles = ['day', 'night', 'rest', 'noon', 'morning', 'evening'];
  const icons = { day: '☀️', night: '🌙', rest: '🌿', noon: '🌤️', morning: '🌅', evening: '🌆' };
  const own = (object, key) => Object.prototype.hasOwnProperty.call(object, key);
  const validName = name => typeof name === 'string' && name.length > 0 && name.length <= 40 && !/\s/u.test(name);
  const validTime = value => typeof value === 'string' && /^([01]\d|2[0-3]):[0-5]\d$/.test(value);
  function parseDate(value) {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
    const [year, month, day] = value.split('-').map(Number);
    if (year < 1900 || year > 2100) return null;
    const date = new Date(year, month - 1, day);
    return date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day ? date : null;
  }
  function dayNumber(date) { return Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / 86400000; }
  function cycleIndex(date, anchor, cycle) {
    const difference = dayNumber(date) - dayNumber(anchor);
    return ((difference % cycle.length) + cycle.length) % cycle.length;
  }
  function defaultDef(name) {
    const rest = /休|假/.test(name);
    return { emoji: rest ? icons.rest : '📋', cssClass: rest ? 'rest' : 'day', startTime: rest ? null : '09:00', endTime: rest ? null : '17:00', isWork: !rest, crossDay: false };
  }
  function normalizeDefs(value) {
    const result = Object.create(null);
    if (!value || typeof value !== 'object' || Array.isArray(value)) return result;
    for (const [name, def] of Object.entries(value)) {
      if (!validName(name) || !def || typeof def !== 'object' || Array.isArray(def)) continue;
      const fallback = defaultDef(name);
      const cssClass = styles.includes(def.cssClass) ? def.cssClass : fallback.cssClass;
      const isWork = typeof def.isWork === 'boolean' ? def.isWork : fallback.isWork;
      result[name] = {
        emoji: typeof def.emoji === 'string' && def.emoji.length <= 16 ? def.emoji : icons[cssClass],
        cssClass, isWork,
        startTime: isWork ? (validTime(def.startTime) ? def.startTime : '09:00') : null,
        endTime: isWork ? (validTime(def.endTime) ? def.endTime : '17:00') : null,
        crossDay: isWork && def.crossDay === true
      };
    }
    return result;
  }
  function validCycle(value) { return Array.isArray(value) && value.length > 0 && value.length <= 3660 && value.every(validName); }
  function normalizeConfig(value) {
    if (!value || typeof value !== 'object' || !parseDate(value.anchor) || !validCycle(value.cycle)) return null;
    const shiftDefs = normalizeDefs(value.shiftDefs);
    for (const name of value.cycle) if (!own(shiftDefs, name)) shiftDefs[name] = defaultDef(name);
    return { version: 1, anchor: value.anchor, cycle: [...value.cycle], shiftDefs };
  }
  function isCrossDay(def) { return def.isWork && validTime(def.startTime) && validTime(def.endTime) && (def.crossDay === true || def.endTime <= def.startTime); }
  function duration(def) {
    if (!def.isWork || !validTime(def.startTime) || !validTime(def.endTime)) return 0;
    const minutes = value => Number(value.slice(0, 2)) * 60 + Number(value.slice(3));
    return (minutes(def.endTime) - minutes(def.startTime) + (isCrossDay(def) ? 1440 : 0)) / 60;
  }
  function shiftTimes(date, def) {
    if (!def.isWork || !validTime(def.startTime) || !validTime(def.endTime)) return null;
    const [sh, sm] = def.startTime.split(':').map(Number);
    const [eh, em] = def.endTime.split(':').map(Number);
    return { start: new Date(date.getFullYear(), date.getMonth(), date.getDate(), sh, sm), end: new Date(date.getFullYear(), date.getMonth(), date.getDate() + (isCrossDay(def) ? 1 : 0), eh, em) };
  }
  const lunarDays = ['初一','初二','初三','初四','初五','初六','初七','初八','初九','初十','十一','十二','十三','十四','十五','十六','十七','十八','十九','二十','廿一','廿二','廿三','廿四','廿五','廿六','廿七','廿八','廿九','三十'];
  const animals = ['鼠','牛','虎','兔','龙','蛇','马','羊','猴','鸡','狗','猪'];
  let lunarFormatter;
  let officialCalendar = null;
  const lunarCache = new Map();
  function setLunarCalendar(calendar) { officialCalendar = calendar; lunarCache.clear(); }
  function lunarResult(year,month,day,isLeap,yearGanZhi) {
    return { year,month,day,isLeap,yearGanZhi,zodiac:animals[(year - 4) % 12],monthStr:(isLeap ? '闰' : '') + ['正','二','三','四','五','六','七','八','九','十','冬','腊'][month - 1] + '月',dayStr:lunarDays[day - 1] };
  }
  function lunarDate(date) {
    const key = dayNumber(date);
    if (lunarCache.has(key)) return lunarCache.get(key);
    if (officialCalendar && key >= officialCalendar.lunarStart && key <= officialCalendar.lunarEnd) {
      const month = officialCalendar.lunarMonths.findLast(entry => entry[0] <= key);
      if (month) {
        const [start,year,number,isLeap] = month;
        const stems = ['甲','乙','丙','丁','戊','己','庚','辛','壬','癸'];
        const branches = ['子','丑','寅','卯','辰','巳','午','未','申','酉','戌','亥'];
        const result = lunarResult(year,number,key-start+1,isLeap,stems[(year-4)%10]+branches[(year-4)%12]);
        lunarCache.set(key,result);
        return result;
      }
    }
    try {
      lunarFormatter ||= new Intl.DateTimeFormat('zh-CN-u-ca-chinese', { year: 'numeric', month: 'long', day: 'numeric', timeZone: 'Asia/Shanghai' });
      // Interpret the selected civil date in China, independent of the device timezone.
      const instant = new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate(), 4));
      const parts = Object.fromEntries(lunarFormatter.formatToParts(instant).map(part => [part.type, part.value]));
      const year = Number(parts.relatedYear), day = Number(parts.day);
      const monthStr = parts.month;
      if (!year || !day || !monthStr) throw new Error('Chinese calendar unavailable');
      const month = ['正月','二月','三月','四月','五月','六月','七月','八月','九月','十月','冬月','腊月'].indexOf(monthStr.replace('闰', '').replace('十一月', '冬月').replace('十二月', '腊月')) + 1;
      const result = { year, month, day, isLeap: monthStr.includes('闰'), monthStr: monthStr.replace('十一月', '冬月').replace('十二月', '腊月'), dayStr: lunarDays[day - 1], yearGanZhi: parts.yearName || '', zodiac: animals[(year - 4) % 12] };
      if (lunarCache.size > 1500) lunarCache.clear();
      lunarCache.set(key, result);
      return result;
    } catch (_) {
      return { year: null, month: null, day: null, monthStr: '', dayStr: '', yearGanZhi: '', zodiac: '', isLeap: false };
    }
  }
  // Festival dates are calendar references, not statutory holiday or make-up-work schedules.
  function festival(date, solarTerm) {
    const fixed = { '1-1': '元旦', '5-1': '劳动节', '10-1': '国庆节' };
    const solar = fixed[`${date.getMonth() + 1}-${date.getDate()}`];
    const lunar = lunarDate(date);
    const traditional = !lunar.isLeap && ({ '1-1': '春节', '1-15': '元宵节', '5-5': '端午节', '8-15': '中秋节', '9-9': '重阳节' })[`${lunar.month}-${lunar.day}`];
    if (solar || traditional) return [solar, traditional].filter(Boolean).join('·');
    if (solarTerm === '清明') return '清明';
    if (lunar.month === 12 && lunar.day >= 29) {
      const tomorrow = new Date(date); tomorrow.setDate(tomorrow.getDate() + 1);
      const next = lunarDate(tomorrow);
      if (next.month === 1 && next.day === 1) return '除夕';
    }
    return null;
  }
  return { own, validName, validTime, parseDate, dayNumber, cycleIndex, defaultDef, normalizeDefs, validCycle, normalizeConfig, isCrossDay, duration, shiftTimes, setLunarCalendar, lunarDate, festival };
});
