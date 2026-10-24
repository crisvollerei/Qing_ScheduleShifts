# Qing_ScheduleShifts · 清蒸排班

> ⚠️ **免责声明**：本项目借助 AI 工具辅助开发完成，仅供参考和学习研究使用。

「清蒸排班」是一个纯前端的个人轮班日历。本项目是它的 **Android 封装版**：
在网页内核外面加上打包配置、WebView 外壳、桌面小部件与上班提醒。

![](docs/images/widgets/day-wide.png)
![](docs/images/widgets/night-wide.png)

两份代码可以各自独立使用：

- **只想用排班表** → 打开[原项目](https://github.com/crisvollerei/crisvollerei.github.io)，浏览器里直接用，无需安装
- **想在桌面看班次** → 安装本项目的 APK，附带桌面小部件

## 桌面小部件预览

六张图由小组件的原生布局渲染，不是手机截图。上行银昼、下行蓝夜，详见[小部件说明](docs/WIDGET.md)。

| 银昼 · 浅色 | 蓝夜 · 深色 |
| :---: | :---: |
| <img src="docs/images/widgets/day-minimum.png" alt="银昼小方卡" width="150"> | <img src="docs/images/widgets/night-minimum.png" alt="蓝夜小方卡" width="150"> |
| <img src="docs/images/widgets/day-wide.png" alt="银昼三段横条" width="300"> | <img src="docs/images/widgets/night-wide.png" alt="蓝夜三段横条" width="300"> |
| <img src="docs/images/widgets/day-full.png" alt="银昼完整方卡" width="240"> | <img src="docs/images/widgets/night-full.png" alt="蓝夜完整方卡" width="240"> |

**三种构图**

| 形态 | 基准画布 | 内容 | 最小尺寸 |
| --- | --- | --- | --- |
| 小方卡 | 128×128dp | 日期、班次、倒计时、当前时间、明日提示 | 110×110dp |
| 三段横条 | 360×128dp | 班次 / 时钟与倒计时 / 立绘 | 224×92dp |
| 完整方卡 | 304×320dp | 班次与倒计时、Q 版人物、明日安排、未来七天与周期 | 224×240dp |

实际显示随 Launcher 可用空间、系统字体与缩放调整，以实机为准。

> 另有一个 184×184dp 的旧入口（紧凑方卡）为兼容已安装实例而保留，
> 它复用最小卡的布局，覆盖安装后统一为小方卡构图。

## 项目构成

| 组成 | 位置 | 说明 |
| --- | --- | --- |
| **网页内核** | `app/src/main/assets/` | 轮班日历全部功能：自定义周期、班次时间、跨日班、方案切换、月度统计、农历节气、备份恢复、双主题 |
| **Android 外壳** | `MainActivity.java` | 经 `WebViewAssetLoader` 以同源 https 虚拟域加载页面，负责返回键、外链与设置桥 |
| **原生桥** | `assets/android-bridge.js` | 本项目自有：从小部件侧回读排班、供返回键关闭页面弹窗 |
| **桌面小部件** | `MyWidgetProvider.java` | 读取网页保存的排班，显示今日班次、周期进度与上下班倒计时 |
| **设置桥** | `MainActivity.SettingsInterface` | 网页保存时经 `Android.saveSettings()` 写入 SharedPreferences，供小部件读取 |
| **上班提醒** | `assets/reminders.js`、`WorkReminders.java` | 独立设置页、本应用持续闹钟、系统时钟交接与按日期生成日历提醒 |

网页运行时不依赖任何外部字体、图标或 CDN，也不需要网络权限；素材全部在 `assets/` 内本地存放。

## 上班提醒

Android 应用左下角的「上班提醒」打开独立输入页，沿用银昼 / 蓝夜主题。

- **本应用闹钟**：开启并保存后，按当前排班持续安排上班前提醒，支持提前 0–1440 分钟、铃声与振动；不需要添加桌面小组件。
- **系统时钟**：计算下次提醒时间并打开手机时钟，由用户确认日期与保存；不承诺跨手机批量创建指定日期闹钟或自动取消。
- **系统日历**：按上班日期，从明天起生成 1 天、7 天或自定义 1–90 天的提醒；先预览再确认，不自动续期。

本应用闹钟使用系统精确闹钟，修改排班会更新安排；关闭它不会删除系统时钟或日历记录。
日历生成需要读写授权，只管理本机记录且带本应用标记的导出事件。所选账户可能同步日程到其他设备。
强行停止、应用冻结、权限撤销及关机仍可能导致漏提醒，详见[上班提醒说明](docs/REMINDERS.md)。

## 快速开始

```sh
# 网页版
npm start                 # http://127.0.0.1:4175

# Android 打包（含测试与签名）
.\package-apk.ps1
```

环境要求：Android SDK（API 34）、JDK 17+、Gradle 8.5（已内置）。

## 文档

| 文档 | 内容 |
| --- | --- |
| [网页版说明](docs/WEB_APP.md) | 排班设置、方案管理、日历统计、主题与陪伴、快捷键 |
| [小部件说明](docs/WIDGET.md) | 三种构图、班次风格、状态文案、字体边界、刷新机制与系统限制 |
| [构建与打包](docs/BUILD.md) | 编译命令、一键打包、签名、版本号、图标、包名 |
| [数据同步](docs/DATA_SYNC.md) | 网页与原生如何共用排班数据、优先级规则 |
| [上班提醒](docs/REMINDERS.md) | 三种入口、提前量、权限、日历管理与可靠性边界 |
| [测试](docs/TESTING.md) | 网页内核与 Android 侧测试、两端契约测试 |
| [设计说明](docs/DESIGN_NOTES.md) | 银昼 / 蓝夜视觉语言、排版系统、动效与可访问性 |
| [备份格式](docs/BACKUP_FORMAT.md) | 备份编码与数据版本约定 |

## 目录结构

```text
ScheduleShifts/
├── app/src/main/
│   ├── assets/                    # ★ 网页内核（同时是 Android 的内嵌页面）
│   │   ├── index.html
│   │   └── assets/
│   │       ├── main.js            # ES module 启动入口（挂载原生桥）
│   │       ├── app.js             # 主题、实时更新与模块协调
│   │       ├── schedule.js        # 排班模型与周期计算
│   │       ├── schedule-core.js   # 配置校验与日期、班次计算
│   │       ├── views.js           # 月历、统计与详情渲染
│   │       ├── companion.js       # Q 版陪伴
│   │       ├── android-bridge.js  # ★ 本项目自有：原生桥
│   │       ├── reminders.js       # ★ Android 上班提醒设置页
│   │       └── …                  # 其余模块、样式与素材
│   ├── java/com/example/myapp/
│   │   ├── MainActivity.java      # WebView 宿主与设置桥
│   │   ├── MyWidgetProvider.java  # 排班计算、事件与响应式更新
│   │   ├── WidgetRenderer.java    # 三种有界构图、字号与主题绑定
│   │   ├── WidgetShiftAppearance.java # 六种班次风格、预设图标与配色
│   │   ├── WidgetPortrait.java    # 横条立绘窗口合成与主题缓存
│   │   ├── WidgetRefresh.java     # 状态边界精确刷新与恢复
│   │   ├── WorkReminders.java     # 独立精确闹钟调度与取消
│   │   ├── WorkAlarmService.java  # 到点响铃与通知停止入口
│   │   └── ReminderCalendar.java  # 日历预览、生成、去重与清理
│   ├── res/
│   └── AndroidManifest.xml
├── docs/                          # 详细文档与渲染预览图
├── tests/                         # 网页内核与小部件同步的回归测试
├── package-apk.ps1                # 一键打包
└── README.md
```

> 页面用 `assets/xxx.css` 这类**相对路径**引用资源，因此 `assets/assets/` 这一层是必需的，
> 不能为了「看起来更整齐」把它拍平。

## 许可

[MIT License](LICENSE)
