# VivoKeyguardNotifUnlock

让 vivo 锁屏显示**所有**历史通知

- 目标设备：vivo V2419A（Android 17 / OriginOS 17，已 root）
- 运行环境：KernelSU + Zygisk Next + LSPosed 2.2.1
- 模块类型：**modern LSPosed 模块**（libxposed API 102），作用域仅 `com.android.systemui`

---

## 1. 功能

OriginOS 的锁屏默认只显示"本次锁屏期间"收到的通知。解锁前收到的通知在锁屏上会被隐藏。

本模块保留 vivo 的全部原有过滤逻辑（锁屏隐私/隐藏内容、用户开关、专注模式、低优先级与常驻通知过滤等），只解除"非本次锁屏期间 → 隐藏"这一条，从而实现：

- 解锁状态下收到的通知，锁屏后依然可见
- 历史通知（本次锁屏之前到达的）全部出现在锁屏通知列表
- 不修改通知本身的显示内容，不影响通知渠道、声音、震动等行为

---

## 2. 实现原理

### 2.1 vivo 原生行为

SystemUI 内部对每条通知维护一个标记：

```
com.android.systemui.statusbar.notification.collection.NotificationEntry.mBelongToKeyguard
```

- 锁屏期间新到达的通知，会被 vivo 的协调器（`VivoNormalNotifCoordinator`）打上 `mBelongToKeyguard = true`
- 解锁时，`CentralSurfacesImpl` 会批量把这些标记清回 `false`
- 锁屏渲染时，`VivoKeyguardNotificationVisibilityProviderImpl.vivoKeyguardNotificationFilter(entry)` 会把未打标（`false`）的旧通知过滤掉

因此"旧通知看不见"的本质是 `mBelongToKeyguard` 已经被清成 `false`。

### 2.2 Hook 点

```
类：com.android.systemui.statusbar.notification.interruption.VivoKeyguardNotificationVisibilityProviderImpl
方法：public final boolean vivoKeyguardNotificationFilter(NotificationEntry)
```

在该方法执行前，把入参 entry 的 `mBelongToKeyguard` 强制置为 `true`，再放行原方法：

```java
private static Field sField; // NotificationEntry.mBelongToKeyguard（public boolean）

Method filter = provider.getDeclaredMethod("vivoKeyguardNotificationFilter", entry);
hook(filter).intercept(chain -> {
    Object e = chain.getArg(0);
    if (e != null) {
        try { sField.setBoolean(e, true); } catch (Throwable ignored) {}
    }
    return chain.proceed();
});
```

由于只改这一个字段，方法内部其余判断（隐私模式、开关、优先级等）仍按 vivo 原逻辑执行，副作用最小。

### 2.3 为什么必须用 modern API

本机 LSPosed 实测：**legacy 模块**（`assets/xposed_init` + `IXposedHookLoadPackage`）在普通 App 中正常加载，但在 `com.android.systemui` 中完全不分发（hook-debug dump 中没有任何 legacy hook 记录，多个模块均如此）。

原因是该 LSPosed 为 JingMatrix 的 Vector 新架构：legacy 分发位于 `LoadedApkHookers.kt` 的 `LoadedApkCreateCLHooker`，依赖 `isInitialLoad` 与 `LoadedApkTracker`，SystemUI 的加载路径不满足其条件；而 modern 模块的 `onPackageLoaded` / `onPackageReady` 走 `LoadedApkCreateAppFactoryHooker`，不受限制。

因此本模块使用：

- APK 内 `META-INF/xposed/module.prop`（`minApiVersion=102`、`targetApiVersion=102`）
- APK 内 `META-INF/xposed/java_init.list`（入口类 `com.zxl.vivokeyguardnotif.ModernHookEntry`）
- 入口类 `extends io.github.libxposed.api.XposedModule`，在 `onPackageLoaded(PackageLoadedParam)` 中安装 hook

---

## 3. 项目结构

