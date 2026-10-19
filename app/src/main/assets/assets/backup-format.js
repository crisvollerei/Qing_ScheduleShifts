import Core from './schedule-core.js?v=fc384b3c941b';
import { DEFAULT_TITLE, normalizeTitle } from './personalization-core.js?v=7b3e66c3c69d';

export const MAX_BACKUP_BYTES = 2 * 1024 * 1024;
export const MAX_BACKUP_TEXT_LENGTH = 4 * 1024 * 1024;
const APP = 'qingzheng-schedule', SCHEMA_VERSION = 1, TEXT_VERSION = 1;
const encoder = new TextEncoder();
const BASE64URL = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
const crcTable = Uint32Array.from({length:256},(_,value) => {
  for (let bit = 0; bit < 8; bit++) value = value & 1 ? 0xedb88320 ^ (value >>> 1) : value >>> 1;
  return value >>> 0;
});

function validateConfig(value) {
  if (!value || value.version !== 1 || !Core.parseDate(value.anchor) || !Core.validCycle(value.cycle)
    || !value.shiftDefs || typeof value.shiftDefs !== 'object' || Array.isArray(value.shiftDefs)) return null;
  for (const [name,def] of Object.entries(value.shiftDefs)) {
    if (!Core.validName(name) || !def || typeof def !== 'object' || Array.isArray(def)
      || !['day','night','rest','noon','morning','evening'].includes(def.cssClass)
      || typeof def.isWork !== 'boolean' || typeof def.emoji !== 'string' || def.emoji.length > 16
      || typeof def.crossDay !== 'boolean'
      || (def.isWork && (!Core.validTime(def.startTime) || !Core.validTime(def.endTime)))) return null;
  }
  if (value.cycle.some(name => !Core.own(value.shiftDefs,name))) return null;
  return Core.normalizeConfig(value);
}

function readVersion1(data) {
  const config = validateConfig(data.config);
  if (!config || !Array.isArray(data.presets)) throw new Error('备份中的排班数据不完整或无效，原有数据未改变。');
  const presets = data.presets.map(preset => {
    const normalized = validateConfig(preset);
    if (!normalized || typeof preset.name !== 'string' || !preset.name.trim() || preset.name.trim().length > 40) {
      throw new Error('备份中有无效的方案，未导入任何内容。');
    }
    return {...normalized,name:preset.name.trim()};
  });
  const title = normalizeTitle(data.personalization?.title);
  if (!title) throw new Error('备份中的自定义标题不完整或无效，未导入任何内容。');
  return {config,presets,personalization:{title}};
}

export function parseBackup(text) {
  if (typeof text !== 'string' || text.length > MAX_BACKUP_BYTES || encoder.encode(text).length > MAX_BACKUP_BYTES) {
    throw new Error('备份数据过大，原始 JSON 不能超过 2 MB。');
  }
  let data;
  try { data = JSON.parse(text.replace(/^\uFEFF/,'')); }
  catch (_) { throw new Error('无法读取备份，请使用清蒸排班导出的 JSON 文件或完整备份文本。'); }
  if (!data || data.app !== APP) throw new Error('这不是清蒸排班的备份，原有数据未改变。');
  // Future schema readers migrate to the current restore model here, not in UI handlers.
  switch (data.version) {
    case 1: return readVersion1(data);
    default: throw new Error('不支持这个备份数据版本，请用较新版本的清蒸排班打开。原有数据未改变。');
  }
}

export function createBackup(config, presets, exportedAt, personalization = {title:DEFAULT_TITLE}) {
  return {app:APP,version:SCHEMA_VERSION,exportedAt,config,presets,personalization:{title:personalization.title}};
}

function checksum(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) crc = crcTable[(crc ^ byte) & 255] ^ (crc >>> 8);
  return ((crc ^ 0xffffffff) >>> 0).toString(16).padStart(8,'0');
}

function encodeBase64(bytes) {
  const chunks = [];
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    chunks.push(String.fromCharCode(...bytes.subarray(offset,offset+0x8000)));
  }
  return btoa(chunks.join('')).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
}

export function createBackupText(backup) {
  const json = JSON.stringify(backup);
  parseBackup(json);
  const bytes = encoder.encode(json);
  return `QZB${TEXT_VERSION}.${encodeBase64(bytes)}.${checksum(bytes)}`;
}

export function parseBackupText(text) {
  if (typeof text !== 'string' || text.length > MAX_BACKUP_TEXT_LENGTH) throw new Error('备份文本过长，请检查是否粘贴了多份内容。');
  const trimmed = text.trim();
  if (!trimmed) throw new Error('请先粘贴完整的备份文本。');
  if (trimmed.startsWith('{')) return parseBackup(trimmed);
  const compact = trimmed.replace(/\s/g,''), marker = /^QZB(\d+)\./.exec(compact);
  if (!marker) throw new Error('未找到备份标识，请完整粘贴以 QZB1. 开头的备份文本，也可以粘贴原始 JSON。');
  if (marker[1] !== String(TEXT_VERSION)) throw new Error('不支持这个备份文本版本，请用较新版本的清蒸排班打开。原有数据未改变。');
  const damaged = () => new Error('备份文本不完整或已损坏，请重新完整复制。原有数据未改变。');
  const parts = /^([A-Za-z0-9_-]+)\.([a-fA-F0-9]{8})$/.exec(compact.slice(marker[0].length));
  if (!parts) throw damaged();
  const payload = parts[1];
  if (Math.floor(payload.length*3/4) > MAX_BACKUP_BYTES) throw new Error('备份数据过大，原始 JSON 不能超过 2 MB。');
  const remainder = payload.length % 4, last = BASE64URL.indexOf(payload.at(-1));
  if (remainder === 1 || (remainder === 2 && (last & 15)) || (remainder === 3 && (last & 3))) throw damaged();
  let bytes;
  try { bytes = Uint8Array.from(atob(payload.replace(/-/g,'+').replace(/_/g,'/')),char => char.charCodeAt(0)); }
  catch (_) { throw damaged(); }
  if (checksum(bytes) !== parts[2].toLowerCase()) throw damaged();
  let json;
  try { json = new TextDecoder('utf-8',{fatal:true}).decode(bytes); }
  catch (_) { throw damaged(); }
  return parseBackup(json);
}
