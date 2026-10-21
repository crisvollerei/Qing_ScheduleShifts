import { createSchedule } from '../app/src/main/assets/assets/schedule.js';

export function scheduleFor(config) {
  return createSchedule({
    readJSON: key => key === 'qingzheng-config' ? config : null,
    get: () => null, set: () => true, markRecovered() {}
  });
}

// Project the web model into the fields consumed by a native widget.
export function widgetSnapshot(config, stamp) {
  const schedule = scheduleFor(config);
  const now = new Date(stamp);
  const info = schedule.getEffectiveShiftInfo(now);
  const working = info.start && now >= info.start && now < info.end;
  const delay = schedule.getNextWorkDelay(now);
  const target = working ? +info.end : delay === null ? 0 : +now + delay;
  let targetName = working ? info.shift : '';
  if (!working && target) {
    for (let offset = 0; offset <= schedule.cycle.length; offset++) {
      const day = schedule.addDays(now, offset);
      if (+schedule.getShiftTimes(day)?.start === target) {
        targetName = schedule.getShift(day);
        break;
      }
    }
  }
  const dayInfo = offset => {
    const day = schedule.addDays(now, offset);
    const name = schedule.getShift(day);
    const def = schedule.getShiftDef(name);
    return { date: schedule.formatDateISO(day), name, ...def };
  };
  return {
    instant: +now,
    name: info.shift,
    day: schedule.formatDateISO(info.date),
    start: info.start ? +info.start : 0,
    end: info.end ? +info.end : 0,
    cycleIndex: schedule.getCycleIndex(now),
    countdown: { kind: working ? 'off' : target ? 'work' : 'none', target, name: targetName },
    tomorrow: dayInfo(1),
    week: Array.from({ length: 7 }, (_, index) => dayInfo(index + 1))
  };
}
