package com.zxl.vivokeyguardnotif;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * vivo 锁屏通知解锁模块（modern LSPosed API / libxposed 102）
 *
 * vivo OriginOS 锁屏只显示"本次锁屏期间"收到的通知：
 *   - 锁屏期间新来的通知被 VivoNormalNotifCoordinator 打标 entry.mBelongToKeyguard = true
 *   - 解锁时 CentralSurfacesImpl 把所有打标批量清回 false
 *   - 锁屏渲染时 VivoKeyguardNotificationVisibilityProviderImpl.vivoKeyguardNotificationFilter()
 *     会把未打标的旧通知过滤掉
 *
 * 本模块在 filter 执行期间把 mBelongToKeyguard 临时置 true，调用返回后恢复原值，
 * 既让过滤逻辑看到 true，又不污染其他读取该字段的 SystemUI 代码路径。
 * 其余 vivo 过滤逻辑（锁屏隐私模式、用户开关、专注模式、低优先级/常驻过滤）全部保留。
 *
 * 注意：本机 LSPosed（JingMatrix Vector 架构）对 legacy 模块（assets/xposed_init）
 * 在 com.android.systemui 中不会分发，因此必须使用 modern API + META-INF/xposed/module.prop。
 */
public class ModernHookEntry extends XposedModule {
    private static final String TAG = "VKNU";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String PROVIDER =
            "com.android.systemui.statusbar.notification.interruption.VivoKeyguardNotificationVisibilityProviderImpl";
    private static final String ENTRY =
            "com.android.systemui.statusbar.notification.collection.NotificationEntry";

    private static Field sField;
    private static boolean sFieldFailLogged;

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!SYSTEMUI.equals(param.getPackageName())) return;
        ClassLoader cl = param.getDefaultClassLoader();
        try {
            Class<?> provider = Class.forName(PROVIDER, false, cl);
            Class<?> entry = Class.forName(ENTRY, false, cl);
            sField = entry.getDeclaredField("mBelongToKeyguard");
            sField.setAccessible(true);
            Method filter = provider.getDeclaredMethod("vivoKeyguardNotificationFilter", entry);
            hook(filter).intercept(chain -> {
                Object e = chain.getArg(0);
                if (e == null || sField == null) {
                    return chain.proceed();
                }
                boolean orig = false;
                boolean marked = false;
                try {
                    orig = sField.getBoolean(e);
                    sField.setBoolean(e, true);
                    marked = true;
                } catch (Throwable t) {
                    logFieldFailOnce(t);
                }
                try {
                    return chain.proceed();
                } finally {
                    if (marked) {
                        try {
                            sField.setBoolean(e, orig);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "hook setup failed", t);
        }
    }

    private static void logFieldFailOnce(Throwable t) {
        if (sFieldFailLogged) return;
        sFieldFailLogged = true;
        Log.w(TAG, "set mBelongToKeyguard failed", t);
    }
}
