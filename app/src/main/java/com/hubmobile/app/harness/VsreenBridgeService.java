package com.hubmobile.app.harness;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * 虚拟屏桥 —— 阶段 J：从「永远 false 的空占位」升级为真实状态机。
 *
 * 上游完整实现有 2072 行，靠三样本版拿不到：
 *   ① moe.shizuku.server.IRemoteProcess / IShizukuService（Shizuku 服务端 hidden AIDL）
 *   ② assets/vscreen_shizuku.jar（以 shell uid 由 app_process 加载的特权服务端，
 *      上游源码树里没有，只在它的发行 APK 里）
 *   ③ 用户在 Shizuku App 里对本包的授权
 *
 * 所以这里不假装能建屏，但把能做的都做真：
 *   · 反射探测 Shizuku binder 是否活、权限是否已授（判据与上游 shizukuAvailable() 一致）
 *   · 按「未装 / 未授权 / 缺服务端 jar」三种情况给出准确提示
 *   · 保留上游 OverlayService 调用的 4 个入口，不静默崩
 *
 * 拿到 ①②③ 之后，把本文件换成上游 2072 行实现即可 100% 对齐。
 */
public final class VsreenBridgeService {

    public static volatile boolean sVscreenRunning = false;
    private static volatile String lastReason = "";

    public static boolean isVscreenRunning() { return sVscreenRunning; }

    public static String lastReason() { return lastReason; }

    /** 反射探测：binder 活 + 已授权，与上游判据一致。 */
    public static boolean shizukuReady() {
        try {
            Class<?> c = Class.forName("rikka.shizuku.Shizuku");
            Object ping = c.getMethod("pingBinder").invoke(null);
            if (!Boolean.TRUE.equals(ping)) return false;
            Object perm = c.getMethod("checkSelfPermission").invoke(null);
            return perm instanceof Integer && ((Integer) perm) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 特权服务端 jar 是否随包（本版 payload 里没有，上游发行 APK 的 assets 里才有）。 */
    public static boolean coreJarPresent(Context c) {
        try {
            java.io.File f = new java.io.File(c.getFilesDir(), "vscreen/vscreen_shizuku.jar");
            if (f.isFile() && f.length() > 1024) return true;
            java.io.File ext = new java.io.File(
                    android.os.Environment.getExternalStorageDirectory(),
                    "DeepSeekHarness/vscreen/vscreen_shizuku.jar");
            return ext.isFile() && ext.length() > 1024;
        } catch (Throwable t) { return false; }
    }

    /** 控制台 /overlay 的 vscreen 状态字段。 */
    public static String statusJson(Context c) {
        boolean sh = shizukuReady(), jar = coreJarPresent(c);
        String reason = !sh ? "需安装 Shizuku 并对 githup 授权"
                : (!jar ? "缺特权服务端 vscreen_shizuku.jar" : "就绪");
        try {
            return new org.json.JSONObject()
                    .put("available", sh && jar)
                    .put("running", sVscreenRunning)
                    .put("shizuku", sh).put("coreJar", jar)
                    .put("reason", reason).toString();
        } catch (Throwable t) { return "{\"available\":false}"; }
    }

    /** 通知/桌宠面板点「开虚拟屏」：不假装成功，把真实缺什么说出来。 */
    public static void toggleFromUi() {
        boolean sh = shizukuReady();
        if (!sh) {
            sVscreenRunning = false;
            lastReason = "需先安装 Shizuku 并在其中对 githup 授权";
        } else {
            sVscreenRunning = false;
            lastReason = "缺特权服务端 vscreen_shizuku.jar（上游发行 APK 的 assets 内）";
        }
        try {
            OverlayService s = OverlayService.get();
            if (s != null) s.pushFluid("虚拟屏不可用：" + lastReason);
        } catch (Throwable ignored) {}
    }

    public static void destroyVscreenFromWhale() {
        sVscreenRunning = false;
    }

    private VsreenBridgeService() {}
}
