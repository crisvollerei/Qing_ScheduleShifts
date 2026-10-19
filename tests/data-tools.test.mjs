import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import Core from '../app/src/main/assets/assets/schedule-core.js';
import { MAX_BACKUP_BYTES, MAX_BACKUP_TEXT_LENGTH, createBackup, parseBackup, createBackupText, parseBackupText } from '../app/src/main/assets/assets/backup-format.js';

const config = () => Core.normalizeConfig({anchor:'2026-10-19',cycle:['白班','夜班','休息'],shiftDefs:{
  '白班':{isWork:true,startTime:'08:00',endTime:'20:00',cssClass:'day'},
  '夜班':{isWork:true,startTime:'20:00',endTime:'08:00',crossDay:true,cssClass:'night'},
  '休息':{isWork:false,cssClass:'rest'}
}});
const backup = () => createBackup(config(),[{...config(),name:'日常方案',id:'not-reused'}],'2026-10-19T12:00:00Z');

test('backup round trips all shift properties and supports UTF-8 BOM files',() => {
  const data = parseBackup('\uFEFF'+JSON.stringify(backup()));
  assert.deepEqual(data.config,config());
  assert.equal(data.presets[0].name,'日常方案');
  assert.equal(data.presets[0].shiftDefs['夜班'].crossDay,true);
  assert.equal(data.presets[0].id,undefined,'Import creates fresh local identifiers');
});
test('backup rejects other applications, future versions and non-JSON files',() => {
  for (const value of ['', '<html>hello</html>','null','[]',JSON.stringify({...backup(),version:2}),JSON.stringify({...backup(),app:'other'})]) {
    assert.throws(() => parseBackup(value));
  }
});
test('backup rejects invalid configurations instead of silently repairing the user data',() => {
  for (const mutate of [
    value => value.config.anchor = '2026-02-30',
    value => value.config.cycle = [],
    value => value.config.cycle = ['missing'],
    value => value.config.shiftDefs['白班'].startTime = '25:00',
    value => value.config.shiftDefs['白班'].isWork = 'true',
    value => value.config.shiftDefs['白班'].crossDay = 'false',
    value => value.config.shiftDefs['白班'].cssClass = 'invalid',
    value => value.presets = {},
    value => value.presets[0].name = ' ',
    value => value.presets[0].cycle = [],
    value => delete value.personalization,
    value => value.personalization.title = '',
    value => value.personalization.title = 'x'.repeat(41),
    value => value.personalization.title = '多行\n标题'
  ]) {
    const value = backup(); mutate(value);
    assert.throws(() => parseBackup(JSON.stringify(value)));
  }
});
test('backup keeps special property names safe and supports every valid long cycle',() => {
  const value = backup(), name = '__proto__';
  value.config.cycle = Array(3660).fill(name);
  value.config.shiftDefs = {[name]:{emoji:'x',isWork:true,startTime:'09:00',endTime:'17:00',cssClass:'day',crossDay:false}};
  const parsed = parseBackup(JSON.stringify(value));
  assert.equal(parsed.config.cycle.length,3660);
  assert.equal(Object.getPrototypeOf(parsed.config.shiftDefs),null);
  assert.equal(Core.own(parsed.config.shiftDefs,name),true);
  assert.equal({}.isWork,undefined);
});
test('backup refuses oversized input and supports an empty collection of saved presets',() => {
  assert.throws(() => parseBackup(' '.repeat(2*1024*1024+1)),/2 MB/);
  const value = backup(); value.presets = [];
  assert.deepEqual(parseBackup(JSON.stringify(value)).presets,[]);
});

