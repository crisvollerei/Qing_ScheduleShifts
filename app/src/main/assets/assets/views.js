import Core from './schedule-core.js?v=fc384b3c941b';
import { FESTIVAL_ART } from './festivals.js?v=4d4197fe0e0a';
export function createViews({window, ui, schedule, festivals, onUpdateLive}) {
  const { document, Date } = window;
  const { $, mk, animateElement, motionAllowed, reveal, openDialog, closeDialog } = ui;
  const { getShiftDef, isWorkShift, getCycleIndex, getShift, getLunarDate, formatDateISO, formatDate, getWeekdayCn, isSameDay, addDays, shiftTimeText, getDaysToRest, getUniqueShiftsInCycle, getWorkShifts, calculateNextShifts, calculateMonthlyStats, calculateWorkHours, getConsecutiveShiftDays } = schedule;
  const { getSolarTerm, getHoliday, festivalParts, makeFestivalArt, renderFestivalNotice } = festivals;
  const now = new Date();
  let viewYear = now.getFullYear(), viewMonth = now.getMonth(), selectedDate = null, pickerYear, lastRenderedMonth = null;
  let detailTrigger = null, detailReturnDate = null, detailReturnSource = null;
  function renderStats() {
    const stats = calculateMonthlyStats(viewYear, viewMonth);
    const previous = calculateMonthlyStats(viewYear, viewMonth - 1);
    const shifts = getUniqueShiftsInCycle();
    const totalDays = new Date(viewYear, viewMonth + 1, 0).getDate();
    $('statsMonth').textContent = `${viewYear}.${String(viewMonth + 1).padStart(2,'0')}`;
    $('statsTitle').textContent = `${viewMonth + 1}月概览`;
    const cards = shifts.map(name => {
      const def = getShiftDef(name), count = stats[name] || 0, difference = count - (previous[name] || 0);
      const card = mk('div', { cls:`stat-card ${def.cssClass}` });
      const change = difference === 0 ? '较上月持平' : `较上月 ${difference > 0 ? '+' : ''}${difference} 天`;
      const label = mk('div', {cls:'stat-label'});
      const dot = mk('span', {cls:'legend-dot'}); dot.setAttribute('aria-hidden','true');
      label.append(dot, mk('span', {text:name}));
      const value = mk('div', {cls:'stat-value', text:count});
      value.append(mk('span', {cls:'stat-unit', text:'天'}));
      card.append(label, value, mk('div', {cls:'stat-trend', text:change}));
      return card;
    });
    $('statsContainer').replaceChildren(...cards);
    $('statsContainer').style.gridTemplateColumns = `repeat(${Math.min(shifts.length, 3)},minmax(0,1fr))`;
    $('workHours').textContent = calculateWorkHours(viewYear, viewMonth);
    const workDays = shifts.reduce((sum, name) => sum + (isWorkShift(name) ? stats[name] || 0 : 0), 0);
    const restDays = totalDays - workDays, workPercent = Math.round(workDays / totalDays * 100);
    $('monthDays').textContent = `全月 ${totalDays} 天`;
    $('workDays').textContent = `${workDays} 天`;
    $('restDays').textContent = `${restDays} 天`;
    $('workPercent').textContent = `${workPercent}%`;
    $('restPercent').textContent = `${100 - workPercent}%`;
    // Segment geometry follows exact day counts, independently of rounded labels.
    $('workBalanceWork').style.flexGrow = workDays;
    $('workBalanceRest').style.flexGrow = restDays;
    $('workBalanceWork').hidden = workDays === 0;
    $('workBalanceRest').hidden = restDays === 0;
    $('workBalance').setAttribute('aria-label', `${viewMonth + 1}月共${totalDays}天，工作${workDays}天，占${workPercent}%；休息${restDays}天，占${100 - workPercent}%`);
    $('monthMeta').textContent = `共 ${totalDays} 天 · 工作 ${workDays} 天`;
  }
  function renderLegend() {
    $('legendContainer').replaceChildren(...getUniqueShiftsInCycle().map(name => {
      const def = getShiftDef(name), item = mk('div', {cls:'legend-item'});
      item.title = `${name} · ${shiftTimeText(name)}`;
      item.append(mk('span', {cls:`legend-dot ${def.cssClass}`}), mk('span', {text:name})); return item;
    }));
  }
  function renderCalendar() {
    $('monthTitle').textContent = `${viewYear} 年 ${viewMonth + 1} 月`;
    $('prevBtn').disabled = viewYear === minYear() && viewMonth === 0;
    $('nextBtn').disabled = viewYear === maxYear() && viewMonth === 11;
    const fragment = document.createDocumentFragment();
    const firstDow = (new Date(viewYear, viewMonth, 1).getDay() + 6) % 7;
    const daysInMonth = new Date(viewYear,viewMonth + 1,0).getDate();
    $('daysGrid').style.setProperty('--week-rows',Math.ceil((firstDow + daysInMonth) / 7));
    function appendAdjacent(date) {
      const empty = mk('div',{cls:'day-cell empty'});
      empty.setAttribute('aria-hidden','true');
      empty.append(mk('span',{cls:'day-num',text:date.getDate()}),mk('span',{cls:'adjacent-month',text:`${date.getMonth()+1}月`}));
      fragment.append(empty);
    }
    for (let i = 0; i < firstDow; i++) appendAdjacent(new Date(viewYear,viewMonth,1 - firstDow + i));
    for (let d = 1; d <= new Date(viewYear, viewMonth + 1, 0).getDate(); d++) {
      const date = new Date(viewYear, viewMonth, d), name = getShift(date), def = getShiftDef(name);
      const today = isSameDay(date, new Date()), selected = selectedDate && isSameDay(date, selectedDate);
      const lunar = getLunarDate(date), term = getSolarTerm(date), festival = getHoliday(date);
      const cell = mk('button', {cls:`day-cell shift-${def.cssClass}${today ? ' today' : ''}${selected ? ' selected' : ''}`, onclick:event => showDetail(date,event.currentTarget)});
      cell.dataset.date = formatDateISO(date);
      cell.setAttribute('aria-label', `${formatDate(date)} ${getWeekdayCn(date)} ${name} ${shiftTimeText(name)}${lunar.day ? ` 农历${lunar.monthStr}${lunar.dayStr}` : ''}${term ? ` ${term}` : ''}${festival ? ` ${festival}` : ''}`);
      cell.setAttribute('aria-pressed', String(!!selected));
      if (today) cell.setAttribute('aria-current','date');
      const heading = mk('span',{cls:'day-heading'});
      heading.append(mk('span',{cls:'day-num',text:d}));
      if (today) heading.append(mk('span',{cls:'today-label',text:'今'}));
      if (festival) {
        cell.classList.add('is-festival');
        cell.dataset.festival = festival;
        const parts = festivalParts(festival);
        cell.classList.toggle('festival-combined',parts.length > 1);
        if (parts.length) cell.dataset.festivalPalette = FESTIVAL_ART[parts[0]].palette;
        cell.append(makeFestivalArt(festival));
      }
      const label = mk('span', {cls:'day-shift'});
      label.append(mk('span',{cls:`shift-dot ${def.cssClass}`}),mk('span',{cls:'day-shift-label',text:name}));
      cell.append(heading,label,mk('span',{cls:`lunar-date${term ? ' solar-term' : ''}`,text:term || (lunar.day === 1 ? lunar.monthStr : lunar.dayStr)}));
      if (festival) cell.append(mk('span',{cls:'holiday-badge',text:festival}));
      fragment.append(cell);
    }
    const trailingDays = (7 - (firstDow + daysInMonth) % 7) % 7;
    for (let i = 1; i <= trailingDays; i++) appendAdjacent(new Date(viewYear,viewMonth + 1,i));
    $('daysGrid').replaceChildren(fragment);
    const monthKey = viewYear * 12 + viewMonth;
    if (lastRenderedMonth !== null && lastRenderedMonth !== monthKey) {
      const direction = monthKey > lastRenderedMonth ? 1 : -1;
      animateElement($('daysGrid'),[{opacity:.15,transform:`translateX(${direction * 14}px)`},{opacity:1,transform:'translateX(0)'}]);
      animateElement($('statsContainer'),[{opacity:.3,transform:'translateY(5px)'},{opacity:1,transform:'translateY(0)'}]);
    }
    lastRenderedMonth = monthKey;
  }
  function renderHeatmap() {
    $('heatmapTitle').textContent = `${viewYear} 年度排班总览`;
    const months = [];
    for (let m = 0; m < 12; m++) {
      const month = mk('button',{cls:`heatmap-month${m === viewMonth ? ' viewed-month' : ''}`,onclick:event => {
        const day = event.target.closest('[data-date]')?.dataset.date;
        navigateMonth(viewYear,m);
        if (day) showDetail(Core.parseDate(day),event.currentTarget);
        if (!day || window.innerWidth > 640) $('calendarSection').scrollIntoView?.({behavior:motionAllowed() ? 'smooth' : 'auto',block:'start'});
      }});
      month.setAttribute('aria-label',`查看${viewYear}年${m + 1}月排班`);
      month.setAttribute('aria-pressed',String(m === viewMonth));
      const weekdays = mk('span',{cls:'mini-cal-weekdays'}); weekdays.setAttribute('aria-hidden','true');
      for (const label of ['一','二','三','四','五','六','日']) weekdays.append(mk('span',{cls:'mini-cal-wd',text:label}));
      const grid = mk('span',{cls:'mini-cal-grid'}); grid.setAttribute('aria-hidden','true');
      const start = (new Date(viewYear,m,1).getDay() + 6) % 7;
      for (let i = 0; i < start; i++) grid.append(mk('span',{cls:'mini-cal-day empty'}));
      for (let d = 1; d <= new Date(viewYear,m + 1,0).getDate(); d++) {
        const date = new Date(viewYear,m,d);
        const day = mk('span',{cls:`mini-cal-day shift-${getShiftDef(getShift(date)).cssClass}${isSameDay(date,new Date()) ? ' today-marker' : ''}`});
        day.dataset.date = formatDateISO(date);
        day.title = `${m + 1}月${d}日 · ${getShift(date)}`;
        grid.append(day);
      }
      month.append(weekdays,grid,mk('span',{cls:'heatmap-month-label',text:`${m + 1}月`})); months.push(month);
    }
    $('heatmapGrid').replaceChildren(...months);
  }
  function renderNextShifts() {
    const upcoming = calculateNextShifts();
    $('nextShiftContainer').replaceChildren(...getWorkShifts().map(name => {
      const row = mk('div',{cls:'next-shift-item'});
      row.append(mk('span',{text:`下次${name}`}),mk('span',{text:`${upcoming[name]} 天后`})); return row;
    }));
    $('nextShiftContainer').hidden = getWorkShifts().length === 0;
  }
  function renderUpcoming() {
    const today = new Date();
    const last = addDays(today,6);
    $('weekRange').textContent = `${today.getMonth()+1}.${String(today.getDate()).padStart(2,'0')} — ${last.getMonth()+1}.${String(last.getDate()).padStart(2,'0')}`;
    $('upcomingList').replaceChildren(...Array.from({length:7},(_,index) => {
      const date = addDays(today,index), name = getShift(date), def = getShiftDef(name);
      const festival = getHoliday(date);
      const row = mk('button',{cls:`upcoming-day${index === 0 ? ' is-today' : ''}`,onclick:event => {
        navigateMonth(date.getFullYear(),date.getMonth());
        showDetail(date,event.currentTarget);
      }});
      row.dataset.date = formatDateISO(date);
      row.setAttribute('aria-label',`${formatDate(date)} ${getWeekdayCn(date)}${festival ? ` ${festival}` : ''} ${name} 查看详情`);
      const dateLabel = mk('span',{cls:'upcoming-date'});
      dateLabel.append(mk('strong',{text:`${String(date.getMonth()+1).padStart(2,'0')}.${String(date.getDate()).padStart(2,'0')}`}),mk('small',{text:index === 0 ? '今天' : index === 1 ? '明天' : getWeekdayCn(date)}));
      const shift = mk('span',{cls:`upcoming-shift ${def.cssClass}`});
      shift.append(mk('span',{cls:`shift-dot ${def.cssClass}`}),mk('span',{text:name}));
      row.title = [festival,shiftTimeText(name)].filter(Boolean).join(' · ');
      row.append(dateLabel,shift);
      if (festival) {
        row.classList.add('is-festival');
        const parts = festivalParts(festival);
        if (parts.length) row.dataset.festivalPalette = FESTIVAL_ART[parts[0]].palette;
        const tag = mk('span',{cls:'upcoming-festival'});
        tag.append(makeFestivalArt(festival),mk('span',{text:festival}));
        row.append(tag);
      }
      return row;
    }));
  }
  function render() {
    const today = new Date();
    viewYear = Math.min(maxYear(),Math.max(minYear(),viewYear));
    $('subtitle').textContent = `${formatDate(today)} · ${getWeekdayCn(today)}`;
    $('footerHint').textContent = `配置仅保存在当前设备 · ${schedule.cycle.length} 天一循环`;
    renderStats(); renderLegend(); renderCalendar(); renderHeatmap(); renderNextShifts(); renderUpcoming(); onUpdateLive();
    if (selectedDate) renderDetail(selectedDate);
  }
  function renderDetail(date) {
    const name = getShift(date), def = getShiftDef(name), lunar = getLunarDate(date);
    const festival = getHoliday(date), term = getSolarTerm(date);
    renderFestivalNotice($('detailFestival'),festival);
    $('detailHeading').textContent = `${date.getMonth()+1}月${date.getDate()}日 · ${getWeekdayCn(date)}`;
    $('detailDate').textContent = `${formatDate(date)} ${getWeekdayCn(date)}${lunar.day ? ` · 农历${lunar.yearGanZhi}年 [${lunar.zodiac}] ${lunar.monthStr}${lunar.dayStr}` : ''}${festival ? ` · ${festival}` : ''}${term && term !== festival ? ` · ${term}` : ''}`;
    $('detailShift').textContent = `${def.emoji} ${name}`;
    $('detailShift').className = `detail-shift ${def.cssClass}`;
    $('detailTime').textContent = shiftTimeText(name);
    $('detailCycle').textContent = getCycleIndex(date) + 1;
    const rest = getDaysToRest(date), consecutive = getConsecutiveShiftDays(date);
    $('detailExtra').replaceChildren(...[
      ['连续同班',consecutive === null ? '整个周期' : `${consecutive} 天`],
      ['当月计划工时',`${calculateWorkHours(date.getFullYear(),date.getMonth())} h`],
      ['距休息日',rest < 0 ? '周期内无休息日' : rest === 0 ? (isSameDay(date,new Date()) ? '就是今天' : '当天休息') : `${rest} 天`],
      ['周期位置',`${getCycleIndex(date) + 1} / ${schedule.cycle.length}`]
    ].map(([label,value]) => { const item = mk('div',{cls:'detail-extra-item'}); item.append(mk('p',{cls:'detail-extra-label',text:label}),mk('p',{cls:'detail-extra-value',text:value})); return item; }));
    $('detailPrev').disabled = addDays(date,-1).getFullYear() < minYear();
    $('detailNext').disabled = addDays(date,1).getFullYear() > maxYear();
  }
  function showDetail(date,trigger = null) {
    if (trigger) {
      detailTrigger = trigger; detailReturnDate = formatDateISO(date);
      detailReturnSource = trigger.classList.contains('upcoming-day') ? 'upcoming' : 'calendar';
    }
    selectedDate = date; renderDetail(date);
    $('upcomingPanel').hidden = true;
    $('detailPanel').classList.add('show');
    reveal($('detailPanel'));
    updateSelection();
    if (trigger) $('detailHeading').focus({preventScroll:true});
    if (window.innerWidth <= 640) $('detailPanel').scrollIntoView?.({behavior:motionAllowed() ? 'smooth' : 'auto',block:'nearest'});
  }
  function moveDetail(amount) {
    if (!selectedDate) return;
    const date = addDays(selectedDate,amount);
    if (date.getFullYear() < minYear() || date.getFullYear() > maxYear()) return;
    selectedDate = date;
    if (date.getFullYear() !== viewYear || date.getMonth() !== viewMonth) {
      viewYear = date.getFullYear(); viewMonth = date.getMonth(); render();
    } else { renderDetail(date); updateSelection(); }
    $('detailAnnouncement').textContent = `${formatDate(date)} ${getWeekdayCn(date)}，${getShift(date)}，${shiftTimeText(getShift(date))}`;
  }
  function updateSelection() {
    for (const cell of $('daysGrid').querySelectorAll('button')) {
      const selected = !!selectedDate && cell.dataset.date === formatDateISO(selectedDate);
      cell.classList.toggle('selected',selected); cell.setAttribute('aria-pressed',String(selected));
    }
  }
  function closeDetail({restoreFocus = true} = {}) {
    const wasOpen = $('detailPanel').classList.contains('show');
    const returnFocus = restoreFocus && wasOpen && $('detailPanel').contains(document.activeElement);
    const closingDate = selectedDate && formatDateISO(selectedDate);
    $('detailPanel').classList.remove('show');
    $('upcomingPanel').hidden = false;
    selectedDate = null; updateSelection();
    if (wasOpen) reveal($('upcomingPanel'));
    if (returnFocus) {
      const fallback = detailReturnSource === 'upcoming' ? $('upcomingList') : $('daysGrid');
      const target = detailTrigger?.isConnected ? detailTrigger : fallback.querySelector(`[data-date="${detailReturnDate}"]`)
        || fallback.querySelector(`[data-date="${closingDate}"]`) || fallback.querySelector('button');
      (target || $('monthTitle')).focus({preventScroll:true});
      if (window.innerWidth <= 640) (target || $('monthTitle')).scrollIntoView?.({behavior:motionAllowed() ? 'smooth' : 'auto',block:'nearest'});
    }
    detailTrigger = null; detailReturnDate = null; detailReturnSource = null;
    $('detailAnnouncement').textContent = '';
  }

  function openYearOverview() {
    $('yearOverview').open = true;
    $('yearOverview').scrollIntoView?.({behavior:motionAllowed() ? 'smooth' : 'auto',block:'start'});
  }
  $('yearOverview').addEventListener('toggle',event => {
    if (event.currentTarget.open) reveal(event.currentTarget.querySelector('.year-content'));
  });
  document.querySelector('.view-link[href="#calendarSection"]').addEventListener('click',event => {
    event.preventDefault();
    $('calendarSection').scrollIntoView?.({behavior:motionAllowed() ? 'smooth' : 'auto',block:'start'});
  });

  // A single navigation boundary applies to buttons, keyboard shortcuts and the picker.
  function minYear() { return Math.max(1900,new Date().getFullYear() - 1); }
  function maxYear() { return Math.min(2100,new Date().getFullYear() + 1); }
  function navigateMonth(year, month) {
    const target = new Date(year,month,1);
    if (target.getFullYear() < minYear() || target.getFullYear() > maxYear()) return;
    viewYear = target.getFullYear(); viewMonth = target.getMonth(); closeDetail({restoreFocus:false}); render();
  }
  function prevMonth() { navigateMonth(viewYear,viewMonth - 1); }
  function nextMonth() { navigateMonth(viewYear,viewMonth + 1); }
  function backToToday() { const today = new Date(); navigateMonth(today.getFullYear(),today.getMonth()); showDetail(today); }

  // Let the browser own vertical scrolling and pinch zoom; only a deliberate
  // one-finger horizontal gesture on the month grid changes the month.
  const calendar = document.querySelector('.calendar');
  const pointers = new Set();
  let gesture = null, suppressClickUntil = 0;
  const gestureTime = () => window.performance.now();
  calendar.addEventListener('pointerdown',event => {
    if (!['touch','pen'].includes(event.pointerType) || ui.activeDialog) return;
    pointers.add(event.pointerId);
    if (pointers.size !== 1 || event.isPrimary === false) { gesture = null; return; }
    suppressClickUntil = 0;
    gesture = {id:event.pointerId,x:event.clientX,y:event.clientY,start:gestureTime(),axis:null};
  },{passive:true});
  window.addEventListener('pointerdown',event => {
    if (gesture && gesture.id !== event.pointerId && ['touch','pen'].includes(event.pointerType)) {
      pointers.add(event.pointerId); gesture = null;
    }
  },{passive:true});
  window.addEventListener('pointermove',event => {
    if (!gesture || gesture.id !== event.pointerId) return;
    const dx = event.clientX - gesture.x, dy = event.clientY - gesture.y;
    if (!gesture.axis && Math.max(Math.abs(dx),Math.abs(dy)) >= 10) {
      if (Math.abs(dx) <= Math.abs(dy) * 1.2) { gesture = null; return; }
      gesture.axis = 'horizontal';
      calendar.setPointerCapture?.(event.pointerId);
    }
  },{passive:true});
  window.addEventListener('pointerup',event => {
    pointers.delete(event.pointerId);
    if (!gesture || gesture.id !== event.pointerId) return;
    const current = gesture;
    gesture = null;
    const dx = event.clientX - current.x, dy = event.clientY - current.y;
    const horizontal = current.axis === 'horizontal' || (Math.abs(dx) >= 10 && Math.abs(dx) > Math.abs(dy) * 1.2);
    if (!horizontal) return;
    // A swipe must never turn into a click on a date, even at a year boundary.
    suppressClickUntil = gestureTime() + 450;
    const threshold = Math.max(42,Math.min(72,calendar.clientWidth * .14));
    if (ui.activeDialog || gestureTime() - current.start > 1100 || Math.abs(dx) < threshold || Math.abs(dx) < Math.abs(dy) * 1.4) return;
    if (dx < 0) nextMonth(); else prevMonth();
  },{passive:true});
  window.addEventListener('pointercancel',event => {
    pointers.delete(event.pointerId);
    if (gesture?.id === event.pointerId) gesture = null;
  },{passive:true});
  calendar.addEventListener('click',event => {
    if (event.detail !== 0 && gestureTime() < suppressClickUntil) {
      event.preventDefault(); event.stopImmediatePropagation();
    }
  },true);
  function resetGesture() { gesture = null; pointers.clear(); }
  window.addEventListener('blur',resetGesture);
  document.addEventListener('visibilitychange',resetGesture);

  function openPicker() { pickerYear = viewYear; renderPicker(); openDialog('pickerModal'); }
  function closePicker() { closeDialog('pickerModal'); }
  function renderPicker() {
    $('pickerYearLabel').textContent = `${pickerYear} 年`;
    $('pickerPrev').disabled = pickerYear <= minYear(); $('pickerNext').disabled = pickerYear >= maxYear();
    $('pickerMonths').replaceChildren(...Array.from({length:12},(_,m) => {
      const button = mk('button',{cls:`picker-month-btn${pickerYear === viewYear && m === viewMonth ? ' active' : ''}`,text:`${m + 1}月`,onclick:() => { navigateMonth(pickerYear,m); closePicker(); }});
      button.setAttribute('aria-pressed',String(pickerYear === viewYear && m === viewMonth)); return button;
    }));
  }
  function pickerPrevYear() { if (pickerYear > minYear()) { pickerYear--; renderPicker(); } }
  function pickerNextYear() { if (pickerYear < maxYear()) { pickerYear++; renderPicker(); } }
  return { renderStats, renderLegend, renderCalendar, renderHeatmap, renderNextShifts, renderUpcoming, render, renderDetail, showDetail, moveDetail, updateSelection, closeDetail, openYearOverview, minYear, maxYear, navigateMonth, prevMonth, nextMonth, backToToday, openPicker, closePicker, renderPicker, pickerPrevYear, pickerNextYear, get viewYear() { return viewYear; }, get viewMonth() { return viewMonth; }, get pickerYear() { return pickerYear; } };
}
