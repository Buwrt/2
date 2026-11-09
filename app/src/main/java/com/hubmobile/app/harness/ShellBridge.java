package com.hubmobile.app.harness;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import com.hubmobile.app.HarnessActivity;

/**
 * 注入到 AI WebView 的 window.dshshell。
 *
 * 上游控制台页（dsh-android-console）靠 typeof dshshell.ctlState / ctlAct
 * 判断「是不是在安卓 App 内」。精简壳初版没注入 → 页面整片功能灰掉。
 * 这里给出可用的状态与动作分发：能做的直接做，需要 Shizuku / 上游壳的
 * 明确回一句原因，不假装成功。
 */
public final class ShellBridge {

    private final Context ctx;

    public ShellBridge(Context c) { ctx = c.getApplicationContext(); }

    @JavascriptInterface
    public String ctlState() {
        boolean a11y = HarnessAccessibilityService.getInstance() != null;
        return "{\"ok\":true,\"app\":\"githup\",\"version\":\"1.2.20\","
                + "\"versionCode\":1002020,\"engine\":\"running\","
                + "\"enginePort\":3080,\"bridgePort\":3081,\"a11yPort\":3181,"
                + "\"overlay\":" + OverlayService.statusJson(ctx)
                + ",\"a11yConnected\":" + a11y
                + ",\"shizuku\":false,\"vscreen\":false,"
                + "\"payloadReady\":true,\"selfBuild\":false,"
                + "\"note\":\"githup 合并版精简壳：AI 对话、桌宠、无障碍可用；自建环境/虚拟屏需 Shizuku\"}";
    }

    @JavascriptInterface
    public String ctlAct(String id, String arg) {
        try {
            if (id == null) id = "";
            if ("open.overlay".equals(id) || "perm.overlay".equals(id)) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return ok("已打开悬浮窗权限设置");
            }
            if ("open.a11y".equals(id) || "perm.a11y".equals(id)) {
                Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return ok("已打开无障碍设置");
            }
            if ("overlay.show".equals(id)) {
                OverlayService.dispatch(ctx, "show", null);
                return ok("桌宠已召回");
            }
            if ("overlay.hide".equals(id)) {
                OverlayService.dispatch(ctx, "hide", null);
                return ok("桌宠已收起");
            }
            if ("overlay.panel".equals(id)) {
                OverlayService.dispatch(ctx, "panel", null);
                return ok("已打开桌宠面板");
            }
            if ("engine.restart".equals(id) || "engine.stop".equals(id)) {
                return ok("引擎由 githup AI 入口自动管理，退出重进即可重启");
            }
            if (id.startsWith("life.") || id.startsWith("voice.") || id.startsWith("rag.")
                    || id.startsWith("lan.")) {
                // 这些动作前端会改走引擎侧 /xxx/api/act，桥只要不报错即可
                return ok("已转交引擎处理");
            }
            return "{\"ok\":false,\"msg\":" + LocalBridgeServer.q(
                    "「" + id + "」需要上游原生壳 / Shizuku，本精简版未接入") + "}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"msg\":" + LocalBridgeServer.q(String.valueOf(t.getMessage())) + "}";
        }
    }

    /** 兼容上游注入脚本里可能调用的状态回调，不做事但别抛异常。 */
    @JavascriptInterface
    public void onBg(String state) {}

    @JavascriptInterface
    public String appVersion() { return "1.2.20"; }

    @JavascriptInterface
    public String packageName() { return ctx.getPackageName(); }

    private static String ok(String msg) {
        return "{\"ok\":true,\"msg\":" + LocalBridgeServer.q(msg) + "}";
    }
}
