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
| **网页内核** | `app/src/main/assets/` | 轮班日历全部功能：自定义周期、班次时间、跨日班、方案切换、月度统计、年度总览、农历/节气/节日、备份恢复、头像与标题、银昼与蓝夜双主题 |
| **Android 外壳** | `app/src/main/java/…/MainActivity.java` | 经 `WebViewAssetLoader` 以同源 https 虚拟域加载页面，负责返回键、外链与设置桥 |
| **原生桥** | `app/src/main/assets/assets/android-bridge.js` | 本项目自有：从小部件侧回读排班、供返回键关闭页面弹窗 |
| **桌面小部件** | `app/src/main/java/…/MyWidgetProvider.java` | 读取网页保存的排班，显示今日班次、周期进度与上下班倒计时 |
| **设置桥** | `MainActivity.SettingsInterface` | 网页保存时经 `Android.saveSettings()` 写入 SharedPreferences，供小部件读取 |

网页运行时不依赖任何外部字体、图标或 CDN，也不需要网络权限；素材全部在 `assets/` 内本地存放。

> 网页内核自 v2 起以 **ES module** 启动。`file://` 的 origin 为 `null`，module 请求会被当作
> 跨源拦截，因此 WebView 不能再用 `file:///android_asset/` 直接加载，改用
> `WebViewAssetLoader` 映射到 `https://appassets.androidplatform.net/`（详见 [设计说明](docs/DESIGN_NOTES.md)）。

---

## 🚀 直接使用网页版

网页内核可以脱离 Android 独立运行：

```sh
npm start                 # 默认 http://127.0.0.1:4175
```

> 页面使用 ES module，**必须经 HTTP(S) 加载**，不能直接双击 `index.html`。
> 本机预览服务已经满足这一要求。

功能要点：

- 点击「排班设置」，选周期起始日期，再用空格填写重复的班次顺序，例如
  `白班 夜班 休息 白班 夜班 休息 休息 休息`。起始日期对应第一个班次。
- 每个班次可设颜色、工作/休息属性与上下班时间。下班时间早于或等于上班时间时视为次日下班，
  也可手动勾选「跨日」；例如 09:00 至次日 17:00 计为 32 小时。
- 修改期间使用独立草稿；「取消」、关闭弹窗或按 Esc 都会放弃修改。
- 「保存方案」记录当前日期、周期与班次定义，顶部方案按钮用于切换，旁边的 × 用于删除。
  命名使用页面内表单（不依赖浏览器原生输入弹窗），空白、超长与重名会在窗口内提示。
- 日历显示班次、农历与节气；年度总览可点击月份或小日期跳转；侧栏显示未来七天。
- 月度统计跟随当前查看的月份；计划工时按班次开始日计入当月。
- 顶部月亮 / 太阳按钮手动切换主题，时钟按钮启用昼夜自动模式（07:00–19:00 银昼，其余蓝夜）。
- 月历旁的 Q 版小陪伴：银昼是大肥龙，蓝夜是大肥鱼。点击人物在轻松短句与当前排班提示
  （距离下班 / 距离下次上班 / 明日班次 / 下个休息日）之间切换，气泡可收起。
- 月历支持左右滑动切月；「备份与恢复」可导出、导入排班数据；「头像与标题」可自定义外观。
- 键盘：← / → 切月，T 回到今天，Esc 关闭弹窗或详情。
- 农历与节气数据（2025–2030）离线存放于 `assets/calendar-data.js`，来源为香港天文台公历与农历对照表。
  已覆盖年份之外，农历回退到浏览器内置的中国历法。

配置与方案只保存在当前浏览器（或 WebView 的 localStorage），不上传服务器。
网页兼容旧版存储键 `qingzheng-anchor`、`qingzheng-cycle`、`qingzheng-shift-defs`，保存后使用单条 `qingzheng-config`。

> 网页内核自 v2 起以 **ES module** 启动，不能直接双击 `index.html` 打开，
> 需经 HTTP(S) 加载。本仓库的 `npm start` 已提供符合该要求的本地预览服务。

---

## 🗂️ 目录结构

