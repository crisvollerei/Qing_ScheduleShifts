# 网页与原生数据同步

网页（WebView）、桌面小部件与上班提醒共用 `SharedPreferences("WidgetSettings")` 一份排班数据，
键为 `cycle` / `anchorDate` / `shiftDefs`，桥接方法见 `MainActivity.SettingsInterface`。

## 同步方向

| 方向       | 时机                          | 行为                                                      |
| -------- | --------------------------- | ------------------------------------------------------- |
| 网页 → 原生 | 页面加载完成或保存排班（`commitConfig`） | 调用 `Android.saveSettings(cycle, anchor, defs)` 写入，刷新小部件并重新核对本应用闹钟；没有小部件时闹钟仍独立工作 |
| 原生 → 网页 | 页面启动、且本地无可用配置               | 调用 `Android.getSettings()` 取回，作为回退                      |

## 优先级

优先级是**本地配置优先**：只要浏览器里已有 `qingzheng-config`（或旧键），就以本地为准，
不会被小部件侧的数据覆盖，避免用户刚改的排班被回滚。

只有 WebView 数据被清除、本地完全空白时，才采用原生侧已保存的排班，
防止页面与桌面显示两套班表。

## 浏览器环境

网页在普通浏览器中运行时没有 `Android` 对象，上述桥接自动跳过，功能不受影响。

## 提醒设置

`assets/reminders.js` 经 `Android.reminderRequest()` 请求原生操作，原生异步回传状态、结果或错误。
提醒设置与调度记录保存在独立 `SharedPreferences("WorkReminders")`，不属于排班方案或网页备份。
本应用闹钟跟随当前排班重新计算，系统日历只在用户预览、确认后写入；系统时钟保存后的记录由时钟应用自行管理。
