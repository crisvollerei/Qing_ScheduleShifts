import Core from './schedule-core.js?v=fc384b3c941b';
import { CSS_PRESETS, STYLE_NAMES, PRESET_ICONS, DEFAULT_ANCHOR, DEFAULT_CYCLE, DEFAULT_SHIFT_DEFS } from './config.js?v=68f942b55cf8';
import { syncAndroidBridge } from './integrations.js?v=924ca4c1da81';
export function createSettings({window, ui, schedule, views, storage}) {
  const { document, Date } = window;
  const { $, mk, toast, openDialog, closeDialog } = ui;
  const { set: storageSet, readJSON } = storage;
  const { captureCurrentConfig } = schedule;
  let PRESETS = [], activePresetId = null, draftDefs = null;
  const presetNodes = new Map();
  function configKey(config) {
    return JSON.stringify([config.anchor,config.cycle,Object.entries(config.shiftDefs).sort(([a],[b])=>a.localeCompare(b))
      .map(([name,def]) => [name,def.emoji,def.cssClass,def.isWork,def.startTime,def.endTime,!!def.crossDay])]);
  }
  function commitConfig(config,options) {
    if (!Core.normalizeConfig(config)) return false;
    const persisted = schedule.commitConfig(config,options);
    syncActivePreset(); views.closeDetail(); views.render();
    syncAndroidBridge(window,schedule.captureCurrentConfig(),toast);
    return persisted;
  }
  function loadPresets() {
    const saved = readJSON('qingzheng-presets');
    if (saved !== null && !Array.isArray(saved)) storage.markRecovered();
    const seen = new Set();
    PRESETS = (Array.isArray(saved) ? saved : []).flatMap(preset => {
      const config = Core.normalizeConfig(preset);
      if (!config || typeof preset.id !== 'string' || typeof preset.name !== 'string' || !preset.name.trim() || seen.has(preset.id)) { storage.markRecovered(); return []; }
      seen.add(preset.id);
      return [{...config,id:preset.id,name:preset.name,createdAt:preset.createdAt}];
    });
    syncActivePreset();
  }
  function savePresets() { return storageSet('qingzheng-presets',JSON.stringify(PRESETS)); }
  function syncActivePreset() {
    const current = configKey(captureCurrentConfig());
    activePresetId = PRESETS.find(preset => configKey(preset) === current)?.id || null;
    renderPresetBar();
  }
  function saveAsPreset() {
    if (ui.activeDialog === 'presetModal') return;
    $('presetName').value = '';
    presetNameError('');
    openDialog('presetModal');
  }
  function presetNameError(message) {
    $('presetError').textContent = message; $('presetError').hidden = !message;
    $('presetName').setAttribute('aria-invalid',String(!!message));
  }
  function closePresetDialog() {
    closeDialog('presetModal');
    $('presetName').value = ''; presetNameError('');
  }
  function submitPreset() {
    if (ui.activeDialog !== 'presetModal') return;
    const name = $('presetName').value.trim();
    let error = '';
    if (!name) error = '请填写方案名称。';
    else if (name.length > 40) error = '方案名称最多 40 个字符。';
    else if (PRESETS.some(preset => preset.name === name)) error = '已有同名方案，请换一个名字。';
    if (error) { presetNameError(error); $('presetName').focus({preventScroll:true}); return; }
    const preset = {...captureCurrentConfig(),id:`preset_${Date.now()}_${Math.random().toString(36).slice(2,8)}`,name,createdAt:new Date().toISOString()};
    PRESETS.push(preset); activePresetId = preset.id;
    const saved = savePresets(); renderPresetBar();
    closePresetDialog();
    toast(saved ? '当前排班已保存为方案' : '方案仅在本次打开期间有效');
  }
  $('presetForm').addEventListener('submit',event => { event.preventDefault(); submitPreset(); });
  $('presetName').addEventListener('input',() => presetNameError(''));
  $('presetName').addEventListener('keydown',event => {
    if (event.key === 'Enter' && (event.isComposing || event.keyCode === 229)) event.preventDefault();
  });
  function applyPreset(id) {
    const preset = PRESETS.find(value => value.id === id);
    if (!preset) return;
    const saved = commitConfig(preset); closeSettings();
    toast(saved ? `已切换到「${preset.name}」` : '方案已应用，本次修改暂时无法保存');
  }
  function deletePreset(id) {
    const preset = PRESETS.find(value => value.id === id);
    if (!preset) return;
    ui.confirmAction(`删除方案「${preset.name}」？当前排班不会改变。`,() => {
      PRESETS = PRESETS.filter(value => value.id !== id);
      if (activePresetId === id) activePresetId = null;
      const saved = savePresets(); renderPresetBar(); toast(saved ? '方案已删除，当前排班保留' : '本次已移除方案，关闭后可能恢复');
      document.querySelector('[data-action="saveAsPreset"]').focus({preventScroll:true});
    });
  }
  function renderPresetBar() {
    const bar = $('presetBar'), ids = new Set(PRESETS.map(preset => preset.id));
    for (const [id,group] of presetNodes) {
      if (!ids.has(id)) { group.remove(); presetNodes.delete(id); }
    }
    let label = bar.querySelector('.preset-label');
    if (activePresetId) { label?.remove(); label = null; }
    else {
      const text = PRESETS.length ? '自定义排班' : '当前排班';
      if (!label) { label = mk('span',{cls:'preset-label',text}); bar.prepend(label); }
      else if (label.textContent !== text) label.textContent = text;
    }
    let previous = label;
    for (const preset of PRESETS) {
      let group = presetNodes.get(preset.id);
      if (!group) {
        group = mk('div',{cls:'preset-group'});
        group.append(mk('button',{cls:'preset-btn',onclick:()=>applyPreset(preset.id)}),
          mk('button',{cls:'preset-delete',text:'×',onclick:()=>deletePreset(preset.id)}));
        presetNodes.set(preset.id,group);
      }
      const apply = group.firstElementChild, remove = group.lastElementChild;
      const active = preset.id === activePresetId;
      if (group.classList.contains('active') !== active) group.classList.toggle('active',active);
      if (apply.textContent !== preset.name) apply.textContent = preset.name;
      const title = `应用方案「${preset.name}」`, removeLabel = `删除方案「${preset.name}」`;
      if (apply.title !== title) apply.title = title;
      if (apply.getAttribute('aria-pressed') !== String(active)) apply.setAttribute('aria-pressed',String(active));
      if (remove.getAttribute('aria-label') !== removeLabel) remove.setAttribute('aria-label',removeLabel);
      // Append or reorder only the changed item; leave the existing buttons and focus intact.
      const next = previous ? previous.nextSibling : bar.firstChild;
      if (group !== next) bar.insertBefore(group,next);
      previous = group;
    }
  }
  function restoreBackup(backup) {
    const usedNames = new Set(PRESETS.map(preset => preset.name));
    const numberedName = (name,suffix) => {
      const ending = `（${suffix}）`;
      return name.slice(0,40 - ending.length) + ending;
    };
    const uniqueName = name => {
      let result = name, suffix = 1;
      while (usedNames.has(result)) result = numberedName(name,suffix++);
      usedNames.add(result);
      return result;
    };
    const addPreset = (config,name) => PRESETS.push({...Core.normalizeConfig(config),
      id:`preset_${Date.now()}_${Math.random().toString(36).slice(2,10)}`,name:uniqueName(name),createdAt:new Date().toISOString()});
    // Keep the pre-import schedule reachable even when it was never saved as a preset.
    const current = captureCurrentConfig();
    if (!PRESETS.some(preset => configKey(preset) === configKey(current))) addPreset(current,'导入前的排班');
    for (const preset of backup.presets) {
      const duplicate = PRESETS.some(existing => {
        const suffix = existing.name.match(/（([1-9]\d*)）$/)?.[1];
        return configKey(existing) === configKey(preset) && (existing.name === preset.name || (suffix && existing.name === numberedName(preset.name,suffix)));
      });
      if (!duplicate) addPreset(preset,preset.name);
    }
    const presetsSaved = savePresets();
    // Never overwrite the persisted schedule if its recovery preset could not be saved.
    const configSaved = commitConfig(backup.config,{persist:presetsSaved});
    return presetsSaved && configSaved;
  }

  // Settings are a separate draft. Add/remove/re-render never mutate the live schedule.
  function settingsStatus(message = '') { $('settingsStatus').textContent = message; $('settingsStatus').hidden = !message; }
  function settingsError(message) { settingsStatus(); $('settingsError').textContent = message; $('settingsError').hidden = !message; }
  function openSettings() {
    if (ui.activeDialog === 'settingsModal') return;
    draftDefs = Core.normalizeDefs(schedule.shiftDefs);
    $('anchorDate').value = captureCurrentConfig().anchor; $('cyclePattern').value = schedule.cycle.join(' ');
    $('shiftName').value = '';
    settingsError(''); renderShiftDefsEditor(); openDialog('settingsModal');
  }
  function closeSettings() { draftDefs = null; settingsStatus(); closeDialog('settingsModal'); }
  function restoreDefaultSettings() {
    if (!draftDefs || ui.activeDialog !== 'settingsModal') return;
    const draft = draftDefs;
    ui.confirmAction(`恢复初始的 ${DEFAULT_CYCLE.length} 天周期、班次与时间，起始日期设为 ${DEFAULT_ANCHOR}？这会替换本页未保存的修改；仍需点击“保存设置”才生效，已保存方案不会删除。`,() => {
      if (draftDefs !== draft || ui.activeDialog !== 'settingsModal') return;
      draftDefs = Core.normalizeDefs(DEFAULT_SHIFT_DEFS);
      $('anchorDate').value = DEFAULT_ANCHOR;
      $('cyclePattern').value = DEFAULT_CYCLE.join(' ');
      $('shiftName').value = '';
      settingsError(''); renderShiftDefsEditor();
      settingsStatus('已恢复默认，尚未保存。请按实际轮班核对起始日期，再保存设置。');
      $('settingsStatus').focus();
    },{title:'恢复默认排班',accept:'恢复默认',cancel:'保留当前编辑'});
  }
  function readDraftRows() {
    const defs = Object.create(null);
    for (const row of $('shiftDefsEditor').querySelectorAll('.shift-def-row')) {
      const name = row.dataset.shift, style = row.querySelector('select').value;
      const work = row.querySelector('[data-field="isWork"]').checked;
      const oldDef = draftDefs && Core.own(draftDefs,name) ? draftDefs[name] : null;
      defs[name] = { emoji:oldDef?.cssClass === style ? oldDef.emoji : PRESET_ICONS[style],cssClass:style,isWork:work,startTime:work ? row.querySelector('[data-field="startTime"]').value : null,endTime:work ? row.querySelector('[data-field="endTime"]').value : null,crossDay:work && row.querySelector('[data-field="crossDay"]').checked };
    }
    return defs;
  }
  function labeledInput(label, type, name, field, value) {
    const wrapper = mk('label',{text:label}), input = mk('input');
    input.type = type; input.dataset.field = field; input.setAttribute('aria-label',`${name} ${label}`);
    if (type === 'checkbox') input.checked = !!value; else input.value = value || '';
    wrapper.append(input); return [wrapper,input];
  }
  function renderShiftDefsEditor() {
    const rows = [];
    for (const [name,def] of Object.entries(draftDefs || {})) {
      const row = mk('div',{cls:'shift-def-row'}); row.dataset.shift = name;
      const select = mk('select'); select.setAttribute('aria-label',`${name} 颜色样式`);
      for (const style of CSS_PRESETS) { const option = mk('option',{text:`${PRESET_ICONS[style]} ${STYLE_NAMES[style]}`}); option.value = style; select.append(option); } select.value = def.cssClass;
      const remove = mk('button',{cls:'shift-def-delete',text:'×',onclick:()=>removeShiftDef(name)}); remove.setAttribute('aria-label',`删除班次「${name}」`);
      row.append(mk('span',{cls:'shift-def-name',text:`${def.emoji} ${name}`}),select,remove);
      const controls = mk('div',{cls:'shift-def-controls'});
      const [startLabel,start] = labeledInput('上班','time',name,'startTime',def.startTime);
      const [endLabel,end] = labeledInput('下班','time',name,'endTime',def.endTime);
      const [workLabel,work] = labeledInput('工作班次','checkbox',name,'isWork',def.isWork);
      const [crossLabel,cross] = labeledInput('跨日','checkbox',name,'crossDay',def.crossDay);
      start.disabled = end.disabled = cross.disabled = !def.isWork;
      work.addEventListener('change',() => { start.disabled = end.disabled = cross.disabled = !work.checked; if (work.checked) { start.value ||= '09:00'; end.value ||= '17:00'; } });
      controls.append(startLabel,endLabel,workLabel,crossLabel); row.append(controls); rows.push(row);
    }
    $('shiftDefsEditor').replaceChildren(...rows);
    $('cycleQuickAdd').replaceChildren(...Object.entries(draftDefs || {}).map(([name,def]) => {
      const button = mk('button',{cls:`cycle-add ${def.cssClass}`,text:`＋ ${name}`,onclick:() => appendCycleShift(name)});
      button.dataset.cycleShift = name;
      button.setAttribute('aria-label',`向周期末尾添加一天${name}`);
      return button;
    }));
    renderCyclePreview();
  }
  function cycleEntries() { return $('cyclePattern').value.trim().split(/\s+/).filter(Boolean); }
  function appendCycleShift(name) {
    if (!draftDefs || !Core.own(draftDefs,name)) return;
    const cycle = cycleEntries();
    if (cycle.length >= 3660) { settingsError('周期最多 3660 天，不能继续添加。'); return; }
    $('cyclePattern').value = [...cycle,name].join(' ');
    settingsError(''); renderCyclePreview();
  }
  function removeLastCycleShift() {
    if (!draftDefs) return;
    $('cyclePattern').value = cycleEntries().slice(0,-1).join(' ');
    settingsError(''); renderCyclePreview();
  }
  function renderCyclePreview() {
    if (!draftDefs) return;
    settingsStatus();
    const cycle = cycleEntries(), defs = readDraftRows(), anchor = Core.parseDate($('anchorDate').value);
    const unknown = [...new Set(cycle.filter(name => !Core.own(defs,name)))];
    const valid = Core.validCycle(cycle);
    const workDays = cycle.filter(name => Core.own(defs,name) && defs[name].isWork).length;
    const restDays = cycle.filter(name => Core.own(defs,name) && !defs[name].isWork).length;
    for (const button of $('cycleQuickAdd').children) {
      const className = `cycle-add ${defs[button.dataset.cycleShift].cssClass}`;
      if (button.className !== className) button.className = className;
    }
    const summary = cycle.length ? `${cycle.length} 天一循环 · 工作 ${workDays} 天 · 休息 ${restDays} 天${unknown.length ? ' · 有待定义班次' : ''}` : '还没有班次，可点选班次或直接输入';
    if ($('cycleSummary').textContent !== summary) $('cycleSummary').textContent = summary;
    $('cycleRemoveLast').disabled = cycle.length === 0;
    $('cyclePreviewDays').replaceChildren(...cycle.slice(0,16).map((name,index) => {
      const def = Core.own(defs,name) ? defs[name] : null;
      const item = mk('li',{cls:`cycle-preview-day ${def?.cssClass || ''}${def ? '' : ' is-undefined'}`});
      const date = anchor ? schedule.addDays(anchor,index) : null;
      item.append(mk('small',{text:`第 ${index + 1} 天${date ? ` · ${date.getMonth()+1}/${date.getDate()}` : ''}`}),
        mk('strong',{text:name}),mk('span',{text:def ? (def.isWork ? `${def.startTime}–${Core.isCrossDay(def) ? '次日 ' : ''}${def.endTime}` : '休息日') : '待定义'}));
      return item;
    }));
    $('cyclePreviewHint').textContent = !cycle.length ? '添加班次后，这里会显示起始日期对应的安排。'
      : !valid ? '周期最多 3660 天；每个班次名称应为 1–40 个字符。'
      : unknown.length ? `未定义：${unknown.slice(0,6).join('、')}${unknown.length > 6 ? '等' : ''}。保存时会补充定义，请确认时间。`
      : !anchor ? '选择有效的起始日期，即可核对每天的安排。'
      : `预览${cycle.length > 16 ? '前 16 天，完整周期仍会保存；' : '一个完整周期；'}之后从第一个班次重新循环。`;
  }
  $('cyclePattern').addEventListener('input',renderCyclePreview);
  $('anchorDate').addEventListener('input',renderCyclePreview);
  $('shiftDefsEditor').addEventListener('input',renderCyclePreview);
  $('shiftDefsEditor').addEventListener('change',renderCyclePreview);
  function addShiftDef() {
    if (!draftDefs) return;
    const input = $('shiftName'), name = input.value.trim();
    if (!Core.validName(name)) { settingsError('班次名称不能包含空格，且长度应为 1–40 个字符。'); input.focus({preventScroll:true}); return; }
    draftDefs = readDraftRows();
    if (Core.own(draftDefs,name)) { settingsError('这个班次已存在。'); input.focus({preventScroll:true}); return; }
    draftDefs[name] = Core.defaultDef(name); settingsError(''); renderShiftDefsEditor();
    input.value = ''; input.focus({preventScroll:true});
  }
  $('shiftName').addEventListener('keydown',event => {
    if (event.key !== 'Enter') return;
    event.preventDefault();
    if (!event.isComposing && event.keyCode !== 229) addShiftDef();
  });
  function removeShiftDef(name) {
    if (!draftDefs || !Core.own(draftDefs,name)) return;
    ui.confirmAction(`移除班次「${name}」及周期中的对应项？保存设置后生效。`,() => {
      if (!draftDefs) return;
      draftDefs = readDraftRows(); delete draftDefs[name];
      $('cyclePattern').value = $('cyclePattern').value.split(/\s+/).filter(value=>value && value !== name).join(' ');
      renderShiftDefsEditor();
    });
  }
  function saveSettings() {
    if (!draftDefs) return;
    const anchor = $('anchorDate').value, cycle = $('cyclePattern').value.trim().split(/\s+/).filter(Boolean);
    if (!Core.parseDate(anchor)) { settingsError('请选择有效的周期起始日期（1900–2100 年）。'); return; }
    if (!Core.validCycle(cycle)) { settingsError('周期不能为空；最多 3660 天，每个班次名称最多 40 个字符且不含空格。'); return; }
    const defs = readDraftRows();
    for (const [name,def] of Object.entries(defs)) if (def.isWork && (!Core.validTime(def.startTime) || !Core.validTime(def.endTime))) { settingsError(`请填写「${name}」的完整上下班时间。`); return; }
    const missing = [];
    for (const name of cycle) if (!Core.own(defs,name)) { defs[name] = Core.own(DEFAULT_SHIFT_DEFS,name) ? DEFAULT_SHIFT_DEFS[name] : Core.defaultDef(name); missing.push(name); }
    if (missing.length) {
      draftDefs = Core.normalizeDefs(defs); renderShiftDefsEditor(); settingsError(`已为新班次「${[...new Set(missing)].join('、')}」补充时间定义，请确认后再次保存。`); return;
    }
    const persisted = commitConfig({anchor,cycle,shiftDefs:defs}); closeSettings();
    toast(persisted ? '排班设置已保存' : '排班已更新，但浏览器暂时无法保存');
  }
  return { configKey, commitConfig, loadPresets, savePresets, syncActivePreset, saveAsPreset, presetNameError, closePresetDialog, submitPreset, applyPreset, deletePreset, renderPresetBar, restoreBackup, settingsError, openSettings, closeSettings, restoreDefaultSettings, readDraftRows, labeledInput, renderShiftDefsEditor, renderCyclePreview, appendCycleShift, removeLastCycleShift, addShiftDef, removeShiftDef, saveSettings, get presets() { return PRESETS; }, get draftDefs() { return draftDefs; } };
}