```
VivoKeyguardNotifUnlock/
├─ VivoKeyguardNotifUnlock.apk            # 已编译并签名的成品（modern 版）
├─ app/src/main/
│  ├─ AndroidManifest.xml                 # package / uses-sdk / label
│  ├─ java/com/zxl/vivokeyguardnotif/
│  │  └─ ModernHookEntry.java             # 模块入口与 hook 实现
│  └─ resources/META-INF/xposed/
│     ├─ module.prop                      # modern 模块元数据
│     └─ java_init.list                   # modern 入口类列表
```

---

## 4. 构建

项目不含 Gradle，使用 Android SDK 命令行工具构建。

### 4.1 准备

- JDK：Android Studio 自带 JBR（`%LOCALAPPDATA%\..\Program Files\Android\Android Studio\jbr`）
- SDK：`android.jar`（`platforms\android-37.0`）、`build-tools\36.0.0`（含 d8 / aapt2 / zipalign / apksigner）
- libxposed API 102：只发布 `.aar`，需取其中的 `classes.jar`

```powershell
# 从 maven 下载 api-102.0.0.aar 后解出 classes.jar
# 路径参考：...\maven2\io\github\libxposed\api\102.0.0\api-102.0.0.aar
```

### 4.2 编译与打包

```powershell
$JAVA = "C:\Program Files\Android\Android Studio\jbr"
$BT   = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0"
$AJ   = "$env:LOCALAPPDATA\Android\Sdk\platforms\android-37.0\android.jar"
$API  = "$env:TEMP\libxposed-api.jar"   # 来自 aar 的 classes.jar

# 1) 编译（注意：API 仅用于编译）
& "$JAVA\bin\javac.exe" --release 11 -cp "$AJ;$API" -d out app\src\main\java\com\zxl\vivokeyguardnotif\ModernHookEntry.java

# 2) dex（只输入模块自身 class，切勿把 API 桩类打进 dex）
New-Item -ItemType Directory -Force out\dex | Out-Null
& "$BT\d8.bat" --min-api 27 --lib "$AJ" --lib "$API" --output out\dex out\com\zxl\vivokeyguardnotif\ModernHookEntry.class

# 3) 生成基础 APK（aapt2 保证 resources.arsc 不压缩）
& "$BT\aapt2.exe" link -o out\base.apk --manifest app\src\main\AndroidManifest.xml -I "$AJ" --min-sdk-version 27 --target-sdk-version 34
Copy-Item out\base.apk out\module.apk -Force

# 4) 写入 dex 与 modern 元数据（jar 的 -C 只作用于紧跟的一个参数，故分三条）
& "$JAVA\bin\jar.exe" uf out\module.apk -C out\dex classes.dex
& "$JAVA\bin\jar.exe" uf out\module.apk -C app\src\main\resources META-INF\xposed\module.prop
& "$JAVA\bin\jar.exe" uf out\module.apk -C app\src\main\resources META-INF\xposed\java_init.list

# 5) 对齐与签名
& "$BT\zipalign.exe" -f -p 4 out\module.apk out\module-aligned.apk
& "$BT\apksigner.bat" sign --ks debug.keystore --ks-pass pass:<你的keystore密码> --key-pass pass:<你的key密码> --ks-key-alias <你的key别名> --out VivoKeyguardNotifUnlock.apk out\module-aligned.apk
```

> 注意：不要用通用 zip 工具（如 .NET ZipArchive）重打包，`resources.arsc` 会被压缩，导致
> `INSTALL_FAILED_... Targeting R+ requires the resources.arsc ... stored uncompressed and aligned`。

---

## 5. 兼容性与注意事项

- 仅针对本 ROM 的类名/字段名实现；系统 OTA 升级后 `VivoKeyguardNotificationVisibilityProviderImpl` 或 `mBelongToKeyguard` 可能变更，需要重新确认（dexdump 查 `classes.dex`）。
- 模块仅在 `com.android.systemui` 作用域内工作，其他进程不注入，开销可忽略。
- 使用自签名 debug keystore，仅供个人设备使用。

---

## 6. 下载

已编译并签名的模块见仓库根目录：`VivoKeyguardNotifUnlock.apk`（自签名，仅供个人设备使用）。

---

## 7. 许可证

[MIT](LICENSE)
