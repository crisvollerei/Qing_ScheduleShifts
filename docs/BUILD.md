# 构建与打包

## 环境要求

- Android SDK（API 34）与 Build-Tools
- JDK 17+
- Gradle 8.5（已内置 `gradlew`）

## 构建命令

```bash
.\gradlew.bat assembleDebug      # Windows
.\gradlew.bat assembleRelease
./gradlew assembleRelease        # macOS / Linux
```

| 变体         | 包名                     | 应用名  | 输出目录                                      |
| ---------- | ---------------------- | ---- | ----------------------------------------- |
| `original` | `com.example.myapp`    | 清蒸排班 | `app/build/outputs/apk/original/release/` |
| `schedule` | `com.example.schedule` | 排班   | `app/build/outputs/apk/schedule/release/` |

两个变体共用同一套源码与资源，仅包名和应用名不同，可在同一台设备共存，数据按包名隔离。

## 一键打包

根目录的 `package-apk.ps1` 会读版本号、跑单元测试、构建两个 Release 并复制到根目录：

```powershell
.\package-apk.ps1                # 完整流程（含测试）
.\package-apk.ps1 -SkipTests     # 跳过测试
.\package-apk.ps1 -Clean         # 先 clean 再构建
```

发布包分发文件名：

| 源文件                        | 分发文件名                              |
| -------------------------- | ---------------------------------- |
| `app-original-release.apk` | `清蒸排班_v<版本名>.apk`               |
| `app-schedule-release.apk` | `排班_v<版本名>.apk`                  |

构建输出保存在 `app/build/`；APK、AAB、构建缓存和本机签名材料均由 `.gitignore` 排除，
不纳入源码提交。

## 签名

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

## 修改应用名

`app/build.gradle` 的 `manifestPlaceholders`，`AndroidManifest.xml` 引用 `${appLabel}`：

```groovy
original { manifestPlaceholders = [appLabel: "清蒸排班"] }
schedule { manifestPlaceholders = [appLabel: "排班"] }
```

## 修改版本号

`app/build.gradle` 顶部集中维护：

```groovy
def appVersionCode = 34
def appVersionName = "0.3.4"
```

> ⚠️ `versionCode` 必须**严格递增**，否则覆盖安装会被系统拒绝
> （`INSTALL_FAILED_VERSION_DOWNGRADE`）。

覆盖安装需保持包名与签名一致，并使用更高的 `versionCode`。两个应用变体的数据独立，
不能通过安装另一包名来继承现有排班。

## 修改应用图标

图标位于 `res/mipmap-*`，按密度提供四种形态：

| 文件                           | 用途                   | mdpi | hdpi | xhdpi | xxhdpi | xxxhdpi |
| ---------------------------- | -------------------- | ---- | ---- | ----- | ------ | ------- |
| `ic_launcher.png`            | 普通图标（圆角方形）           | 48   | 72   | 96    | 144    | 192     |
| `ic_launcher_round.png`      | 圆形图标                 | 48   | 72   | 96    | 144    | 192     |
| `ic_launcher_foreground.png` | 自适应图标前景              | 108  | 162  | 216   | 324    | 432     |
| `ic_launcher_monochrome.png` | Android 13+ 主题图标     | 108  | 162  | 216   | 324    | 432     |

自适应图标定义在 `res/mipmap-anydpi-v26/ic_launcher.xml`，
背景色为 `@color/ic_launcher_background`（`#8A576A`，取自原图边缘色调）。

更换图标时需要注意两点，否则会明显走样：

1. **前景要满幅出血**，不要把人物缩进中央 72dp 安全区——源插画本身是满幅构图，
   缩进后人物会小一圈，和普通图标观感对不上。四周留白由系统遮罩负责裁切。
2. **单色图标是剪影**，会被系统整体染成壁纸主题色，因此只需干净的人形轮廓，
   且要填充内部空洞（面部浅色区会形成破洞）。

也可用 Android Studio：右键 `res/` → **New → Image Asset** → **Launcher Icons**
重新生成整套图标。

## 主题配色

网页内外两套主题需保持一致：

| 主题     | 网页                                           | Android 资源                    |
| ------ | -------------------------------------------- | ----------------------------- |
| 银昼（浅色） | `assets/styles.css` / `themes.css` 的 `:root` | `res/values/colors.xml`       |
| 蓝夜（深色） | 同上 `[data-theme="dark"]`                     | `res/values-night/colors.xml` |

改主题色时两处都要更新，否则小部件与页面会不同色。

## 修改包名（applicationId）

修改 `app/build.gradle` 的 `namespace` 与 `productFlavors` 中的 `applicationId`。
注意 `com.example.*` 是 Google Play 拒绝的保留命名空间，上架前请换成自有域名。
