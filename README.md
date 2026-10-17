# Qing_ScheduleShifts · 清蒸排班

> ⚠️ **免责声明**：本项目借助 AI 工具辅助开发完成，仅供参考和学习研究使用。

「清蒸排班」是一个纯前端的个人轮班日历。本项目是它的 **Android 封装版**：
在网页内核外面加上打包配置、WebView 外壳与桌面小部件。

网页内核来自原项目：

> **原项目：<https://github.com/crisvollerei/crisvollerei.github.io>**
>
> 浏览器内核（`index.html` + `assets/`）以该仓库为准，本项目将其内嵌到
> `app/src/main/assets/` 并随 Android 一起分发。网页本身的玩法、视觉与历法数据
> 由原项目维护；纯网页使用请直接访问上面的仓库。

这样两份代码可以各自独立使用：

- **只想用排班表** → 打开原项目，浏览器里直接用，无需安装
- **想在桌面看班次** → 安装本项目的 APK，附带桌面小部件

---

## 📱 项目构成

| 组成 | 位置 | 说明 |
|------|------|------|
| **网页内核** | `app/src/main/assets/` | 轮班日历全部功能：自定义周期、班次时间、跨日班、方案切换、月度统计、年度总览、农历/节气/节日、银昼与蓝夜双主题 |
| **Android 外壳** | `app/src/main/java/…/MainActivity.java` | WebView 加载本地页面，负责返回键、外链与设置桥 |
| **桌面小部件** | `app/src/main/java/…/MyWidgetProvider.java` | 读取网页保存的排班，显示今日班次、周期进度与上下班倒计时 |
| **设置桥** | `MainActivity.SettingsInterface` | 网页保存时经 `Android.saveSettings()` 写入 SharedPreferences，供小部件读取 |

网页运行时不依赖任何外部字体、图标或 CDN，也不需要网络权限；素材全部在 `assets/` 内本地存放。

---

## 🚀 直接使用网页版

网页内核可以脱离 Android 独立运行：

```sh
# 方式一：直接打开
app/src/main/assets/index.html

# 方式二：本机预览服务（推荐，localStorage 行为与线上一致）
npm start                 # 默认 http://127.0.0.1:4175
```

> 两种方式的浏览器存储空间是分开的，已有数据不会自动互相出现。

功能要点：

- 点击「排班设置」，选周期起始日期，再用空格填写重复的班次顺序，例如
  `白班 夜班 休息 白班 夜班 休息 休息 休息`。起始日期对应第一个班次。
- 每个班次可设颜色、工作/休息属性与上下班时间。下班时间早于或等于上班时间时视为次日下班，
  也可手动勾选「跨日」；例如 09:00 至次日 17:00 计为 32 小时。
- 修改期间使用独立草稿；「取消」、关闭弹窗或按 Esc 都会放弃修改。
- 「保存方案」记录当前日期、周期与班次定义，顶部方案按钮用于切换，旁边的 × 用于删除。
- 日历显示班次、农历与节气；年度总览可点击月份或小日期跳转；侧栏显示未来七天。
- 月度统计跟随当前查看的月份；计划工时按班次开始日计入当月。
- 顶部月亮 / 太阳按钮手动切换主题，时钟按钮启用昼夜自动模式（07:00–19:00 银昼，其余蓝夜）。
- 键盘：← / → 切月，T 回到今天，Esc 关闭弹窗或详情。
- 农历与节气数据（2025–2030）离线存放于 `assets/calendar-data.js`，来源为香港天文台公历与农历对照表。
  已覆盖年份之外，农历回退到浏览器内置的中国历法。

配置与方案只保存在当前浏览器（或 WebView 的 localStorage），不上传服务器。
网页兼容旧版存储键 `qingzheng-anchor`、`qingzheng-cycle`、`qingzheng-shift-defs`，保存后使用单条 `qingzheng-config`。

---

## 🗂️ 目录结构

```text
ScheduleShifts/
├── app/src/main/
│   ├── assets/                    # ★ 网页内核（同时是 Android 的内嵌页面）
│   │   ├── index.html             # 页面结构
│   │   ├── avatar.png             # 品牌头像
│   │   └── assets/
│   │       ├── styles.css         # 明暗主题与响应式样式
│   │       ├── themes.css         # 银昼 / 蓝夜纹样与饰扣
│   │       ├── festivals.css      # 节日配色与卡片插画
│   │       ├── app.js             # 交互、草稿、存储与视图
│   │       ├── schedule-core.js   # 配置校验与日期、班次计算
│   │       ├── calendar-data.js   # 农历月首与节气数据（2025–2030）
│   │       ├── character-day.webp / character-night.webp
│   │       └── *.svg              # 本地矢量装饰
│   ├── java/com/example/myapp/
│   │   ├── MainActivity.java      # WebView 宿主与设置桥
│   │   ├── MyWidgetProvider.java  # 小部件核心
│   │   └── WidgetRefresh.java     # 状态边界精确刷新与恢复
│   ├── res/                       # 图标、布局、银昼/蓝夜配色
│   └── AndroidManifest.xml
├── tests/                         # 网页内核与小部件同步的回归测试
├── scripts/serve.mjs              # 无依赖本机预览服务
├── docs/DESIGN_NOTES.md           # 银昼 / 蓝夜视觉设计说明
├── package.json                   # 仅用于开发测试，网页运行不需要
└── README.md
```

