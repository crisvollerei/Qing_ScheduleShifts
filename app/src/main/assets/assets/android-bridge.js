/**
 * Android 外壳适配层（本项目自有，不属于网页内核）。
 *
 * 网页内核自 v2 起改为 ES module + 工厂函数结构，不再向 window 暴露
 * CYCLE / SHIFT_DEFS / closeSettings() 等全局符号。原生侧需要的两项能力
 * 因此改由本模块桥接：
 *
 * 1. 反向读取：WebView 本地存储被清除后，从小部件侧（SharedPreferences）
 *    取回已保存排班，避免页面与桌面显示两套班表；
 * 2. 关闭弹窗：返回键需要先关掉页面内弹窗，而非直接退出应用。
 *
 * 约定：本文件只依赖注入的 app 实例与 window，不修改上游模块，
 * 上游同步时可原样覆盖 assets/ 其余文件，仅保留本文件与其挂载点。
 */

/** 校验并归一化来自原生侧的数据；任何异常一律返回 null，交由页面用本地或默认配置。 */
function readNativeConfig(Core, raw) {
  if (typeof raw !== 'string' || !raw) return null;
  let data;
  try {
    data = JSON.parse(raw);
  } catch (_) {
    return null;
  }
  if (!data || typeof data !== 'object') return null;
  // cycle 与 anchorDate 同时存在才视为原生侧保存过排班。
  if (!data.cycle || !data.anchorDate) return null;
  let cycle;
  try {
    cycle = JSON.parse(data.cycle);
  } catch (_) {
    return null;
  }
  let shiftDefs;
  try {
    shiftDefs = data.shiftDefs ? JSON.parse(data.shiftDefs) : {};
  } catch (_) {
    shiftDefs = {};
  }
  return Core.normalizeConfig({
    anchor: data.anchorDate,
    cycle,
    shiftDefs: shiftDefs && typeof shiftDefs === 'object' && !Array.isArray(shiftDefs) ? shiftDefs : {}
  });
}

/**
 * 建立与原生侧的桥。返回的 bridge 同时挂到 window.QingZhengAppBridge，
 * 供 MainActivity 通过 evaluateJavascript 调用。
 *
 * @param {object} window   页面 window
 * @param {object} app      createApp() 的返回值
 * @param {object} Core     schedule-core 模块
 */
export function installAndroidBridge(window, app, Core) {
  const native = window.Android && typeof window.Android.saveSettings === 'function' ? window.Android : null;

  if (!native) {
    // 普通浏览器：不安装任何桥，避免影响网页自身行为。
    return null;
  }

  const canRead = typeof native.getSettings === 'function';

  /** 把当前配置推送给小部件。失败不阻断页面保存。 */
  function push(config) {
    try {
      native.saveSettings(JSON.stringify(config.cycle), config.anchor, JSON.stringify(config.shiftDefs));
      return true;
    } catch (_) {
      return false;
    }
  }

  /**
   * 判断网页侧是否完全没有可用配置。
   * 与 schedule.js 的 loadConfig 保持同一套判定：单条 qingzheng-config 可解析，
   * 或存在任一旧版键，都视为本地已有配置——此时绝不采用原生侧数据，
   * 否则用户刚改的排班会被小部件里的旧值覆盖。
   */
  function hasLocalConfig() {
    const readJSON = key => {
      let raw;
      try {
        raw = window.localStorage.getItem(key);
      } catch (_) {
        return null;
      }
      if (raw === null) return null;
      try {
        return JSON.parse(raw);
      } catch (_) {
        // 存在但已损坏：仍算“本地有数据”，交给页面自身的恢复逻辑处理。
        return {};
      }
    };
    if (Core.normalizeConfig(readJSON('qingzheng-config'))) return true;
    for (const key of ['qingzheng-config', 'qingzheng-cycle', 'qingzheng-anchor', 'qingzheng-shift-defs']) {
      try {
        if (window.localStorage.getItem(key) !== null) return true;
      } catch (_) {
        return true;
      }
    }
    return false;
  }

  /** 仅当页面本地完全没有可用配置时，才采用原生侧数据。 */
  function adoptNativeConfigIfLocalBlank() {
    if (!canRead) return false;
    if (hasLocalConfig()) return false;
    let bridged;
    try {
      bridged = readNativeConfig(Core, native.getSettings());
    } catch (_) {
      return false;
    }
    if (!bridged) return false;
    try {
      return app.schedule.commitConfig(bridged, { persist: true }) !== false;
    } catch (_) {
      return false;
    }
  }

  /** 关闭当前最上层弹窗；返回 true 表示确实关掉了，原生侧据此决定是否退出应用。 */
  function dismissTopDialog() {
    const id = app.ui.activeDialog;
    if (!id) return false;
    app.ui.closeDialog(id);
    return true;
  }

  const bridge = {
    /** 原生侧在页面加载完成后调用，把页面当前配置对齐给小部件。 */
    syncNow() {
      try {
        return push(app.schedule.captureCurrentConfig());
      } catch (_) {
        return false;
      }
    },
    dismissTopDialog,
    /** 供原生侧读取当前配置（调试或未来扩展）。 */
    currentConfig() {
      try {
        return JSON.stringify(app.schedule.captureCurrentConfig());
      } catch (_) {
        return '';
      }
    }
  };

  // 先尝试回填原生侧配置，再挂上桥，保证首屏渲染用的就是最终数据。
  adoptNativeConfigIfLocalBlank();
  window.QingZhengAppBridge = bridge;
  return bridge;
}