test('text transport round trips Chinese, emoji, multiline names and every shift field',() => {
  const data = backup(); data.presets[0].name = '换班 🐉\n周末';
  data.config.shiftDefs['夜班'].emoji = '🌙';
  const text = createBackupText(data);
  assert.match(text,/^QZB1\.[A-Za-z0-9_-]+\.[a-f0-9]{8}$/);
  assert.deepEqual(parseBackupText(text),parseBackup(JSON.stringify(data)));
  assert.deepEqual(parseBackupText(' \uFEFF'+text.match(/.{1,57}/g).join(' \r\n\t')+'\n'),parseBackupText(text));
});
test('fixed v1 JSON and text fixtures define the current backup contract and stable transport',() => {
  const json = readFileSync(new URL('./fixtures/backup-v1.json',import.meta.url),'utf8');
  const text = readFileSync(new URL('./fixtures/backup-text-v1.txt',import.meta.url),'utf8').trim();
  assert.deepEqual(parseBackupText(text),parseBackup(json));
  assert.equal(createBackupText(JSON.parse(json)),text);
});
test('text restore accepts raw JSON content and keeps transport and data versions independent',() => {
  const json = JSON.stringify(backup(),null,2);
  assert.deepEqual(parseBackupText('\uFEFF'+json+'\n'),parseBackup(json));
  assert.throws(() => parseBackupText(createBackupText(backup()).replace('QZB1.','QZB2.')),/文本版本/);
  assert.throws(() => parseBackupText(JSON.stringify({...backup(),version:2})),/数据版本/);
  assert.throws(() => createBackupText({...backup(),version:2}),/数据版本/);
});
test('both backup formats include the title and explicitly exclude the local avatar',() => {
  const data = createBackup(config(),[],'now',{title:'小林的生活日历 🐟',avatar:'PRIVATE_AVATAR'});
  assert.deepEqual(data.personalization,{title:'小林的生活日历 🐟'});
  const json = JSON.stringify(data);
  assert.doesNotMatch(json,/avatar|PRIVATE_AVATAR/);
  for (const parsed of [parseBackup(json),parseBackupText(createBackupText(data))]) {
    assert.deepEqual(parsed.personalization,{title:'小林的生活日历 🐟'});
  }
  data.personalization.avatar = 'UNTRUSTED_AVATAR';
  assert.deepEqual(parseBackup(JSON.stringify(data)).personalization,{title:'小林的生活日历 🐟'});
});
test('text transport CRC-32 matches the standard check vector',() => {
  // UTF-8 "123456789": CRC-32/ISO-HDLC is cbf43926. Transport succeeds, schema rejects the number.
  assert.throws(() => parseBackupText('QZB1.MTIzNDU2Nzg5.cbf43926'),/不是清蒸排班/);
  assert.throws(() => parseBackupText('QZB1._w.ff000000'),/损坏/,'Invalid UTF-8 is rejected even with a matching checksum');
});
test('text transport detects edits, truncation, mixed copies and non-canonical base64',() => {
  const text = createBackupText(backup());
  for (const corrupt of [
    text.slice(0,-1), text.slice(0,-12),text+'.extra',text+text,
    text.replace(/\.[A-Za-z0-9_-]/,'.A'),text.slice(0,-8)+'00000000',
    text.replace('QZB1.','QZB01.'),text.replace('QZB1.','QZB1.='),
    'QZB1.A.cbf43926','QZB1.Zh.00000000','QZB1.Zm9.00000000',
    '不是备份', '```'+text+'```', '', '   '
  ]) assert.throws(() => parseBackupText(corrupt));
  assert.deepEqual(parseBackupText(text.slice(0,-8)+text.slice(-8).toUpperCase()),parseBackupText(text));
});
test('JSON and text limits bound UTF-8 bytes and decoded allocation before parsing',() => {
  assert.throws(() => parseBackup(JSON.stringify({ignored:'汉'.repeat(800000)})),/2 MB/);
  assert.throws(() => parseBackupText(' '.repeat(MAX_BACKUP_TEXT_LENGTH+1)),/过长/);
  const tooLarge = 'QZB1.'+'A'.repeat(Math.ceil(MAX_BACKUP_BYTES/3)*4+4)+'.00000000';
  assert.ok(tooLarge.length < MAX_BACKUP_TEXT_LENGTH);
  assert.throws(() => parseBackupText(tooLarge),/2 MB/);
  const data = backup(); data.ignored = 'x'.repeat(MAX_BACKUP_BYTES);
  assert.throws(() => createBackupText(data),/2 MB/);
});
test('a backup exactly at the UTF-8 byte boundary remains readable in both formats',() => {
  const data = backup(); data.padding = '';
  data.padding = 'x'.repeat(MAX_BACKUP_BYTES-Buffer.byteLength(JSON.stringify(data)));
  assert.equal(Buffer.byteLength(JSON.stringify(data)),MAX_BACKUP_BYTES);
  const text = createBackupText(data);
  assert.deepEqual(parseBackupText(text),parseBackup(JSON.stringify(data)));
  data.padding += 'x';
  assert.throws(() => createBackupText(data),/2 MB/);
});
test('text restore shares strict validation and safe special-name normalization with file restore',() => {
  const data = backup(); data.config.cycle = ['__proto__'];
  data.config.shiftDefs = {['__proto__']:{emoji:'🐟',isWork:false,cssClass:'rest',crossDay:false}};
  const result = parseBackupText(createBackupText(data));
  assert.equal(Object.getPrototypeOf(result.config.shiftDefs),null);
  assert.equal(result.config.shiftDefs['__proto__'].emoji,'🐟');
  assert.equal({}.isWork,undefined);
  data.presets[0].cycle = ['missing'];
  assert.throws(() => createBackupText(data),/无效/);
});