> 页面用 `assets/xxx.css` 这类**相对路径**引用资源，因此 `assets/assets/` 这一层是必需的，
> 不能为了「看起来更整齐」把它拍平。

---

## ✅ 测试

网页内核带有回归测试，覆盖配置校验与恢复、草稿取消、输入校验、跨日与跨午夜、
方案切换、日期边界、节日与农历、主题切换，以及网页与原生侧的数据同步。

```sh
npm ci        # 仅首次，安装 jsdom
npm test
```

测试直接读取 `app/src/main/assets/`，与 Android 打包的是同一份文件。

Android 侧的小部件计算另有单元测试：

```sh
.\gradlew.bat testOriginalDebugUnitTest
```

---

## 🔨 编译构建

### 环境要求
- Android SDK（API 34）与 Build-Tools
- JDK 11+
- Gradle 8.5（已内置 `gradlew`）

```bash
.\gradlew.bat assembleDebug      # Windows
.\gradlew.bat assembleRelease
./gradlew assembleRelease        # macOS / Linux
```

| 变体 | 包名 | 应用名 | 输出目录 |
|------|------|--------|----------|
| `original` | `com.example.myapp` | 清蒸排班 | `app/build/outputs/apk/original/release/` |
| `schedule` | `com.example.schedule` | 排班 | `app/build/outputs/apk/schedule/release/` |

两个变体共用同一套源码与资源，仅包名和应用名不同，可在同一台设备共存，数据按包名隔离。

### 签名

签名口令不写在 `build.gradle` 里。请在根目录 `local.properties`（已被 `.gitignore` 忽略）
或环境变量中提供：

```properties
RELEASE_STORE_FILE=app/release-key.jks
RELEASE_STORE_PASSWORD=***
RELEASE_KEY_ALIAS=qingzheng
RELEASE_KEY_PASSWORD=***
```

缺失这些键时，Release APK 会以**未签名**方式产出（构建不会失败），命令行会提示。
CI 建议用环境变量注入，切勿提交 `*.jks` 与口令。

---

## ⚙️ 其他配置

### 修改应用名

`app/build.gradle` 的 `manifestPlaceholders`，`AndroidManifest.xml` 引用 `${appLabel}`：

```groovy
original { manifestPlaceholders = [appLabel: "清蒸排班"] }
schedule { manifestPlaceholders = [appLabel: "排班"] }
```

### 修改版本号

`app/build.gradle` 顶部集中维护：

```groovy
def appVersionCode = 6
def appVersionName = "0.3.0"
```

> ⚠️ `versionCode` 必须**严格递增**，否则覆盖安装会被系统拒绝
> （`INSTALL_FAILED_VERSION_DOWNGRADE`）。

### 修改应用图标

图标位于 `res/mipmap-*`，按密度提供四种形态：

| 文件 | 用途 | mdpi | hdpi | xhdpi | xxhdpi | xxxhdpi |
|------|------|------|------|-------|--------|---------|
| `ic_launcher.png` | 普通图标（圆角方形） | 48 | 72 | 96 | 144 | 192 |
| `ic_launcher_round.png` | 圆形图标 | 48 | 72 | 96 | 144 | 192 |
| `ic_launcher_foreground.png` | 自适应图标前景 | 108 | 162 | 216 | 324 | 432 |
| `ic_launcher_monochrome.png` | Android 13+ 主题图标 | 108 | 162 | 216 | 324 | 432 |

自适应图标定义在 `res/mipmap-anydpi-v26/ic_launcher.xml`，
背景色为 `@color/ic_launcher_background`（`#8A576A`，取自原图边缘色调）。

更换图标时需要注意两点，否则会明显走样：

1. **前景要满幅出血**，不要把人物缩进中央 72dp 安全区——源插画本身是满幅构图，
   缩进后人物会小一圈，和普通图标观感对不上。四周留白由系统遮罩负责裁切。
2. **单色图标是剪影**，会被系统整体染成壁纸主题色，因此只需干净的人形轮廓，
   且要填充内部空洞（面部浅色区会形成破洞）。

也可用 Android Studio：右键 `res/` → **New → Image Asset** → **Launcher Icons**
重新生成整套图标。

### 主题配色

网页内外两套主题需保持一致：

