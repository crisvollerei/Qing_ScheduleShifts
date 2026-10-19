import test from 'node:test';
import assert from 'node:assert/strict';
import { DEFAULT_TITLE, MAX_TITLE_LENGTH, MAX_AVATAR_DATA_LENGTH, normalizeTitle, validAvatar, avatarMime } from '../app/src/main/assets/assets/personalization-core.js';

test('custom titles allow arbitrary plain text and reject blank, multiline and oversized values',() => {
  assert.equal(normalizeTitle(DEFAULT_TITLE),DEFAULT_TITLE);
  assert.equal(normalizeTitle('  小林的生活日历 🐟  '),'小林的生活日历 🐟');
  assert.equal(normalizeTitle('<b>我的日历</b>'),'<b>我的日历</b>');
  assert.equal(normalizeTitle('a'.repeat(MAX_TITLE_LENGTH)),'a'.repeat(MAX_TITLE_LENGTH));
  for (const value of [null,{},42,'','   ','a'.repeat(MAX_TITLE_LENGTH+1),'标题\n换行','\t标题','标题\u0000','标题\u007f','标题\u2028','标题\u2029']) {
    assert.equal(normalizeTitle(value),null);
  }
});
test('stored avatars are bounded static raster data, never network, SVG or animated URLs',() => {
  assert.equal(validAvatar(null),true);
  for (const type of ['png','jpeg','webp']) assert.equal(validAvatar(`data:image/${type};base64,YWJjZA==`),true);
  for (const value of [undefined,'',{},'https://example.com/avatar.png','blob:avatar','data:image/svg+xml;base64,YWJj','data:image/gif;base64,YWJj','data:image/png;base64,','data:image/png;base64,YW Jj','data:image/png;base64,'+'A'.repeat(MAX_AVATAR_DATA_LENGTH)]) {
    assert.equal(validAvatar(value),false);
  }
});
test('avatar file types use byte signatures rather than filenames or claimed MIME types',() => {
  const cases = [
    [[137,80,78,71,13,10,26,10],'image/png'],
    [[255,216,255,224],'image/jpeg'],
    [[71,73,70,56,57,97],'image/gif'],
    [[71,73,70,56,55,97],'image/gif'],
    [[82,73,70,70,0,0,0,0,87,69,66,80],'image/webp'],
    [[],null],[[137,80],null],[[60,115,118,103,62],null],[[71,73,70,56,56,97],null]
  ];
  for (const [bytes,mime] of cases) assert.equal(avatarMime(Uint8Array.from(bytes)),mime);
});
