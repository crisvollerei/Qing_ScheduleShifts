
export function createStorage(window) {
  const $ = id => window.document.getElementById(id);
  let storageFailed = false;
  let recoveredData = false;

  function storageGet(key) {
    try { return window.localStorage.getItem(key); }
    catch (_) { storageFailed = true; return null; }
  }
  function storageSet(key, value) {
    try { window.localStorage.setItem(key, value); return true; }
    catch (_) { storageFailed = true; updateStorageNotice(); return false; }
  }
  function readJSON(key) {
    const raw = storageGet(key);
    if (raw === null) return null;
    try { return JSON.parse(raw); }
    catch (_) { recoveredData = true; return null; }
  }
  function updateStorageNotice() {
    const notice = $('storageNotice');
    notice.hidden = !storageFailed && !recoveredData;
    notice.textContent = storageFailed ? '浏览器暂时无法保存数据。你仍可使用排班表，本次修改在关闭页面后可能丢失。' : '部分本地配置无法读取，已使用可用配置或默认值。原始数据仍保留在浏览器中，请检查排班设置。';
  }
  return { get: storageGet, set: storageSet, readJSON, updateNotice: updateStorageNotice, markRecovered() { recoveredData = true; } };
}
