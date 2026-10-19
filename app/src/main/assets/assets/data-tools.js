import { MAX_BACKUP_BYTES, createBackup, parseBackup, createBackupText, parseBackupText } from './backup-format.js?v=e7cb2360b4d4';
export function createDataTools({window,ui,schedule,settings,personalization}) {
  const { document, Date } = window;
  const { $, toast } = ui;
  let pending = null, readRevision = 0, copyRevision = 0;
  function showError(message = '') {
    $('backupError').textContent = message;
    $('backupError').hidden = !message;
    if (message) $('backupError').focus();
  }
  function openDataTools() {
    if (ui.activeDialog === 'dataModal') return;
    pending = null; readRevision++; copyRevision++;
    $('backupFile').value = '';
    $('backupTextInput').value = '';
    $('backupTextOutput').value = '';
    $('backupTextExport').hidden = true;
    $('backupTextCopyStatus').textContent = '';
    $('importPreview').hidden = true;
    $('backupStatus').textContent = '';
    $('backupSummary').textContent = `当前 ${schedule.cycle.length} 天一循环 · ${settings.presets.length} 个已保存方案`;
    showError(); ui.openDialog('dataModal');
  }
  function closeDataTools() {
    readRevision++; copyRevision++; pending = null;
    $('backupTextInput').value = ''; $('backupTextOutput').value = '';
    $('backupTextExport').hidden = true; $('importPreview').hidden = true;
    ui.closeDialog('dataModal');
  }
  function captureBackup() {
    return createBackup(schedule.captureCurrentConfig(),settings.presets,new Date().toISOString(),personalization.captureBackup());
  }
  function exportBackup() {
    let text;
    try { text = JSON.stringify(captureBackup(),null,2); parseBackup(text); }
    catch (error) { showError(error.message); return; }
    try {
      const blob = new window.Blob([text],{type:'application/json;charset=utf-8'});
      const url = window.URL.createObjectURL(blob), link = document.createElement('a');
      link.href = url; link.download = `清蒸排班-备份-${schedule.formatDateISO(new Date())}.json`;
      document.body.append(link); link.click(); link.remove();
      window.setTimeout(() => window.URL.revokeObjectURL(url),30000);
      $('backupStatus').textContent = '已发起备份下载，请在浏览器的下载列表中确认并妥善保存文件。';
      showError();
    } catch (_) { showError('当前浏览器无法下载文件，请使用系统浏览器打开页面后再试。'); }
  }
  function selectBackupText() {
    const output = $('backupTextOutput');
    if (ui.activeDialog !== 'dataModal' || !output.value) return;
    output.focus(); output.select(); output.setSelectionRange(0,output.value.length);
    output.scrollTop = 0; output.scrollLeft = 0;
    $('backupTextCopyStatus').textContent = '已全选，请长按文本或使用系统菜单复制，再另存到备忘录等安全位置。';
  }
  async function copyBackupText() {
    if (ui.activeDialog !== 'dataModal') return;
    const revision = ++copyRevision;
    let text;
    try { text = createBackupText(captureBackup()); }
    catch (error) { showError(error.message); return; }
    $('backupTextOutput').value = text; $('backupTextExport').hidden = false;
    $('backupTextSize').textContent = `共 ${text.length.toLocaleString('zh-CN')} 个字符，请完整保存。文本未加密，请勿公开分享。`;
    $('backupTextCopyStatus').textContent = '正在尝试复制…';
    showError();
    try {
      if (!window.navigator.clipboard?.writeText) throw new Error('Clipboard unavailable');
      await window.navigator.clipboard.writeText(text);
      if (revision !== copyRevision || !$('dataModal').classList.contains('input-view')) return;
      $('backupTextCopyStatus').textContent = '已复制到剪贴板。请粘贴到备忘录或其他安全位置；仅复制还不算留存备份。';
    } catch (_) {
      if (revision !== copyRevision || !$('dataModal').classList.contains('input-view')) return;
      $('backupTextCopyStatus').textContent = '浏览器未允许自动复制。请点「全选文本」，再长按复制；完整文本已在下方生成。';
      if (ui.activeDialog === 'dataModal' && document.activeElement !== $('backupTextInput')) {
        selectBackupText();
        $('backupTextCopyStatus').textContent = '浏览器未允许自动复制，已全选下方文本。请长按或使用系统菜单复制，再另存到安全位置。';
      }
    }
  }
  function invalidateImport() {
    readRevision++; pending = null;
    $('importPreview').hidden = true; showError();
    $('backupStatus').textContent = '';
  }
  function previewBackup(backup,source,text = null) {
    pending = {backup,source,text};
    $('importSource').textContent = source === 'text' ? '来自粘贴文本 · 校验通过' : '来自备份文件 · 校验通过';
    $('importSummary').textContent = `${backup.config.cycle.length} 天一循环 · 起始 ${backup.config.anchor} · ${backup.presets.length} 个方案`;
    $('importCycle').textContent = `${backup.config.cycle.slice(0,16).join(' → ')}${backup.config.cycle.length > 16 ? ' …' : ''}`;
    $('importTitlePreview').textContent = `导入标题：${backup.personalization.title}（头像保持当前设置）`;
    $('importPreview').hidden = false;
    $('backupStatus').textContent = '预览已就绪，尚未导入。请核对预览内容后再确认。';
    $('importPreviewTitle').focus();
  }
  function previewBackupText() {
    if (ui.activeDialog !== 'dataModal') return;
    invalidateImport(); $('backupFile').value = '';
    const text = $('backupTextInput').value;
    try { previewBackup(parseBackupText(text),'text',text); }
    catch (error) { showError(error.message); }
  }
  async function readBackupFile() {
    if (ui.activeDialog !== 'dataModal') return;
    invalidateImport();
    const revision = readRevision, file = $('backupFile').files[0];
    if (!file) return;
    if (file.size > MAX_BACKUP_BYTES) { showError('备份文件过大，请选择不超过 2 MB 的文件。'); return; }
    try {
      const text = await new Promise((resolve,reject) => {
        const reader = new window.FileReader();
        reader.onload = () => resolve(reader.result);
        reader.onerror = () => reject(new Error('文件读取失败，请重新选择。'));
        reader.readAsText(file);
      });
      if (revision !== readRevision || ui.activeDialog !== 'dataModal') return;
      previewBackup(parseBackup(text),'file');
    } catch (error) {
      if (revision === readRevision && ui.activeDialog === 'dataModal') showError(error.message);
    }
  }
  function currentImport(entry) {
    if (pending !== entry || ui.activeDialog !== 'dataModal') return false;
    if (entry.source === 'text' && entry.text !== $('backupTextInput').value) {
      invalidateImport(); showError('备份文本已改变，请重新预览后再导入。'); return false;
    }
    return true;
  }
  function importBackup() {
    if (!pending || ui.activeDialog !== 'dataModal') return;
    const entry = pending;
    if (!currentImport(entry)) return;
    ui.confirmAction('应用这份备份中的排班和标题？已有方案不会删除，当前排班会保留为方案；头像保持不变。',() => {
      if (!currentImport(entry)) return;
      const scheduleSaved = settings.restoreBackup(entry.backup);
      const titleSaved = personalization.restoreTitle(entry.backup.personalization.title,{persist:scheduleSaved});
      const saved = scheduleSaved && titleSaved;
      closeDataTools();
      toast(saved ? '备份已导入，原有排班与方案均已保留' : '已在本次打开中导入，但浏览器未能完整保存，请复制或下载备份另存');
    },{title:'确认恢复排班',accept:'导入并应用',cancel:'暂不导入'});
  }
  $('backupFile').addEventListener('change',readBackupFile);
  $('backupTextInput').addEventListener('input',() => { invalidateImport(); $('backupFile').value = ''; });
  ui.onDismiss('dataModal',closeDataTools);
  return {openDataTools,closeDataTools,exportBackup,importBackup,readBackupFile,copyBackupText,selectBackupText,previewBackupText};
}
