# 网页与原生数据同步

网页（WebView）与桌面小部件共用 `SharedPreferences("WidgetSettings")` 一份数据，
键为 `cycle` / `anchorDate` / `shiftDefs`，桥接方法见 `MainActivity.SettingsInterface`。

## 同步方向

| 方向       | 时机                          | 行为                                                      |
| -------- | --------------------------- | ------------------------------------------------------- |
| 网页 → 原生 | 页面加载完成或保存排班（`commitConfig`） | 调用 `Android.saveSettings(cycle, anchor, defs)` 写入，并**立即**重绘小部件 |
| 原生 → 网页 | 页面启动、且本地无可用配置               | 调用 `Android.getSettings()` 取回，作为回退                      |

## 优先级

优先级是**本地配置优先**：只要浏览器里已有 `qingzheng-config`（或旧键），就以本地为准，
不会被小部件侧的数据覆盖，避免用户刚改的排班被回滚。

只有 WebView 数据被清除、本地完全空白时，才采用原生侧已保存的排班，
防止页面与桌面显示两套班表。

## 浏览器环境

网页在普通浏览器中运行时没有 `Android` 对象，上述桥接自动跳过，功能不受影响。
