export const DEFAULT_TITLE = '清蒸排班';
export const MAX_TITLE_LENGTH = 40;
export const MAX_AVATAR_FILE_BYTES = 10 * 1024 * 1024;
export const MAX_AVATAR_DATA_LENGTH = 384 * 1024;
export const MAX_AVATAR_PIXELS = 24 * 1000 * 1000;

export function normalizeTitle(value) {
  if (typeof value !== 'string' || /[\u0000-\u001f\u007f\u2028\u2029]/.test(value)) return null;
  const title = value.trim();
  return title && title.length <= MAX_TITLE_LENGTH ? title : null;
}

export function validAvatar(value) {
  return value === null || (typeof value === 'string' && value.length <= MAX_AVATAR_DATA_LENGTH
    && /^data:image\/(?:png|jpeg|webp);base64,[A-Za-z0-9+/]+={0,2}$/.test(value));
}

export function avatarMime(bytes) {
  const matches = (offset,values) => values.every((value,index) => bytes[offset+index] === value);
  if (matches(0,[137,80,78,71,13,10,26,10])) return 'image/png';
  if (matches(0,[255,216,255])) return 'image/jpeg';
  if (matches(0,[71,73,70,56]) && [55,57].includes(bytes[4]) && bytes[5] === 97) return 'image/gif';
  if (matches(0,[82,73,70,70]) && matches(8,[87,69,66,80])) return 'image/webp';
  return null;
}
