package com.hubmobile.app.harness;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.io.File;

/**
 * 注入到 AI WebView 的 window.dshshell。
 *
 * 上游控制台页（dsh-android-console）靠 typeof dshshell.ctlState / ctlAct
 * 判断「是不是在安卓 App 内」。这里把能做的动作真做，需要 Shizuku /
 * 上游完整壳的明确回原因，不假装成功。
 */
public final class ShellBridge {

    private final Context ctx;

    public ShellBridge(Context c) { ctx = c.getApplicationContext(); }

    @JavascriptInterface
    public String ctlState() {
        boolean a11y = HarnessAccessibilityService.getInstance() != null;
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true).put("app", "githup").put("version", "1.2.20")
                    .put("versionCode", 1002020).put("engine", "running")
                    .put("enginePort", 3080).put("bridgePort", 3081).put("a11yPort", 3181)
                    .put("overlay", new JSONObject(OverlayService.statusJson(ctx)))
                    .put("a11yConnected", a11y)
                    .put("shizuku", false).put("vscreen", false)
                    .put("root", RootShell.probe().ok)
                    .put("payloadReady", true).put("selfBuild", false)
                    .put("note", "githup 合并版：AI 对话 / 桌宠 / 无障碍 / 语音 / 定时任务可用；"
                            + "虚拟屏与手机内自建编译环境需 Shizuku 或上游完整壳");
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":" + LocalBridgeServer.q(String.valueOf(t.getMessage())) + "}";
        }
    }

    @JavascriptInterface
    public String ctlAct(String id, String arg) {
        try {
            if (id == null) id = "";
            String a = arg == null ? "" : arg;

            // ---- 权限 / 设置页 ----
            if (id.startsWith("open.") || id.startsWith("perm.")) {
                Intent i;
                if (id.contains("overlay")) {
                    i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + ctx.getPackageName()));
                } else if (id.contains("a11y") || id.contains("accessibility")) {
                    i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                } else if (id.contains("usage")) {
                    i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                } else if (id.contains("write") || id.contains("setting")) {
                    i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:" + ctx.getPackageName()));
                } else if (id.contains("battery")) {
                    i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                } else if (id.contains("notification")) {
                    i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.getPackageName());
                } else if (id.equals("open.url") && a.startsWith("http")) {
                    i = new Intent(Intent.ACTION_VIEW, Uri.parse(a));
                } else {
                    i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + ctx.getPackageName()));
                }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return ok("已打开系统设置");
            }

            // ---- 桌宠 ----
            if (id.equals("overlay.show") || id.equals("pet.show")) {
                OverlayService.dispatch(ctx, "show", null); return ok("桌宠已召回");
            }
            if (id.equals("overlay.hide") || id.equals("pet.hide")) {
                OverlayService.dispatch(ctx, "hide", null); return ok("桌宠已收起");
            }
            if (id.equals("overlay.panel")) {
                OverlayService.dispatch(ctx, "panel", null); return ok("已打开桌宠面板");
            }

            // ---- 日志 / 更新 / 备份 ----
            if (id.startsWith("log.") || id.equals("logs")) {
                File f = new File(ctx.getFilesDir(), "dsh-web.log");
                JSONObject o = new JSONObject();
                o.put("ok", true).put("size", f.exists() ? f.length() : 0)
                        .put("uri", LogShareProvider.uriForAbs(ctx, f.getAbsolutePath()).toString());
                return o.toString();
            }
            if (id.startsWith("update.")) {
                return "{\"ok\":true,\"current\":\"1.2.20\",\"versionCode\":1002020,"
                        + "\"hint\":\"在 githup 客户端内检查更新\"}";
            }
            if (id.startsWith("backup.")) {
                return new JSONObject().put("ok", true)
                        .put("hint", "完整时光机备份/恢复需上游原生壳流程；/backup 可查目录清单与体积")
                        .toString();
            }

            // ---- 语音 ----
            if (id.startsWith("voice.")) {
                return VoiceBridge.get(ctx).voice(id.substring(6));
            }
            if (id.equals("tts.speak")) {
                return VoiceBridge.get(ctx).tts("speak", a, false);
            }
            if (id.equals("tts.stop")) {
                return VoiceBridge.get(ctx).tts("stop", null, false);
            }

            // ---- 引擎 ----
            if (id.equals("engine.restart") || id.equals("engine.stop")) {
                return ok("引擎由 githup AI 入口自动管理，退出重进即可重启");
            }
            if (id.equals("engine.keepalive")) {
                EngineService.keepAlive(ctx); return ok("前台保活已拉起");
            }

            // ---- NFC ----
            if (id.equals("nfc.arm")) {
                return NfcStore.arm(ctx, true).toString();
            }
            if (id.equals("nfc.disarm")) {
                return NfcStore.arm(ctx, false).toString();
            }
            if (id.equals("nfc.status")) {
                return NfcStore.statusJson(ctx).toString();
            }

            // ---- 引擎侧插件动作：前端会改走 /xxx/api/act，桥只要不报错 ----
            if (id.startsWith("life.") || id.startsWith("voice-hub.") || id.startsWith("rag.")
                    || id.startsWith("lan.") || id.startsWith("dsh-office-kit.")
                    || id.startsWith("plugin.") || id.startsWith("theme.")) {
                return ok("已转交引擎处理");
            }

            return "{\"ok\":false,\"msg\":" + LocalBridgeServer.q(
                    "「" + id + "」需要上游完整原生壳 / Shizuku，本精简版未接入") + "}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"msg\":" + LocalBridgeServer.q(String.valueOf(t.getMessage())) + "}";
        }
    }

    /** 兼容上游注入脚本里可能调用的状态回调。 */
    @JavascriptInterface public void onBg(String state) {}

    @JavascriptInterface public String appVersion() { return "1.2.20"; }

    @JavascriptInterface public String packageName() { return ctx.getPackageName(); }

    private static String ok(String msg) {
        return "{\"ok\":true,\"msg\":" + LocalBridgeServer.q(msg) + "}";
    }
}
