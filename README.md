# MyApp

> ⚠️ **免责声明**：本项目借助 AI 工具（腾讯元宝）辅助开发完成，仅供参考和学习研究使用。

---

## 📱 项目简介

这是一个 Android 应用，包含主界面和一个桌面小部件（Widget）。

**主要功能：**
- 主界面 Activity
- 桌面小部件（Widget）显示倒计时等信息
- 自定义头像/品牌展示

---

## 🛠️ 如何修改 App 图标

### 方式一：替换图标文件（推荐）

准备好你的 PNG 图标（建议尺寸 1024×1024），按以下路径替换所有分辨率的图标文件：

| 目录 | 图标尺寸 | 用途 |
|------|---------|------|
| `app/src/main/res/drawable-xxxhdpi/` | 192×192 px | 超超高分辨率 |
| `app/src/main/res/drawable-xxhdpi/` | 144×144 px | 超高分辨率 |
| `app/src/main/res/drawable-xhdpi/` | 96×96 px | 高分辨率 |
| `app/src/main/res/drawable-hdpi/` | 72×72 px | 高分辨率 |
| `app/src/main/res/drawable-mdpi/` | 48×48 px | 中等分辨率 |
| `app/src/main/res/drawable/` | 48×48 px | 默认图标 |

> 💡 可使用 Android Studio 的 **Image Asset** 工具自动生成各尺寸图标：
> 右键 `res/` → **New → Image Asset** → 选择 **Launcher Icons**

### 方式二：通过 AndroidManifest.xml 修改图标引用

如果你的图标文件名不同，需同步修改 `AndroidManifest.xml` 中的引用：

```xml
<application
    android:icon="@mipmap/你的图标文件名"
    android:roundIcon="@mipmap/你的图标文件名"
    ... >
```

---

## 📛 如何修改 App 名称

### 修改应用显示名（桌面图标下方文字）

编辑 `app/src/main/res/values/strings.xml`，添加或修改 `app_name`：

```xml
<resources>
    <string name="app_name">你的应用名称</string>
</resources>
```

> 如果没有 `strings.xml` 文件，可以直接在 `values/styles.xml` 中引用或新建该文件。

### 修改 Widget 小部件名称

在 `app/src/main/res/xml/my_widget_info.xml` 中找到 `android:label` 属性进行修改：

```xml
<appwidget-provider
    android:minWidth="180dp"
    android:minHeight="110dp"
    android:updatePeriodMillis="86400000"
    android:initialLayout="@layout/widget_layout"
    android:resizeMode="horizontal|vertical"
    android:widgetCategory="home_screen"
    android:label="你的 Widget 名称" />
```

---

## 🖼️ 如何自定义 avatar.png

项目中有两处 `avatar.png` 文件，分别用于不同场景：

| 路径 | 用途 |
|------|------|
| `app/src/main/res/drawable/avatar.png` | 应用内 UI 展示（如主界面中的头像） |
| `app/src/main/assets/avatar.png` | Widget 小部件中显示的头像 |

**修改方法：** 直接用你的图片文件替换上述两个路径中的 `avatar.png` 即可。建议使用 1:1 正方形图片，效果最佳。

---

## 🚀 编译构建

### 环境要求
- Android SDK（API 34）
- JDK 11+
- Gradle 8.5（已内置 `gradlew`，无需单独安装）

### 构建 Debug APK

```bash
# Windows
.\gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

### 构建 Release APK（需签名）

```bash
.\gradlew.bat assembleRelease
```

> 签名配置可在 `app/build.gradle` 中添加自定义签名配置。

### 在 Android Studio 中打开

1. 选择 **File → Open**
2. 选择 `D:\app\MyApp` 文件夹
3. 等待 Gradle Sync 完成后即可运行

---

## 📁 目录结构

```
MyApp/
├── build.gradle              # 根级构建配置
├── settings.gradle           # 项目设置
├── gradle.properties         # Gradle 属性
├── gradlew / gradlew.bat     # Gradle 包装脚本
├── gradle/wrapper/           # Gradle 包装器
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
└── app/
    ├── build.gradle          # App 模块构建配置
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/           # 原始资源（avatar.png 等）
        ├── java/com/example/myapp/  # Java 源代码
        │   ├── MainActivity.java
        │   ├── MyWidgetProvider.java
        │   └── MyWidgetService.java
        └── res/              # Android 资源文件
            ├── drawable/     # 可绘制资源
            ├── drawable-hdpi/mdpi/xhdpi/xxhdpi/xxxhdpi/  # 多分辨率图标
            ├── layout/      # 布局文件
            ├── values/      # 颜色、样式等
            └── xml/         # Widget 配置
```

---

## ⚙️ 其他配置

### 修改应用包名（applicationId）

在 `app/build.gradle` 中修改：

```groovy
defaultConfig {
    applicationId "com.example.myapp"  // 改为你的包名
    ...
}
```

修改后记得同步更新 `AndroidManifest.xml` 中的 `package` 属性以及 `build.gradle` 中的 `namespace`。

### 修改版本号

在 `app/build.gradle` 的 `defaultConfig` 中：

```groovy
versionCode 2        // 递增整数版本号
versionName "1.1"    // 显示给用户的版本字符串
```

---

> 本项目由 **AI 辅助开发**，如有问题欢迎提交 Issue 或 Pull Request。
