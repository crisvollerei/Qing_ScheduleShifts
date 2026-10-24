/** Android-only reminder page. Reuses the web app's dialog/focus/theme lifecycle. */
export function installReminders(window, app) {
  const native = window.Android;
  if (typeof native?.reminderRequest !== 'function') return null;
  const { document } = window, { ui } = app, { $ } = ui;
  const bell = '<svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/></svg>';
  const launch = ui.mk('button', { cls:'button button-secondary reminder-launch' });
  launch.innerHTML = `${bell}<span>上班提醒</span>`;
  launch.setAttribute('aria-haspopup','dialog');
  document.querySelector('.main-container').append(launch);
  const page = ui.mk('div', { cls:'modal reminder-page' });
  page.id = 'reminderModal'; page.hidden = true;
  page.innerHTML = `
    <section class="modal-content reminder-content" role="dialog" aria-modal="true" aria-labelledby="reminderTitle" tabindex="-1">
      <header class="modal-header"><div><p class="eyebrow">把出发的时间，也安排好</p><h2 id="reminderTitle">上班提醒</h2></div><button type="button" class="icon-btn" id="reminderClose" aria-label="关闭上班提醒">×</button></header>
      <p class="form-description">跟随当前排班，提前为下一次上班留出准备时间。三种方式独立使用，也可以组合。</p>
      <p class="form-error" id="reminderError" role="alert" tabindex="-1" hidden></p>
      <section class="reminder-card reminder-owned" aria-labelledby="ownedTitle">
        <div class="reminder-heading"><div><span class="section-label">01 / 持续自动管理</span><h3 id="ownedTitle">本应用闹钟</h3></div><span class="reminder-badge" id="reminderStatus" role="status">读取中</span></div>
        <form id="reminderForm">
          <label class="reminder-toggle"><span><strong>启用上班闹钟</strong><small>排班改变后自动更新，关闭后取消本应用闹钟</small></span><input type="checkbox" id="reminderEnabled" role="switch"><span class="reminder-switch" aria-hidden="true"></span></label>
          <div class="form-group"><label for="reminderLead">上班前多少分钟提醒</label><div class="reminder-lead-row"><input id="reminderLead" type="number" min="0" max="1440" step="1" inputmode="numeric" required value="60"><span>分钟</span></div><div class="reminder-presets" role="group" aria-label="常用提前量"><button type="button" class="button button-secondary button-small" data-lead="15">15 分钟</button><button type="button" class="button button-secondary button-small" data-lead="30">30 分钟</button><button type="button" class="button button-secondary button-small" data-lead="60">1 小时</button><button type="button" class="button button-secondary button-small" data-lead="120">2 小时</button></div><small>支持 0–1440 分钟。以下系统时钟与日历入口也使用已保存的提前量。</small></div>
          <div class="reminder-sound"><label><input type="checkbox" id="reminderSound" checked> 铃声</label><label><input type="checkbox" id="reminderVibrate" checked> 振动</label><button type="button" class="button button-secondary button-small reminder-action" id="reminderRingtone">选择铃声</button></div>
          <div class="reminder-next"><span class="section-label">已登记的下一次提醒</span><strong id="reminderNext">正在读取</strong><span id="reminderShift"></span></div>
          <div class="reminder-permissions" id="reminderPermissions"></div>
          <div class="reminder-actions"><button type="submit" class="button button-primary reminder-action">保存设置</button><button type="button" class="button button-secondary reminder-action" id="reminderTest">30 秒后测试</button><button type="button" class="button button-secondary button-small reminder-action" id="reminderCancelTest" hidden>取消测试</button></div>
        </form>
        <p class="form-note" id="reminderSavedLead">读取设置后可操作。</p>
        <details class="reminder-help"><summary>提醒可靠性与后台设置</summary><p>使用系统精确闹钟，不依赖网页或小组件保持运行。响铃最多持续 5 分钟，可从通知或响铃页停止。请检查闹钟音量与勿扰设置。</p><p>强行停止、应用冻结、撤销权限可能使提醒失效；手机关机时不能保证响铃。重启后需完成首次解锁才能恢复安排。已错过的提醒不会立即补响。</p><button type="button" class="button button-secondary button-small reminder-action" id="reminderBattery">打开应用后台设置</button><p>测试成功不代表锁屏过夜一定可靠，建议在自己的手机上再验证。</p></details>
      </section>
      <section class="reminder-card" aria-labelledby="clockTitle"><div class="reminder-heading"><div><span class="section-label">02 / 交给手机时钟</span><h3 id="clockTitle">系统时钟闹钟</h3></div>${bell}</div><p class="form-note">为下次上班计算时间，再打开系统时钟确认。创建后由系统时钟管理，不随上方开关取消。</p><p class="reminder-caution">通用接口不能指定日历日期或截止日。请核对日期；如果手机时钟不能选择目标日期，请取消，改用本应用闹钟或日历。</p><button type="button" class="button button-secondary reminder-action" id="reminderClock">核对下次提醒并打开时钟 ↗</button></section>
      <section class="reminder-card" aria-labelledby="calendarTitle"><div class="reminder-heading"><div><span class="section-label">03 / 按日期一次生成</span><h3 id="calendarTitle">系统日历提醒</h3></div><span class="reminder-badge">不自动续期</span></div><p class="form-note">按上班日期选择范围，休息日不生成。日历提醒通常是通知，不保证持续响铃。</p>
        <div class="reminder-actions"><button type="button" class="button button-secondary button-small reminder-action" id="reminderCalendarPermission">允许日历访问</button></div>
        <div class="form-group"><label for="reminderCalendar">保存到日历</label><select id="reminderCalendar" disabled><option value="">授权后选择可写日历</option></select><small>日程可能由所选日历账户同步到其他设备。</small></div>
        <div class="reminder-range"><div class="form-group"><label for="reminderRange">生成范围（从明天起）</label><select id="reminderRange"><option value="1">仅明天</option><option value="7" selected>未来七天</option><option value="custom">自定义天数</option></select></div><div class="form-group" id="reminderDaysGroup" hidden><label for="reminderDays">天数</label><input id="reminderDays" type="number" inputmode="numeric" min="1" max="90" step="1" value="14"><small>1–90 天，不会自动补齐。</small></div></div>
        <button type="button" class="button button-secondary reminder-action" id="reminderPreview">预览日历提醒</button>
        <div class="reminder-export" id="reminderExport" hidden><p id="reminderExportSummary" role="status" tabindex="-1"></p><ol id="reminderExportList"></ol><p class="form-note">重新生成会更新所选范围内本应用管理的事件，并移除其中不再上班的事件；其他日程不受影响。排班之后有变动时，需要再次生成。</p><button type="button" class="button button-primary reminder-action" id="reminderGenerate">确认生成 / 更新</button></div>
        <div class="reminder-cleanup"><span class="form-note" id="reminderCalendarCount"></span><button type="button" class="button button-secondary button-small reminder-action" id="reminderClear">清理本应用导出的事件</button></div>
      </section>
      <p class="form-note">组合使用可能收到重复提醒。关闭本应用闹钟，不会删除系统时钟或日历中的记录。</p>
    </section><div class="toast reminder-feedback" id="reminderFeedback" role="status" hidden></div>`;
  document.body.append(page);
  let serial = 0, epoch = 0, state = null, plan = null, busy = false, dirty = false, feedbackTimer;
  const disabledControls = new Map();
  const pending = new Map();
  function request(action, payload = {}) {
    return new Promise((resolve,reject) => {
      const id = `reminder_${++serial}`;
      const timer = window.setTimeout(() => { pending.delete(id); reject(new Error('操作超时，请重新打开页面核对结果，勿重复生成')); },20000);
      pending.set(id,{resolve,reject,timer});
      try { native.reminderRequest(id,action,JSON.stringify(payload)); }
      catch (_) { window.clearTimeout(timer); pending.delete(id); reject(new Error('无法连接安卓提醒功能，请重试')); }
    });
  }
  function receive(id, result) {
    const entry = pending.get(id);
    if (!entry) return;
    pending.delete(id); window.clearTimeout(entry.timer);
    if (result?.ok) entry.resolve(result.data);
    else entry.reject(new Error(result?.error || '操作未完成'));
  }
  function invalidatePlan() { plan = null; $('reminderExport').hidden = true; }
  function changed() { dirty = true; invalidatePlan(); }
  function error(message) {
    $('reminderError').textContent = message; $('reminderError').hidden = false;
    $('reminderError').focus({preventScroll:false});
  }
  // Input pages deliberately hide body siblings, including the app-wide toast.
  function notify(message) {
    window.clearTimeout(feedbackTimer);
    $('reminderFeedback').textContent = message; $('reminderFeedback').hidden = false;
    feedbackTimer = window.setTimeout(() => { $('reminderFeedback').hidden = true; },4500);
  }
  function lock(value) {
    busy = value; page.setAttribute('aria-busy',String(value));
    for (const button of page.querySelectorAll('.reminder-action')) button.disabled = value;
    if (value) {
      for (const node of page.querySelectorAll('input,select,[data-lead]')) {
        disabledControls.set(node,node.disabled); node.disabled = true;
      }
    } else {
      for (const [node,disabled] of disabledControls) node.disabled = disabled;
      disabledControls.clear();
      $('reminderCalendar').disabled = !state?.calendarAllowed || !$('reminderCalendar').value;
    }
  }
  async function run(task) {
    if (busy) return;
    const version = epoch;
    lock(true); $('reminderError').hidden = true;
    try { await task(() => version === epoch && !page.hidden); }
    catch (problem) { if (version === epoch && !page.hidden) error(problem.message); }
    finally { if (version === epoch) lock(false); }
  }
  function fillForm() {
    $('reminderEnabled').checked = state.enabled; $('reminderLead').value = String(state.lead);
    $('reminderSound').checked = state.sound; $('reminderVibrate').checked = state.vibrate;
    dirty = false;
  }
  function render() {
    $('reminderStatus').textContent = state.status;
    const next = state.scheduled;
    $('reminderNext').textContent = next ? next.fireText : state.enabled ? state.status : '尚未启用';
    $('reminderShift').textContent = next ? `${next.startText} · ${next.name}`
      : state.next ? `下次可提醒：${state.next.fireText} · ${state.next.name}` : '当前周期暂无可提醒的工作班次';
    $('reminderSavedLead').textContent = `已保存：上班前 ${state.lead} 分钟。${state.testFire ? '测试闹钟已安排，退出页面或锁屏后也可测试。' : '设置修改后请保存。'}`;
    $('reminderCancelTest').hidden = !state.testFire;
    $('reminderCalendarPermission').hidden = state.calendarAllowed;
    $('reminderCalendarCount').textContent = `本机管理记录 ${state.calendarCount} 条（实际事件可能已被手动删除）。`;
    $('reminderPermissions').replaceChildren();
    for (const [allowed,label,action] of [[state.exact,'允许精确闹钟','permissionExact'],[state.notifications,'允许闹钟通知','permissionNotifications'],[state.fullScreen,'允许锁屏全屏显示（可选）','permissionFullScreen']]) {
      if (allowed) continue;
      const button = ui.mk('button',{cls:'button button-secondary button-small reminder-action',text:label});
      button.addEventListener('click',() => run(async valid => { await request(action); if (valid()) notify('请在系统中完成设置，返回后自动检查'); }));
      $('reminderPermissions').append(button);
    }
    if (state.error) error(state.error);
    if (state.lastFailure) error(`最近一次响铃异常：${state.lastFailure}`);
  }
  async function loadCalendars(valid) {
    if (!state.calendarAllowed) return;
    const result = await request('calendars'); if (!valid()) return;
    const selected = $('reminderCalendar').value;
    $('reminderCalendar').replaceChildren();
    for (const calendar of result.calendars) {
      const option = document.createElement('option'); option.value = String(calendar.id);
      option.textContent = calendar.account ? `${calendar.name} · ${calendar.account}` : calendar.name;
      $('reminderCalendar').append(option);
    }
    $('reminderCalendar').disabled = !result.calendars.length;
    if (result.calendars.some(item => String(item.id) === selected)) $('reminderCalendar').value = selected;
    if (!result.calendars.length) {
      const option = document.createElement('option'); option.value = ''; option.textContent = '没有可写日历，请先在系统日历中添加';
      $('reminderCalendar').append(option);
    }
  }
  function refresh() {
    if (page.hidden || busy) return;
    return run(async valid => {
      const result = await request('state'); if (!valid()) return;
      const oldLead = state?.lead;
      state = result; render();
      if (!dirty) fillForm();
      if (oldLead !== state.lead) invalidatePlan();
      await loadCalendars(valid);
    });
  }
  function open() {
    if (ui.activeDialog === page.id) return;
    epoch++; state = null; dirty = false; invalidatePlan(); lock(false);
    $('reminderError').hidden = true;
    $('reminderFeedback').hidden = true;
    ui.openDialog(page.id); refresh();
  }
  function close() { epoch++; ui.closeDialog(page.id); lock(false); }
  launch.addEventListener('click',open);
  $('reminderClose').addEventListener('click',close); ui.onDismiss(page.id,close);
  page.addEventListener('click',event => { if (event.target === page) close(); });
  for (const id of ['reminderLead','reminderEnabled','reminderSound','reminderVibrate']) $(id).addEventListener('input',changed);
  for (const button of page.querySelectorAll('[data-lead]')) button.addEventListener('click',() => { $('reminderLead').value = button.dataset.lead; changed(); });
  $('reminderForm').addEventListener('submit',event => {
    event.preventDefault();
    run(async valid => {
      const lead = Number($('reminderLead').value);
      if ($('reminderLead').value.trim() === '' || !Number.isInteger(lead) || lead < 0 || lead > 1440) throw new Error('提前量须为 0–1440 分钟的整数');
      const result = await request('save',{lead,enabled:$('reminderEnabled').checked,sound:$('reminderSound').checked,vibrate:$('reminderVibrate').checked,theme:document.documentElement.dataset.theme});
      if (!valid()) return;
      state = result; fillForm(); render(); invalidatePlan();
      notify(state.enabled ? `已保存 · ${state.status}` : '已保存，本应用闹钟已关闭');
    });
  });
  const simple = (id,action,message) => $(id).addEventListener('click',() => run(async valid => {
    await request(action); if (valid()) notify(message);
    if (valid() && ['test','cancelTest'].includes(action)) { state = await request('state'); if (valid()) render(); }
  }));
  simple('reminderRingtone','ringtone','请在系统中选择闹钟铃声');
  simple('reminderTest','test','测试闹钟将在 30 秒后响起，使用已保存的铃声与振动设置');
  simple('reminderCancelTest','cancelTest','已取消测试闹钟');
  simple('reminderBattery','battery','请检查电池限制、后台运行与自启动设置');
  simple('reminderCalendarPermission','permissionCalendar','请允许日历访问，返回后选择日历');
  $('reminderRange').addEventListener('change',() => { $('reminderDaysGroup').hidden = $('reminderRange').value !== 'custom'; invalidatePlan(); });
  $('reminderDays').addEventListener('input',invalidatePlan); $('reminderCalendar').addEventListener('change',invalidatePlan);
  const requireSaved = () => { if (dirty) throw new Error('请先保存上方提醒设置，再核对系统时钟或日历提醒'); };
  $('reminderClock').addEventListener('click',() => run(async valid => {
    requireSaved(); const preview = await request('clockPreview'); if (!valid()) return;
    const {entry} = preview;
    ui.confirmAction(`目标提醒：${entry.fireText}；上班：${entry.startText} · ${entry.name}。系统时钟可能默认今天或最近一次，请务必核对日期；不能选择目标日期时请取消。创建后不随本应用开关或排班变化自动取消。`,() => run(async current => {
      await request('clock',{token:preview.token}); if (current()) notify('已打开系统时钟，请确认日期并保存；尚不能认定闹钟已创建');
    }),{title:'核对系统闹钟日期',accept:'打开系统时钟'});
  }));
  $('reminderPreview').addEventListener('click',() => run(async valid => {
    requireSaved(); invalidatePlan();
    const calendar = Number($('reminderCalendar').value), days = Number($('reminderRange').value === 'custom' ? $('reminderDays').value : $('reminderRange').value);
    if (!calendar) throw new Error('请先授权并选择可写日历');
    if (!Number.isInteger(days) || days < 1 || days > 90) throw new Error('天数须为 1–90 的整数');
    const preview = await request('calendarPreview',{calendar,days}); if (!valid()) return;
    plan = {...preview,calendar,days};
    $('reminderExportSummary').textContent = `从明天起 ${days} 天，${preview.entries.length} 个工作班次；提前 ${preview.lead} 分钟。${preview.skipped ? `另有 ${preview.skipped} 个班次的提醒时间已过，跳过。` : ''}`;
    $('reminderExportList').replaceChildren();
    for (const entry of preview.entries) $('reminderExportList').append(ui.mk('li',{text:`${entry.startText} · ${entry.name} → ${entry.fireText} 提醒`}));
    $('reminderExport').hidden = false; $('reminderExportSummary').focus({preventScroll:false});
  }));
  $('reminderGenerate').addEventListener('click',() => {
    if (!plan || busy) return;
    const confirmed = plan;
    ui.confirmAction('将按预览生成或更新日历事件，并清理所选范围内不再上班的本应用事件。不会自动续期；上方闹钟开关不控制这些事件。',() => run(async valid => {
      if (confirmed !== plan || dirty) throw new Error('设置已变化，请重新预览');
      const result = await request('calendarExport',{calendar:plan.calendar,days:plan.days,token:plan.token});
      if (!valid()) return;
      invalidatePlan(); notify(`日历已处理：新增 ${result.created}、更新 ${result.updated}、移除 ${result.removed} 条`);
      state = await request('state'); if (valid()) render();
    }),{title:'生成日历提醒',accept:'确认生成 / 更新'});
  });
  $('reminderClear').addEventListener('click',() => {
    if (busy) return;
    ui.confirmAction('清理本机仍能识别的全部本应用导出事件，包括过去的事件。不会删除其他日程，也不会取消系统时钟或本应用闹钟。',() => run(async valid => {
      const result = await request('calendarClear'); if (!valid()) return;
      invalidatePlan(); notify(`已清理 ${result.removed} 条日历事件`);
      state = await request('state'); if (valid()) render();
    }),{title:'清理日历事件',accept:'确认清理'});
  });
  window.QingZhengReminders = {open,refresh,receive};
  return window.QingZhengReminders;
}
