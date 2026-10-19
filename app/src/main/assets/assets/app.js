import { createStorage } from './storage.js?v=12c75b2d9435';
import { createUI } from './ui.js?v=4df59f3744d9';
import { createSchedule } from './schedule.js?v=aab834ceb702';
import { createFestivals } from './festivals.js?v=4d4197fe0e0a';
import { createViews } from './views.js?v=0e7debd28de1';
import { createCompanion } from './companion.js?v=55425bc50cdc';
import { createSettings } from './settings.js?v=2b4ec1549f42';
import { createDataTools } from './data-tools.js?v=d1fd52e5a6f6';
import { createPersonalization } from './personalization.js?v=31f9a0dac8f4';
export function createApp(window) {
  const { document, Date } = window;
  const storage = createStorage(window);
  const ui = createUI(window);
  const schedule = createSchedule(storage,Date);
  const festivals = createFestivals({window,ui,schedule});
  const views = createViews({window,ui,schedule,festivals,onUpdateLive:updateLiveClockAndCountdown});
  const companion = createCompanion({window,ui,schedule});
  const settings = createSettings({window,ui,schedule,views,storage});
  const personalization = createPersonalization({window,ui,storage});
  const dataTools = createDataTools({window,ui,schedule,settings,personalization});
  const { $, mk, reveal, toast } = ui;
  const { getShiftDef, getCycleIndex, getLunarDate, formatDateISO, getWeekdayCn, shiftTimeText, getEffectiveShiftInfo, formatCountdown, getNextWorkDelay, getDaysToRest } = schedule;
  const { getHoliday, renderFestivalNotice } = festivals;
  const { updateCompanion, syncCompanionTheme } = companion;
  const { render } = views;
  let liveTimer = null, lastDayKey = '', lastTodayShiftKey = '', lastTodayCardKey = '', lastTodayFestival = null, automaticTheme = false;
  // Keep the effective shift, its anchor day and the progress bar consistent at midnight.
  function updateTodayCardDisplay(info, now = new Date()) {
    const def = getShiftDef(info.shift);
    const position = getCycleIndex(info.date) + 1;
    const cardKey = JSON.stringify([formatDateISO(now),info.shift,info.isCrossDay,position,schedule.revision]);
    if (cardKey === lastTodayCardKey) return;
    lastTodayCardKey = cardKey;
    const shiftKey = JSON.stringify([info.shift,def.emoji,def.cssClass]);
    if (lastTodayShiftKey !== shiftKey) {
      $('todayShift').replaceChildren(mk('span', { cls:'today-shift-emoji', text:def.emoji }), document.createTextNode(info.shift));
      $('todayShift').className = `today-shift ${def.cssClass}`;
      lastTodayShiftKey = shiftKey;
      reveal($('todayShift'));
    }
    const lunar = getLunarDate(now);
    $('todayDate').textContent = `${now.getMonth() + 1}月${now.getDate()}日 ${getWeekdayCn(now)}${lunar.day ? ` · 农历${lunar.monthStr}${lunar.dayStr}` : ''}`;
    const festival = getHoliday(now);
    if (festival !== lastTodayFestival) {
      renderFestivalNotice($('todayFestival'),festival);
      document.querySelector('.today-card').classList.toggle('has-festival',!!festival);
      lastTodayFestival = festival;
    }
    $('todayShiftTime').textContent = `${info.isCrossDay ? '昨日班次 · ' : ''}${shiftTimeText(info.shift)}`;
    $('todayCycle').textContent = position;
    $('cycleLength').textContent = schedule.cycle.length;
    const progress = Math.round(position / schedule.cycle.length * 100);
    $('progressBar').style.width = `${progress}%`;
    $('cycleProgress').setAttribute('aria-valuenow', progress);
    $('cycleProgress').setAttribute('aria-valuetext', `周期第${position}天，共${schedule.cycle.length}天`);
    const days = getDaysToRest(now);
    $('restCountdown').textContent = days === 0 ? (info.isCrossDay ? '本班结束后' : '就是今天') : days < 0 ? '周期内无休息日' : `${days} 天后`;
  }
  function updateLiveClockAndCountdown() {
    if (ui.activeDialog || document.hidden) return;
    const now = new Date();
    if (automaticTheme) {
      const theme = themeForTime(now);
      if (document.documentElement.dataset.theme !== theme) setTheme(theme,false);
    }
    const dayKey = formatDateISO(now);
    if (lastDayKey && lastDayKey !== dayKey) {
      lastDayKey = dayKey;
      render();
      return;
    }
    lastDayKey = dayKey;
    $('liveClock').textContent = now.toLocaleTimeString('zh-CN', {hour12:false});
    $('liveClock').dateTime = now.toISOString();
    const info = getEffectiveShiftInfo(now);
    const next = getNextWorkDelay(now);
    setText('countdownToWork',info.start && now >= info.start && now < info.end ? '正在上班' : next === null ? '暂无工作班' : formatCountdown(next));
    setText('countdownToOff',!info.start ? '今日休息' : now < info.end ? formatCountdown(info.end - now) : '已下班');
    updateTodayCardDisplay(info, now);
    updateCompanion(now, info, next);
  }
  function resumeLiveUpdates() {
    if (liveTimer === null) liveTimer = window.setInterval(updateLiveClockAndCountdown,1000);
  }
  function setText(id, value) { if ($(id).textContent !== value) $(id).textContent = value; }
  function setTheme(theme, save = true) {
    document.documentElement.dataset.theme = theme;
    $('themeIcon').textContent = theme === 'light' ? '☾' : '☀';
    $('themeToggle').setAttribute('aria-label',`切换为${theme === 'light' ? '深色' : '浅色'}主题`);
    document.querySelector('meta[name="theme-color"]').content = theme === 'light' ? '#f0f0f7' : '#101b2d';
    $('themeMood').textContent = theme === 'light' ? '银昼' : '蓝夜';
    syncCompanionTheme(theme);
    if (save) {
      automaticTheme = false;
      storage.set('qingzheng-theme',theme);
      storage.set('qingzheng-theme-mode','manual');
    }
    $('themeAuto').setAttribute('aria-pressed',String(automaticTheme));
    $('themeAuto').setAttribute('aria-label',`${automaticTheme ? '关闭' : '启用'}昼夜自动主题`);
    $('themeAuto').title = automaticTheme
      ? '自动主题已开启：07:00–19:00 银昼，其余时间蓝夜；点击关闭并保持当前主题'
      : '自动主题已关闭；点击启用：07:00–19:00 银昼，其余时间蓝夜';
  }
  function themeForTime(date) { return date.getHours() >= 7 && date.getHours() < 19 ? 'light' : 'dark'; }
  function enableAutomaticTheme() {
    automaticTheme = true;
    storage.set('qingzheng-theme-mode','auto');
    setTheme(themeForTime(new Date()),false);
    toast('已启用昼夜主题，07:00–19:00 为银昼，其余时间为蓝夜');
  }
  function toggleAutomaticTheme() {
    if (!automaticTheme) { enableAutomaticTheme(); return; }
    const theme = document.documentElement.dataset.theme;
    setTheme(theme);
    toast(`已关闭自动主题，保持${theme === 'light' ? '银昼' : '蓝夜'}`);
  }
  $('themeToggle').addEventListener('click',() => setTheme(document.documentElement.dataset.theme === 'light' ? 'dark' : 'light'));
  $('backToToday').addEventListener('click',views.backToToday);
  document.addEventListener('visibilitychange',() => {
    document.documentElement.classList.toggle('page-hidden',document.hidden);
    if (!document.hidden) updateLiveClockAndCountdown();
  });
  document.addEventListener('keydown',event => {
    if (ui.activeDialog || event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || event.target.closest?.('input,textarea,select,[contenteditable="true"],#companionMessage')) return;
    if (event.key === 'ArrowLeft') { event.preventDefault(); views.prevMonth(); }
    else if (event.key === 'ArrowRight') { event.preventDefault(); views.nextMonth(); }
    else if (event.key.toLowerCase() === 't') { event.preventDefault(); views.backToToday(); }
    else if (event.key === 'Escape') views.closeDetail();
  });

  function init() {
    const now = new Date();
    const savedTheme = storage.get('qingzheng-theme');
    const savedMode = storage.get('qingzheng-theme-mode');
    automaticTheme = savedMode === 'auto' || !['light','dark'].includes(savedTheme);
    setTheme(automaticTheme ? themeForTime(now) : savedTheme === 'dark' ? 'dark' : 'light',false);
    personalization.load(); settings.loadPresets(); storage.updateNotice(); render();
    $('companion').hidden = false;
    document.documentElement.classList.toggle('page-hidden',document.hidden);
    ui.observeAmbientMotion();
    document.querySelectorAll('.workspace-intro,.enter-stage').forEach((element,index) => {
      reveal(element,{duration:450,delay:Math.min(index,5)*45,fill:'backwards'});
    });
    resumeLiveUpdates();
  }
  ui.setLifecycle({pause() { window.clearInterval(liveTimer); liveTimer = null; }, resume() { updateLiveClockAndCountdown(); resumeLiveUpdates(); }});
  ui.onDismiss('settingsModal',settings.closeSettings);
  ui.onDismiss('presetModal',settings.closePresetDialog);
  const actions = {
    openYearOverview: views.openYearOverview, openPicker: views.openPicker, closePicker: views.closePicker,
    prevMonth: views.prevMonth, nextMonth: views.nextMonth, closeDetail: views.closeDetail,
    prevDetail: () => views.moveDetail(-1), nextDetail: () => views.moveDetail(1),
    pickerPrevYear: views.pickerPrevYear, pickerNextYear: views.pickerNextYear,
    saveAsPreset: settings.saveAsPreset, closePresetDialog: settings.closePresetDialog,
    openSettings: settings.openSettings, closeSettings: settings.closeSettings,
    addShiftDef: settings.addShiftDef, saveSettings: settings.saveSettings,
    restoreDefaultSettings: settings.restoreDefaultSettings,
    removeLastCycleShift: settings.removeLastCycleShift,
    openDataTools: dataTools.openDataTools, closeDataTools: dataTools.closeDataTools,
    openPersonalization: personalization.openPersonalization, closePersonalization: personalization.closePersonalization,
    resetAvatar: personalization.resetAvatar, resetTitle: personalization.resetTitle,
    exportBackup: dataTools.exportBackup, importBackup: dataTools.importBackup,
    copyBackupText: dataTools.copyBackupText, selectBackupText: dataTools.selectBackupText,
    previewBackupText: dataTools.previewBackupText, toggleAutomaticTheme
  };
  for (const button of document.querySelectorAll('[data-action]')) {
    button.addEventListener('click',() => actions[button.dataset.action]?.());
  }
  init();
  return { setTheme, enableAutomaticTheme, toggleAutomaticTheme, updateLiveClockAndCountdown, schedule, views, settings, dataTools, personalization, ui, festivals, companion, get liveTimer() { return liveTimer; }, get lastDayKey() { return lastDayKey; } };
}