```text
ScheduleShifts/
├── app/src/main/
│   ├── assets/                    # ★ 网页内核（同时是 Android 的内嵌页面）
│   │   ├── index.html             # 页面结构
│   │   └── assets/
│   │       ├── main.js            # ES module 启动入口（额外挂载原生桥）
│   │       ├── app.js             # 主题、实时更新与模块协调
│   │       ├── schedule.js        # 排班模型与周期计算
│   │       ├── schedule-core.js   # 配置校验与日期、班次计算
│   │       ├── settings.js        # 排班设置与方案管理
│   │       ├── views.js           # 月历、统计与详情渲染
│   │       ├── ui.js              # 弹窗、焦点与动效工具
│   │       ├── companion.js       # Q 版陪伴
│   │       ├── festivals.js       # 节日与节气
│   │       ├── data-tools.js      # 备份与恢复
│   │       ├── backup-format.js   # 备份编码与校验
│   │       ├── personalization.js # 头像与标题
│   │       ├── storage.js         # 本地存储读写
│   │       ├── config.js          # 默认班次与配色预设
│   │       ├── calendar-data.js   # 农历月首与节气数据（2025–2030）
│   │       ├── android-bridge.js  # ★ 本项目自有：原生桥（反向读取 / 关弹窗）
│   │       ├── *.css              # 主题、节日、陪伴、动效与输入页样式
│   │       ├── *.webp / *.png     # 人物与头像素材
│   │       └── *.svg              # 本地矢量装饰
│   ├── java/com/example/myapp/
│   │   ├── MainActivity.java      # WebView 宿主与设置桥
│   │   ├── MyWidgetProvider.java  # 排班计算、事件与响应式更新
│   │   ├── WidgetRenderer.java    # 四种固定构图与成组比例定位
│   │   └── WidgetRefresh.java     # 状态边界精确刷新与恢复
│   ├── res/                       # 图标、布局、银昼/蓝夜配色
│   └── AndroidManifest.xml
├── tests/                         # 网页内核与小部件同步的回归测试
├── scripts/serve.mjs              # 无依赖本机预览服务
├── docs/DESIGN_NOTES.md           # 银昼 / 蓝夜视觉与交互设计说明
├── docs/BACKUP_FORMAT.md          # 备份编码与数据版本约定
├── package.json                   # 仅用于开发测试，网页运行不需要
└── README.md
```

> 页面用 `assets/xxx.css` 这类**相对路径**引用资源，因此 `assets/assets/` 这一层是必需的，
> 不能为了「看起来更整齐」把它拍平。

---

## ✅ 测试

网页内核带有回归测试，覆盖配置校验与恢复、草稿取消、输入校验、跨日与跨午夜、
方案切换与命名表单、滑动切月、日期边界、节日与农历、主题切换、Q 版陪伴的文案与折叠、
备份导入导出、个性化外观、输入页的绘制隔离，以及网页与原生侧的数据同步。

```sh
npm ci        # 仅首次，安装 jsdom
npm test
```

测试直接读取 `app/src/main/assets/`，与 Android 打包的是同一份文件。

Android 侧使用 JUnit 与 Robolectric 测试排班计算、四种小部件构图、原生计时、刷新触控和设置同步：

```sh
.\gradlew.bat testOriginalDebugUnitTest testScheduleDebugUnitTest
```

布局测试覆盖成组定位、横纵缩放、圆角安全区、不同像素密度与字体大小、长文案、
桌面上报尺寸与实际测量尺寸不同，以及布局升级重载。自动化测试不能代替各厂商桌面的真机适配检查。

---

## 🔨 编译构建

