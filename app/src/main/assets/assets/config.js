export const CSS_PRESETS = ['day', 'night', 'rest', 'noon', 'morning', 'evening'];
export const STYLE_NAMES = { day: '白班蓝', night: '夜班紫', rest: '休息绿', noon: '午后金', morning: '清晨青', evening: '晚霞粉' };
export const PRESET_ICONS = { day: '☀️', night: '🌙', rest: '🌿', noon: '🌤️', morning: '🌅', evening: '🌆' };
export const DEFAULT_ANCHOR = '2026-04-18';
export const DEFAULT_SHIFT_DEFS = {
  '白班': { emoji: '☀️', cssClass: 'day', startTime: '08:00', endTime: '20:00', isWork: true },
  '中班': { emoji: '🌤️', cssClass: 'noon', startTime: '14:00', endTime: '22:00', isWork: true },
  '夜班': { emoji: '🌙', cssClass: 'night', startTime: '20:00', endTime: '08:00', crossDay: true, isWork: true },
  '休息': { emoji: '🌿', cssClass: 'rest', startTime: null, endTime: null, isWork: false }
};
export const DEFAULT_CYCLE = ['白班', '夜班', '休息', '白班', '夜班', '休息', '休息', '休息'];