| 主题 | 网页 | Android 资源 |
|------|------|--------------|
| 银昼（浅色） | `assets/styles.css` / `themes.css` 的 `:root` | `res/values/colors.xml` |
| 蓝夜（深色） | 同上 `[data-theme="dark"]` | `res/values-night/colors.xml` |

改主题色时两处都要更新，否则小部件与页面会不同色。

### 修改包名（applicationId）

修改 `app/build.gradle` 的 `namespace` 与 `productFlavors` 中的 `applicationId`。
注意 `com.example.*` 是 Google Play 拒绝的保留命名空间，上架前请换成自有域名。

---

## 🧩 桌面小部件

### 班次手札布局

小组件与网页使用同一份排班配置，不修改网页内核。使用银昼 / 蓝夜原生资源，
**跟随系统深浅色模式**，不受网页手动主题或网页按时间切换主题影响。

所有尺寸都保留：日期、班次与图标、工作时段（含跨日标记）或全天休息、
周期位置与进度、后续工作班次、秒级时钟、上下班倒计时；空间充足时增加目标日期，高卡片再增加未来七天。
后续摘要与网页一致，从明天开始查找；显示前两种工作班次，更多类型显示 +N。
点击主体打开网页，点击右上角刷新图标立即校准。

| 形状 | 选择规则（实际 dp） | 排布 |
|------|------|------|
| 宽横向 | 宽 ≥ 300 且宽高比 ≥ 1.2 | 左侧班次、周期与后续安排；右侧时钟与倒计时 |
| 标准 / 竖向 | 宽 ≥ 224 且高 ≥ 320 | 班次主视觉、周期摘要、底部双时间区 |
| 紧凑 / 窄长 | 其他尺寸 | 压缩装饰与标题，不删掉时钟、周期和后续班次 |

最小调整尺寸 156 × 216 dp；默认约 4 × 4 格，格数由桌面决定，不再假定一格固定 73 dp。
Android 12+ 按桌面提供的 `OPTION_APPWIDGET_SIZES` 构造响应式 RemoteViews；
旧系统分别使用竖屏的最小宽 / 最大高和横屏的最大宽 / 最小高，避免混用不同方向的尺寸。
所有布局具有相同控件 ID，并且只使用 RemoteViews 支持的原生控件。

### 实时刷新与系统限制

- 时钟由桌面中的 `TextClock` 逐秒更新；Android 7+ 的倒计时由 `Chronometer` 倒计时模式逐秒更新，即使应用进程退出也能继续走动。
- Android 5–6 没有原生倒计时模式，采用亮屏时非唤醒的逐秒闹钟兼容路径，熄屏停止；推荐 Android 7+。
- 排班只在上班、下班、跨日班结束、午夜重建，不用后台服务持续每秒推送。另有非唤醒的半小时恢复闹钟。
- Android 12+ 使用“闹钟和提醒”特殊访问权限，让状态边界精确触发；从小组件打开应用时会说明并提供授权入口。
- 未授权时退回系统允许的非精确闹钟，时钟和倒计时仍逐秒走动，但归零后的班次 / 文案切换可能延迟；打开应用、解锁（进程仍在时）或点击刷新可立即校准。
- 监听重启、应用升级、系统校时、时区与日期变化；进程存活时也会在亮屏、解锁、系统主题变化和分钟广播重新校准。
- 小米等厂商的强制冻结 / 强制停止仍可能阻止广播和闹钟，需要允许应用自启动及正常后台运行；应用无法绕过系统限制。

### 验证

`testOriginalDebugUnitTest` 包含排班边界、跨日、长周期、配置校验、布局完整性测试，
以及 Robolectric 原生控件渲染（Android 14）：多尺寸、深浅色、1.3 倍字体、时钟和倒计时推进。
渲染图输出到 `.qa/widget-previews/`，仅供本地检查，不会打包进 APK。

### 与网页的数据同步

网页（WebView）与桌面小部件共用 `SharedPreferences("WidgetSettings")` 一份数据，
键为 `cycle` / `anchorDate` / `shiftDefs`，桥接方法见 `MainActivity.SettingsInterface`。

| 方向 | 时机 | 行为 |
|------|------|------|
| 网页 → 原生 | 页面加载完成或保存排班（`commitConfig`） | 调用 `Android.saveSettings(cycle, anchor, defs)` 写入，并**立即**重绘小部件 |
| 原生 → 网页 | 页面启动、且本地无可用配置 | 调用 `Android.getSettings()` 取回，作为回退 |

优先级是**本地配置优先**：只要浏览器里已有 `qingzheng-config`（或旧键），就以本地为准，
不会被小部件侧的数据覆盖，避免用户刚改的排班被回滚。只有 WebView 数据被清除、
本地完全空白时，才采用原生侧已保存的排班，防止页面与桌面显示两套班表。

网页在普通浏览器中运行时没有 `Android` 对象，上述桥接自动跳过，功能不受影响。

---

## 📄 许可

[MIT License](LICENSE)
