// Optional legacy shell adapter; the web app never depends on this bridge.
export function syncAndroidBridge(window, config, toast) {
  try {
    window.Android?.saveSettings?.(JSON.stringify(config.cycle),config.anchor,JSON.stringify(config.shiftDefs));
  } catch (_) { toast('网页设置已更新，原生应用同步暂时失败'); }
}
