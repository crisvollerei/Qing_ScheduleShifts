const test = require('node:test');
const assert = require('node:assert/strict');
const Core = require('../app/src/main/assets/assets/schedule-core.js');
Core.setLunarCalendar(require('../app/src/main/assets/assets/calendar-data.js'));

test('dates reject impossible values rather than rolling into the next month', () => {
  for (const date of ['2026-02-29','2026-04-31','2026-13-01','oops','0099-01-01']) assert.equal(Core.parseDate(date),null,date);
  assert.equal(Core.parseDate('2024-02-29').getDate(),29);
});
test('cycle positions ignore time of day and support dates before the anchor', () => {
  const anchor = new Date(2026,3,18), cycle = ['白','夜','休'];
  assert.equal(Core.cycleIndex(new Date(2026,3,18,23,59),anchor,cycle),0);
  assert.equal(Core.cycleIndex(new Date(2026,3,17,12),anchor,cycle),2);
  assert.equal(Core.cycleIndex(new Date(2026,3,21),anchor,cycle),0);
});
test('civil-date cycle arithmetic stays stable across daylight-saving changes', () => {
  const previous=process.env.TZ;
  try {
    process.env.TZ='America/New_York';
    assert.equal(Core.cycleIndex(new Date(2026,2,9),new Date(2026,2,7),['白','夜','休']),2);
    assert.equal(Core.cycleIndex(new Date(2026,10,2),new Date(2026,9,31),['白','夜','休']),2);
  } finally { if(previous===undefined)delete process.env.TZ;else process.env.TZ=previous; }
});
test('durations agree with shift end dates for implicit, explicit and 24-hour overnight shifts', () => {
  for (const [startTime,endTime,crossDay,hours] of [['20:00','08:00',false,12],['09:00','17:00',false,8],['09:00','17:00',true,32],['08:00','08:00',false,24],['09:00','17:30',false,8.5]]) {
    const def = {isWork:true,startTime,endTime,crossDay};
    assert.equal(Core.duration(def),hours);
    const times = Core.shiftTimes(new Date(2026,0,1),def);
    assert.equal((times.end - times.start)/3600000,hours);
  }
});
test('invalid storage values cannot produce an empty or undefined cycle', () => {
  for (const cycle of [[],{},'白班',[null],[2],['']]) assert.equal(Core.normalizeConfig({anchor:'2026-04-18',cycle}),null);
  const result = Core.normalizeConfig({anchor:'2026-04-18',cycle:['白班','休假'],shiftDefs:{'白班':{cssClass:'injected',startTime:'oops'}}});
  assert.equal(result.shiftDefs['白班'].cssClass,'day');
  assert.equal(result.shiftDefs['白班'].startTime,'09:00');
  assert.equal(result.shiftDefs['休假'].isWork,false);
});
test('special object property names remain ordinary shift names', () => {
  const config = Core.normalizeConfig({anchor:'2026-04-18',cycle:['__proto__','constructor','toString'],shiftDefs:{}});
  for (const name of config.cycle) assert.equal(config.shiftDefs[name].startTime,'09:00');
  assert.equal(Object.getPrototypeOf(config.shiftDefs),null);
  assert.equal({}.isWork,undefined);
});
test('Chinese calendar handles lunar year boundary, zodiac and leap months', () => {
  assert.deepEqual([Core.lunarDate(new Date(2026,1,17)).month,Core.lunarDate(new Date(2026,1,17)).day,Core.lunarDate(new Date(2026,1,17)).zodiac],[1,1,'马']);
  assert.equal(Core.lunarDate(new Date(2026,1,16)).zodiac,'蛇');
  const leap = Core.lunarDate(new Date(2025,6,25));
  assert.equal(leap.isLeap,true); assert.equal(leap.month,6); assert.equal(leap.day,1);
  assert.equal(Core.lunarDate(new Date(2026,0,19)).month,12);
  assert.equal(Core.lunarDate(new Date(2027,1,6)).day,1);
  assert.equal(Core.lunarDate(new Date(2027,1,6)).zodiac,'羊');
});
test('festivals mark actual festival dates and never invent a seven-day future holiday', () => {
  assert.equal(Core.festival(new Date(2026,1,16)), '除夕');
  assert.equal(Core.festival(new Date(2026,1,17)), '春节');
  assert.equal(Core.festival(new Date(2026,8,25)), '中秋节');
  assert.equal(Core.festival(new Date(2027,9,3)),null);
});
