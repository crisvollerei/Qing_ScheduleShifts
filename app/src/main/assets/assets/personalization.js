import { DEFAULT_TITLE, MAX_AVATAR_FILE_BYTES, MAX_AVATAR_PIXELS, normalizeTitle, validAvatar, avatarMime } from './personalization-core.js?v=7b3e66c3c69d';

export function createPersonalization({window,ui,storage}) {
  const {document} = window, {$,toast} = ui;
  const defaultAvatar = $('brandAvatar').getAttribute('src');
  const defaultBrand = $('brandTitle').cloneNode(true), defaultTabTitle = document.title;
  const offlineSuffix = defaultTabTitle.endsWith('（离线版）') ? '（离线版）' : '';
  let current = {version:1,title:DEFAULT_TITLE,avatar:null}, draft = null, revision = 0, busy = false;

  function render() {
    if (current.title === DEFAULT_TITLE) $('brandTitle').replaceChildren(...[...defaultBrand.childNodes].map(node => node.cloneNode(true)));
    else $('brandTitle').textContent = current.title;
    $('brandTitle').title = current.title;
    document.querySelector('.brand').setAttribute('aria-label',`${current.title}首页`);
    $('footerBrand').textContent = current.title;
    $('brandAvatar').src = current.avatar || defaultAvatar;
    document.title = current.title === DEFAULT_TITLE ? defaultTabTitle : current.title+offlineSuffix;
  }
  function load() {
    const value = storage.readJSON('qingzheng-personalization');
    if (value !== null) {
      if (!value || value.version !== 1 || typeof value !== 'object' || Array.isArray(value)) storage.markRecovered();
      else {
        const title = normalizeTitle(value.title), avatarOK = validAvatar(value.avatar);
        if (!title || !avatarOK) storage.markRecovered();
        current = {version:1,title:title || DEFAULT_TITLE,avatar:avatarOK ? value.avatar : null};
      }
    }
    render();
  }
  function apply(title,avatar,{persist = true} = {}) {
    const saved = persist && storage.set('qingzheng-personalization',JSON.stringify({version:1,title,avatar}));
    current = {version:1,title,avatar}; render();
    return !!saved;
  }
  function restoreTitle(title,options) {
    const normalized = normalizeTitle(title);
    if (!normalized) return false;
    return apply(normalized,current.avatar,options);
  }
  function error(message = '') {
    $('personalizationError').textContent = message; $('personalizationError').hidden = !message;
    $('personalizationTitle').setAttribute('aria-invalid','false');
    if (message) $('personalizationError').focus();
  }
  function setBusy(value) {
    busy = value; $('personalizationSave').disabled = value;
    $('personalizationPreview').setAttribute('aria-busy',String(value));
  }
  function renderPreview() {
    if (!draft) return;
    $('personalizationPreviewTitle').textContent = $('personalizationTitle').value.trim() || DEFAULT_TITLE;
    $('personalizationPreviewAvatar').src = draft.avatar || defaultAvatar;
  }
  function openPersonalization() {
    if (ui.activeDialog === 'personalizationModal') return;
    revision++; draft = {...current};
    $('personalizationTitle').value = current.title; $('avatarFile').value = '';
    $('avatarStatus').textContent = ''; setBusy(false); error(); renderPreview();
    ui.openDialog('personalizationModal');
  }
  function closePersonalization() {
    revision++; draft = null; setBusy(false); $('avatarFile').value = '';
    $('personalizationPreviewAvatar').src = defaultAvatar;
    ui.closeDialog('personalizationModal');
  }
  function resetAvatar() {
    if (!draft || ui.activeDialog !== 'personalizationModal') return;
    revision++; draft.avatar = null; $('avatarFile').value = ''; setBusy(false); error(); renderPreview();
    $('avatarStatus').textContent = '将使用默认头像，保存后生效。';
  }
  function resetTitle() {
    if (!draft || ui.activeDialog !== 'personalizationModal') return;
    $('personalizationTitle').value = DEFAULT_TITLE; error(); renderPreview();
  }
  function readFile(file,method) {
    return new Promise((resolve,reject) => {
      const reader = new window.FileReader();
      reader.onload = () => resolve(reader.result);
      reader.onerror = reader.onabort = () => reject(new Error('图片读取失败，请重新选择。'));
      reader[method](file);
    });
  }
  function decodeImage(data) {
    return new Promise((resolve,reject) => {
      const image = new window.Image();
      image.onload = () => resolve(image);
      image.onerror = () => reject(new Error('无法解码这张图片，请换一张 JPG、PNG、WebP 或 GIF 图片。'));
      image.src = data;
    });
  }
  async function readAvatarFile() {
    if (!draft || ui.activeDialog !== 'personalizationModal') return;
    const token = ++revision, file = $('avatarFile').files[0], activeDraft = draft;
    const isCurrent = () => token === revision && draft === activeDraft && ui.activeDialog === 'personalizationModal';
    setBusy(false); error(); $('avatarStatus').textContent = '';
    if (!file) return;
    if (file.size > MAX_AVATAR_FILE_BYTES) { error('图片不能超过 10 MB，请选择较小的图片。'); return; }
    setBusy(true); $('avatarStatus').textContent = '正在本地处理图片…';
    try {
      const header = await readFile(file.slice(0,12),'readAsArrayBuffer');
      if (!isCurrent()) return;
      const mime = avatarMime(new Uint8Array(header));
      if (!mime) throw new Error('请选择 JPG、PNG、WebP 或 GIF 图片，不支持 SVG、HEIC 等其他格式。');
      const data = await readFile(new window.Blob([file],{type:mime}),'readAsDataURL');
      if (!isCurrent()) return;
      const image = await decodeImage(data);
      if (!isCurrent()) return;
      const width = image.naturalWidth, height = image.naturalHeight;
      if (!width || !height || width*height > MAX_AVATAR_PIXELS) throw new Error('图片尺寸过大或无效，请选择不超过 2400 万像素的图片。');
      const canvas = document.createElement('canvas'); canvas.width = canvas.height = 256;
      const context = canvas.getContext('2d');
      if (!context) throw new Error('当前浏览器不支持图片处理，请换用系统浏览器。');
      const side = Math.min(width,height);
      context.drawImage(image,(width-side)/2,(height-side)/2,side,side,0,0,256,256);
      const avatar = canvas.toDataURL('image/webp',0.86);
      if (!validAvatar(avatar)) throw new Error('处理后的图片仍然过大，请换一张较简单的图片。');
      draft.avatar = avatar; renderPreview();
      $('avatarStatus').textContent = '头像已处理为 256 × 256 静态图片。仅在本地预览，保存后生效。';
    } catch (cause) {
      if (isCurrent()) { $('avatarStatus').textContent = ''; error(cause.message || '图片处理失败，请重新选择。'); }
    } finally { if (isCurrent()) setBusy(false); }
  }
  function savePersonalization() {
    if (!draft || ui.activeDialog !== 'personalizationModal' || busy) return;
    const title = normalizeTitle($('personalizationTitle').value);
    if (!title) {
      error('请填写 1–40 个字符的单行标题。');
      $('personalizationTitle').setAttribute('aria-invalid','true'); $('personalizationTitle').focus(); return;
    }
    const saved = apply(title,draft.avatar);
    closePersonalization();
    toast(saved ? '标题和头像已保存' : '已在本次打开中应用，但浏览器未能保存，关闭后可能丢失');
  }
  $('personalizationForm').addEventListener('submit',event => { event.preventDefault(); savePersonalization(); });
  $('personalizationTitle').addEventListener('input',() => { error(); renderPreview(); });
  $('personalizationTitle').addEventListener('keydown',event => {
    if (event.key === 'Enter' && (event.isComposing || event.keyCode === 229)) event.preventDefault();
  });
  $('avatarFile').addEventListener('change',readAvatarFile);
  ui.onDismiss('personalizationModal',closePersonalization);
  return {load,openPersonalization,closePersonalization,savePersonalization,readAvatarFile,resetAvatar,resetTitle,restoreTitle,
    captureBackup() { return {title:current.title}; }, get title() { return current.title; }, get avatar() { return current.avatar; }};
}
