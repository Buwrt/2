package com.hubmobile.app.harness;

/**
 * 虚拟屏桥接占位。
 *
 * 上游 VsreenBridgeService（2072 行）用 Shizuku AIDL newProcess() 以 shell uid 起
 * app_process 并反射创建 VirtualDisplay；本版没有 Shizuku AAR / 用户授权，
 * 所以这里只保留上游 OverlayService 调用的 4 个入口，全部安全降级：
 * 开关能点、状态能读，但虚拟屏不会真的创建，并在面板里如实标注。
 *
 * 要 100% 一致，需要：Shizuku App + 对本包授权 + 引入 Shizuku AIDL 依赖，
 * 再用上游实现替换本文件。
 */
public final class VsreenBridgeService {
    public static volatile boolean sVscreenRunning = false;

    public static boolean isVscreenRunning() { return sVscreenRunning; }

    public static void toggleFromUi() {
        // 面板点「开虚拟屏」时给一次明确反馈，不假装成功
        sVscreenRunning = false;
        try {
            OverlayService s = OverlayService.get();
            if (s != null) s.pushFluid("虚拟屏需要 Shizuku 授权，本版暂不可用");
        } catch (Throwable ignored) {}
    }

    public static void destroyVscreenFromWhale() { sVscreenRunning = false; }

    private VsreenBridgeService() {}
}
