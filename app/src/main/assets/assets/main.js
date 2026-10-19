import { createApp } from './app.js?v=50bdb6fedb90';
import Core from './schedule-core.js?v=fc384b3c941b';
import { installAndroidBridge } from './android-bridge.js';

// 本项目（Android 外壳）自有入口：在上游 main.js 的基础上额外装上原生桥。
// 普通浏览器中没有 Android 对象，installAndroidBridge 会直接返回 null，行为不变。
const app = createApp(window);
installAndroidBridge(window, app, Core);
