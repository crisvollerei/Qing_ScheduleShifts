export function createUI(window) {
  const { document, Node } = window;
  const $ = id => document.getElementById(id);
  const runningAnimations = new Set(), elementAnimations = new WeakMap();
  const motionPreference = window.matchMedia?.('(prefers-reduced-motion: reduce)');
  const dismissHandlers = new Map(), stack = [];
  let lifecycle = {}, toastTimer, inputViewScroll = null, pendingConfirmation = null;
  const active = () => stack.at(-1);

  function mk(tag, options = {}) {
    const element = document.createElement(tag);
    if (options.cls) element.className = options.cls;
    if (options.text !== undefined) element.textContent = options.text;
    if (options.onclick) element.addEventListener('click',options.onclick);
    if (tag === 'button') element.type = 'button';
    return element;
  }
  function toast(message) {
    window.clearTimeout(toastTimer);
    $('toast').textContent = message; $('toast').hidden = false;
    toastTimer = window.setTimeout(() => { $('toast').hidden = true; },3200);
  }
  function motionAllowed() { return !active() && !!motionPreference && !motionPreference.matches; }
  function animateElement(element, frames, options = {}) {
    if (!element?.animate || !motionAllowed()) return;
    elementAnimations.get(element)?.cancel();
    const animation = element.animate(frames,{duration:260,easing:'cubic-bezier(.16,1,.3,1)',...options});
    elementAnimations.set(element,animation); runningAnimations.add(animation);
    animation.finished.catch(() => {}).finally(() => runningAnimations.delete(animation));
  }
  motionPreference?.addEventListener?.('change',event => {
    if (event.matches) for (const animation of runningAnimations) animation.cancel();
  });
  function reveal(element, options = {}) {
    animateElement(element,[{opacity:0,transform:'translateY(9px)'},{opacity:1,transform:'translateY(0)'}],{duration:330,...options});
  }
  function observeAmbientMotion() {
    const scenes = document.querySelectorAll('.workspace-intro,.today-card,.calendar-section');
    if (window.IntersectionObserver) {
      const observer = new window.IntersectionObserver(entries => {
        for (const entry of entries) entry.target.classList.toggle('ambient-visible',entry.isIntersecting);
      });
      for (const scene of scenes) observer.observe(scene);
    } else {
      for (const scene of scenes) scene.classList.add('ambient-visible');
    }
  }
  function openDialog(id, { nested = false } = {}) {
    if (active()?.id === id) return;
    if (!nested) while (active()) dismissDialog(active().id);
    const overlay = $(id), content = overlay.querySelector('[role="dialog"]');
    const inputView = !!content.querySelector('input,textarea') || !!active()?.inputView;
    const entry = {id, trigger:document.activeElement, inputView, scroll:{x:window.scrollX,y:window.scrollY}};
    if (active()) { $(active().id).hidden = true; $(active().id).inert = true; }
    lifecycle.pause?.();
    for (const animation of runningAnimations) animation.cancel();
    runningAnimations.clear();
    if (inputView && !inputViewScroll) inputViewScroll = {x:window.scrollX,y:window.scrollY};
    stack.push(entry);
    overlay.classList.toggle('input-view',inputView);
    document.documentElement.classList.toggle('input-view-open',inputView);
    overlay.hidden = false; overlay.inert = false;
    document.querySelector('.main-container').inert = true;
    document.body.style.overflow = inputView ? '' : 'hidden';
    content.scrollTop = 0;
    if (inputView && (window.scrollX || window.scrollY)) window.scrollTo(0,0);
    content.focus({preventScroll:true});
  }
  function closeDialog(id) {
    if (active()?.id !== id) return;
    const entry = stack.pop(), overlay = $(id);
    overlay.hidden = true; overlay.classList.remove('input-view');
    if (active()) {
      const parent = active();
      $(parent.id).hidden = false; $(parent.id).inert = false;
      document.documentElement.classList.toggle('input-view-open',parent.inputView);
      document.body.style.overflow = parent.inputView ? '' : 'hidden';
      if (window.scrollX !== entry.scroll.x || window.scrollY !== entry.scroll.y) window.scrollTo(entry.scroll.x,entry.scroll.y);
    } else {
      document.documentElement.classList.remove('input-view-open');
      document.querySelector('.main-container').inert = false;
      document.body.style.overflow = '';
      lifecycle.resume?.();
      if (inputViewScroll && (window.scrollX !== inputViewScroll.x || window.scrollY !== inputViewScroll.y)) {
        window.scrollTo(inputViewScroll.x,inputViewScroll.y);
      }
      inputViewScroll = null;
    }
    if (entry.trigger?.isConnected) entry.trigger.focus({preventScroll:true});
    else active() && $(active().id).querySelector('[role="dialog"]').focus({preventScroll:true});
  }
  function dismissDialog(id) {
    if (dismissHandlers.has(id)) dismissHandlers.get(id)();
    else closeDialog(id);
  }
  function confirmAction(message, onConfirm, { title = '确认删除', accept = '确认删除', cancel = '取消' } = {}) {
    if (pendingConfirmation) return;
    pendingConfirmation = onConfirm;
    $('confirmTitle').textContent = title;
    $('confirmAccept').textContent = accept;
    $('confirmCancel').textContent = cancel;
    $('confirmDescription').textContent = message;
    openDialog('confirmModal',{nested:true});
  }
  function cancelConfirmation() {
    pendingConfirmation = null;
    closeDialog('confirmModal');
  }
  dismissHandlers.set('confirmModal',cancelConfirmation);
  $('confirmCancel').addEventListener('click',cancelConfirmation);
  $('confirmAccept').addEventListener('click',() => {
    const callback = pendingConfirmation;
    pendingConfirmation = null;
    closeDialog('confirmModal');
    callback?.();
    if (active() && !$(active().id).contains(document.activeElement)) {
      $(active().id).querySelector('[role="dialog"]').focus({preventScroll:true});
    }
  });
  for (const id of ['settingsModal','pickerModal','presetModal','confirmModal','dataModal','personalizationModal']) {
    $(id).addEventListener('click',event => { if (event.target === $(id)) dismissDialog(id); });
  }
  document.addEventListener('keydown',event => {
    if (!active()) return;
    if (event.key === 'Escape') { event.preventDefault(); dismissDialog(active().id); }
    if (event.key !== 'Tab') return;
    const nodes = [...$(active().id).querySelectorAll('button,input,textarea,select')]
      .filter(node => !node.disabled && !node.closest('[hidden],[inert]'))
      .sort((a,b) => a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1);
    const first = nodes[0], last = nodes.at(-1), isContainer = document.activeElement.getAttribute('role') === 'dialog';
    if (event.shiftKey && (document.activeElement === first || isContainer)) { event.preventDefault(); last?.focus(); }
    else if (!event.shiftKey && (document.activeElement === last || isContainer)) { event.preventDefault(); first?.focus(); }
  });
  return { $, mk, toast, reveal, animateElement, motionAllowed, observeAmbientMotion, openDialog, closeDialog, confirmAction,
    runningAnimations, get activeDialog() { return active()?.id || null; },
    setLifecycle(value) { lifecycle = value; }, onDismiss(id, handler) { dismissHandlers.set(id,handler); } };
}