### 环境要求
- Android SDK（API 34）与 Build-Tools
- JDK 17+
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
def appVersionCode = 19
def appVersionName = "0.2.7"
```

> ⚠️ `versionCode` 必须**严格递增**，否则覆盖安装会被系统拒绝
> （`INSTALL_FAILED_VERSION_DOWNGRADE`）。

覆盖安装需保持包名与签名一致，并使用更高的 `versionCode`。两个应用变体的数据独立，
不能通过安装另一包名来继承现有排班。

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

小组件提供四个独立入口，在桌面小组件列表中选择形态。调整尺寸时保持所选构图，
只改变各组位置与显示比例。点击主体打开排班页面，点击右上角刷新按钮校准数据。

小组件与网页共用排班配置，外观跟随**系统深浅色模式**，独立于网页的主题设置。
浅色人物为大肥龙，深色人物为大肥鱼；构建时从网页 PNG 原图生成最长边 320px 的透明原生资源。

### 四种固定构图

| 形态 | 基准画布 | 固定内容 | 最小调整尺寸 |
|------|----------|----------|--------------|
| 最小卡 | 128×128dp | 短日期、班名、倒计时、当前时间 | 110×110dp |
| 紧凑方卡 | 184×184dp | 日期、班名及状态、倒计时、当前时间 | 168×168dp |
| 横条 | 360×128dp | 日期 / 当前时间两组；班次 / 倒计时 / 右侧人物三列 | 224×92dp |
| 完整方卡 | 304×320dp | 日期与时间、班次及人物、倒计时及目标、未来七天与周期 | 224×240dp |

格数只作为选择器建议，实际大小由桌面的内容区域决定。四种形态共用排班、计时、
刷新和设置数据，分别添加或移除；剩余小组件仍保持正常刷新。

### 分组与比例

| 组 | 组内元素 | 定位原则 |
|----|----------|----------|
| 日期 / 时间 | 日期；当前时间标签与 TextClock | 方卡左侧基线一致；横条分别对齐左右两列 |
| 班次 | 图标、班名、状态 / 工作时段 | 组内统一比例，不靠弹性空白把状态推到另一端 |
| 人物 | 横条与完整卡的 Q 版人物 | 独立有界正方形，随主体同比缩放；避开倒计时与完整刷新触摸区 |
| 倒计时 | 目标标签、数字；完整卡附目标日期与班次 | 一起定位、缩放，数字始终主于当前时间 |
| 未来七天 | 标题、周期与进度、七个日期与图标 | 只在完整卡出现，七个等宽单元随整行展开，字号和行高同比缩放 |
| 刷新 | 图标与独立触摸目标 | 图标按统一比例缩放，触摸目标不小于 48dp |

组中心按安全内容区域的横纵比例定位。字号、图标、人物、行高与组内间隙统一使用
`s = min(区域宽 / 基准宽, 区域高 / 基准高)` 等比缩放；最小卡、紧凑卡与横条的文字组宽度也随 s 缩放。

完整方卡的日期、班次、倒计时与未来七天共用横向比例内容带，随桌面实际测量宽度展开。
只拉宽时，七天单元与周期进度使用新增空间，文字和人物保持等比。人物使用独立尺寸约束，
刷新按钮的图标与至少 48dp 的触摸区分开。具体几何与资源结构见 [设计说明](docs/DESIGN_NOTES.md)。

布局使用 RemoteViews 支持的原生控件，时钟和倒计时保持动态。长班名在预留区域省略，
并保留完整无障碍描述；极长小时数通过调整字号保留完整数字。

Android 12+ 的 `OPTION_APPWIDGET_SIZES` 以及旧系统横竖屏尺寸仍用于计算比例，但每个响应式子视图始终使用用户选定的同一布局。

### 实时刷新与系统限制

- 时钟由桌面中的 `TextClock` 逐秒更新；Android 7+ 的倒计时由 `Chronometer` 倒计时模式逐秒更新，即使应用进程退出也能继续走动。
- Android 5–6 没有原生倒计时模式，采用亮屏时非唤醒的逐秒闹钟兼容路径，熄屏停止；推荐 Android 7+。
- 不用后台服务持续每秒推送。上班、下班、跨日班结束、午夜以及倒计时不足 10 小时 / 1 小时的格式边界会重新绑定；倒计时通过有效的 `%s` 格式补齐为 `HH:mm:ss`，超过 99 小时保留完整小时数。
- 重新绑定会替换倒计时的 `Chronometer` 实例，其余内容保持原位。刷新按钮提供原生按下反馈，校准完成后显示“小组件已刷新”。
- Android 7+ 另有不依赖应用进程常驻的分钟级恢复尝试，使用非唤醒闹钟，熄屏时不重绘；保留非唤醒的半小时兜底。分钟级恢复不是系统保证的时限，休眠、省电及权限限制均可能延后。
- Android 12+ 使用“闹钟和提醒”特殊访问权限，让状态边界精确触发；从小组件打开应用时会说明并提供授权入口。
- 未授权时退回系统允许的非精确闹钟，正常的原生计时不受影响，但班次切换、格式补零切换及冻结后的恢复可能延迟；打开应用、解锁（进程仍在时）或点击刷新可立即校准。
- 监听重启、应用升级、系统校时、时区与日期变化；进程存活时也会在亮屏、解锁、系统主题变化和分钟广播重新校准。
- 小米等厂商的强制冻结 / 强制停止仍可能阻止广播和闹钟，需要允许应用自启动及正常后台运行；应用无法绕过系统限制。

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
