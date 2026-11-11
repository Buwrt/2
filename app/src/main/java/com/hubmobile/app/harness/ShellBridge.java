package com.hubmobile.app.harness;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.hubmobile.app.HarnessBridge;

/**
 * 注入到 AI WebView 的 window.dshshell —— 阶段 J：对齐上游完整控制台。
 *
 * 上游控制台页（dsh-android-console）只用两个窄接口：
 *   dshshell.ctlState()          → 状态 JSON（17 个字段）
 *   dshshell.ctlAct(id, arg)     → 执行一个动作，立刻回 {"ok":true}
 *
 * 阶段 B~H 这里是 165 行精简版：36 个动作里大半落到
 * 「需要上游完整壳 / Shizuku」或假成功「已转交引擎处理」。
 * 本版按上游 MainActivity.ctlStateJson() / ctlActionOnUi() 重写，
 * 把 BuildEnvInstaller / KernelStore / BackupRestore / LogShareProvider /
 * NotificationArchive / ScheduleBridge / VoiceManager / OverlayService 全部真接上。
 */
public final class ShellBridge {

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ---- 控制台异步状态（前端每 5 秒 readState() 轮询）----
    private static volatile boolean beResolving = false, beInstalling = false;
    private static volatile String beStage = "", beErr = "";
    private static volatile List<BuildEnvInstaller.Source> beSources = null;
    private static volatile int bootFails = 0;
    private static volatile boolean safeMode = false;

    public ShellBridge(Context c) { ctx = c.getApplicationContext(); }

    private SharedPreferences prefs() { return ctx.getSharedPreferences("dsh_prefs", Context.MODE_PRIVATE); }

    // ==================== 状态 ====================

    @JavascriptInterface
    public String ctlState() {
        boolean a11y = HarnessAccessibilityService.getInstance() != null;
        boolean engineUp = engineAlive();
        File payload = new File(ctx.getFilesDir(), "payload");
        boolean ready = new File(payload, "runtime/bin/node").isFile();
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true).put("app", "githup").put("version", "1.2.20")
                    .put("versionCode", 1002020)
                    .put("enginePort", com.hubmobile.app.harness.EnginePorts.get(ctx))
                    .put("bridgePort", 3081).put("a11yPort", 3181)
                    .put("payloadReady", ready).put("a11yConnected", a11y)
                    .put("root", RootShell.probe().ok)
                    .put("shizuku", shizukuAvailable())
                    .put("vscreen", VsreenBridgeService.isVscreenRunning())
                    .put("vscreenStatus", new JSONObject(VsreenBridgeService.statusJson(ctx)))
                    .put("selfBuild", BuildEnvInstaller.isInstalled(ctx))
                    .put("overlay", new JSONObject(OverlayService.statusJson(ctx)));

            // ---- 解压 ----
            JSONObject ex = new JSONObject();
            ex.put("ready", ready).put("busy", false)
                    .put("state", ready ? "已解压" : "未解压")
                    .put("meta", ready ? "41,426 个引擎文件" : "进入 githup AI 时自动展开");
            o.put("extract", ex);

            // ---- 引擎 ----
            JSONObject en = new JSONObject();
            en.put("running", engineUp).put("booting", false).put("busy", false)
                    .put("state", engineUp ? "引擎运行中" : "未启动")
                    .put("meta", engineUp ? "127.0.0.1:"
                            + com.hubmobile.app.harness.EnginePorts.get(ctx)
                            : "由 githup AI 入口管理");
            o.put("engine", en);

            // ---- 救援 ----
            JSONObject rs = new JSONObject();
            rs.put("safeMode", safeMode).put("fails", bootFails).put("warn", bootFails >= 3)
                    .put("desc", safeMode
                            ? "已旁置 profile 用户层，用出厂配置启动；会话 / 凭证 / 设置都还在。"
                            : "引擎起不来时可用安全模式跳过用户层（不丢数据），也可导出全站备份。");
            o.put("rescue", rs);

            // ---- 主题 / 思考深度 ----
            int mode = prefs().getInt("theme_mode", 0);
            JSONObject th = new JSONObject();
            th.put("mode", mode).put("label", mode == 1 ? "浅色" : mode == 2 ? "跟随系统" : "深色");
            o.put("theme", th);
            String eff = prefs().getString("reasoning_effort", "medium");
            o.put("reasoning", new JSONObject().put("effort", eff).put("label", eff));

            // ---- 权限 ----
            JSONObject pm = new JSONObject();
            JSONArray pr = new JSONArray();
            String[][] perms = {
                    {"overlay", "悬浮窗", "显示在其他应用上层", String.valueOf(canDraw())},
                    {"a11y", "无障碍", "读屏 / 点按 / 手势", String.valueOf(a11y)},
                    {"usage", "使用情况", "读取应用使用统计", String.valueOf(UsageStatsHelper.permissionGranted(ctx))},
                    {"notification", "通知读取", "捕获通知内容", "true"},
                    {"storage", "存储", "读写 /sdcard/DeepSeekHarness", String.valueOf(ready)},
            };
            int granted = 0;
            for (String[] p : perms) {
                boolean ok = Boolean.parseBoolean(p[3]);
                if (ok) granted++;
                pr.put(new JSONObject().put("id", p[0]).put("title", p[1])
                        .put("desc", p[2]).put("ok", ok).put("disabled", false)
                        .put("state", ok ? "已授权" : "未授权"));
            }
            pm.put("summary", granted + " / " + perms.length + " 项已授权").put("rows", pr);
            o.put("perm", pm);

            // ---- 工作区：阶段 M 起可以选 GitHub 仓库 ----
            o.put("workspace", new JSONObject(GhBridge.workspaceState(ctx)));

            // ---- 插件 ----
            JSONObject pg = new JSONObject();
            JSONArray pl = new JSONArray();
            String[] plugs = {"dsh-life", "dsh-tool-vscreen", "dsh-office-kit", "dsh-rag", "dsh-android-console"};
            int onCnt = 0;
            for (String p : plugs) {
                boolean on = !prefs().getBoolean("plugin_off_" + p, false);
                if (on) onCnt++;
                pl.put(new JSONObject().put("id", p).put("title", p)
                        .put("desc", "随包插件").put("on", on));
            }
            pg.put("summary", onCnt + " / " + plugs.length + " 已启用").put("rows", pl);
            o.put("plugins", pg);

            // ---- 自建编译环境 ----
            JSONObject be = new JSONObject();
            be.put("installed", BuildEnvInstaller.isInstalled(ctx))
                    .put("summary", BuildEnvInstaller.isInstalled(ctx) ? "已安装" : "未安装")
                    .put("statusText", BuildEnvInstaller.statusText(ctx))
                    .put("installing", beInstalling).put("stage", beStage)
                    .put("resolving", beResolving).put("err", beErr);
            JSONArray src = new JSONArray();
            List<BuildEnvInstaller.Source> ss = beSources;
            if (ss != null) for (int i = 0; i < ss.size(); i++)
                src.put(new JSONObject().put("index", i).put("label", ss.get(i).label));
            be.put("sources", src);
            o.put("buildEnv", be);

            // ---- 内核 ----
            o.put("kernel", kernelState());

            // ---- 日志 ----
            File log = new File(ctx.getFilesDir(), "dsh-web.log");
            o.put("log", new JSONObject()
                    .put("summary", log.exists() ? (log.length() / 1024) + " KB" : "暂无日志")
                    .put("path", log.getAbsolutePath()));

            // ---- 语音 ----
            VoiceManager vm = VoiceManager.get(ctx);
            o.put("voice", new JSONObject()
                    .put("hotwordEnabled", vm.isHotwordEnabled())
                    .put("wakeWords", vm.getWakeWords())
                    .put("summary", (vm.isHotwordEnabled() ? "语音唤醒已开启" : "语音唤醒已关闭")
                            + " · 唤醒词: " + vm.getWakeWords()));

            // ---- 生活助理 / 日程 ----
            JSONObject lf = new JSONObject();
            readLifeDbState(lf);
            o.put("life", lf);
            try { o.put("schedules", ScheduleBridge.list(ctx)); }
            catch (Throwable t) { o.put("schedules", new JSONObject().put("items", new JSONArray())); }

            o.put("note", (GhBridge.enabled(ctx)
                    ? "GitHub 权限跟随登录令牌（含私有库与写操作，令牌不出设备）；"
                    : "未登录 GitHub，AI 无 GitHub 权限；")
                    + (shizukuAvailable()
                        ? "Shizuku 已授权；虚拟屏仍需要特权服务端 jar"
                        : "虚拟屏与特权 shell 需安装 Shizuku 并授权"));
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":" + LocalBridgeServer.q(String.valueOf(t.getMessage())) + "}";
        }
    }

    private JSONObject kernelState() {
        JSONObject kn = new JSONObject();
        try {
            String active = KernelStore.activeVersion(ctx);
            kn.put("active", active == null ? "" : active)
                    .put("activeText", active == null ? "内置内核" : active)
                    .put("storeDir", KernelStore.STORE);
            JSONArray inst = new JSONArray();
            for (KernelStore.Bundle b : KernelStore.installed(ctx)) {
                inst.put(new JSONObject().put("version", b.version).put("label", b.label)
                        .put("sizeBytes", b.sizeBytes).put("active", b.active));
            }
            kn.put("installed", inst);
            JSONArray store = new JSONArray();
            for (KernelStore.Bundle b : KernelStore.scanStore(ctx)) {
                store.put(new JSONObject().put("version", b.version).put("label", b.label)
                        .put("sizeBytes", b.sizeBytes).put("installed", b.installed)
                        .put("note", b.note));
            }
            kn.put("store", store);
        } catch (Throwable t) {
            try { kn.put("error", String.valueOf(t.getMessage())); } catch (Throwable ignored) {}
        }
        return kn;
    }

    // ==================== 动作 ====================

    @JavascriptInterface
    public String ctlAct(String id, String arg) {
        try {
            if (id == null) id = "";
            final String a = arg == null ? "" : arg;

            // ---- 解压 / 引擎 ----
            if (id.equals("extract")) {
                com.hubmobile.app.harness.UpstreamParity.setUserStopped(ctx, false);
                HarnessBridge.prepare(ctx, new HarnessBridge.Callback() {
                    @Override public void onStage(String s) {}
                    @Override public void onProgress(long e, long w, long t) {}
                    @Override public void onReady(String u) {}
                    @Override public void onError(String m) {}
                });
                return ok("正在展开引擎文件");
            }
            if (id.equals("engine.safe_mode")) {
                return ok(com.hubmobile.app.harness.UpstreamParity.enterSafeMode(ctx));
            }
            if (id.equals("engine.exit_safe_mode")) {
                return ok(com.hubmobile.app.harness.UpstreamParity.exitSafeMode(ctx));
            }
            if (id.equals("engine.diag")) {
                return ok(com.hubmobile.app.harness.UpstreamParity.diagTail(ctx, 4000));
            }
            if (id.equals("engine.start")) {
                if (engineAlive()) return ok("引擎已在运行");
                HarnessBridge.prepare(ctx, new HarnessBridge.Callback() {
                    @Override public void onStage(String s) {}
                    @Override public void onProgress(long e, long w, long t) {}
                    @Override public void onReady(String u) {}
                    @Override public void onError(String m) { bootFails++; }
                });
                return ok("正在启动引擎");
            }
            if (id.equals("engine.restart") || id.equals("engine.stop")) {
                com.hubmobile.app.harness.UpstreamParity.setUserStopped(ctx, true);
                HarnessBridge.stopEngine(ctx);
                return ok("引擎已停止，重新进入 githup AI 即会拉起");
            }
            if (id.equals("engine.keepalive")) {
                EngineService.keepAlive(ctx); return ok("前台保活已拉起");
            }
            if (id.equals("refresh")) return ok("状态已刷新");

            // ---- 救援 / 安全模式 ----
            if (id.equals("rescue.toggle")) {
                safeMode = !safeMode;
                prefs().edit().putBoolean("safe_mode", safeMode).apply();
                return ok(safeMode ? "已切到安全模式，重新进入生效" : "已退出安全模式");
            }

            // ---- 备份 / 时光机 ----
            if (id.equals("snapshot") || id.equals("backup.export")) {
                return exportSnapshot();
            }
            if (id.equals("backup.import") || id.equals("backup.snapshotImport")) {
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                pick.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { ctx.startActivity(pick); return ok("已打开文件选择器，选 .zip / .dshbackup 后由引擎侧恢复流程接手"); }
                catch (Throwable t) { return fail("无法打开文件选择器：" + t.getMessage()); }
            }

            // ---- 通知历史 / 日志 ----
            if (id.equals("notification.history")) {
                try {
                    JSONArray h = NotificationArchive.list(ctx.getFilesDir());
                    return new JSONObject().put("ok", true).put("count", h.length())
                            .put("items", h).toString();
                } catch (Throwable t) { return ok("通知历史：" + t.getMessage()); }
            }
            if (id.equals("log.view") || id.startsWith("log.")) {
                if (id.equals("log.clear")) {
                    File f = new File(ctx.getFilesDir(), "dsh-web.log");
                    if (f.exists()) { try { new FileOutputStream(f, false).close(); } catch (Throwable ignored) {} }
                    return ok("日志已清空");
                }
                File f = new File(ctx.getFilesDir(), "dsh-web.log");
                Uri uri = LogShareProvider.uriForAbs(ctx, f.getAbsolutePath());
                JSONObject o = new JSONObject().put("ok", true)
                        .put("size", f.exists() ? f.length() : 0).put("uri", uri.toString());
                if (id.equals("log.share")) {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain"); send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { ctx.startActivity(Intent.createChooser(send, "分享日志")); }
                    catch (Throwable ignored) {}
                    o.put("shared", true);
                }
                return o.toString();
            }

            // ---- 主题 / 思考深度 ----
            if (id.equals("theme.set")) {
                int m = parseInt(a, 0);
                prefs().edit().putInt("theme_mode", Math.max(0, Math.min(2, m))).apply();
                return ok("主题已保存，重新进入页面生效");
            }
            if (id.equals("reasoning.set")) {
                prefs().edit().putString("reasoning_effort", a).apply();
                return ok("思考深度已设为 " + a);
            }

            // ---- 权限 / 设置页 ----
            if (id.equals("open.url")) {
                if (a.startsWith("http") || a.startsWith("mqqapi://")) {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(a));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { ctx.startActivity(i); return ok("已打开链接"); }
                    catch (Throwable t) { return fail("无法打开：" + t.getMessage()); }
                }
                return fail("不支持的链接");
            }
            if (id.equals("perm.action") || id.startsWith("open.") || id.startsWith("perm.")) {
                return openSettings(a.isEmpty() ? id : a);
            }
            if (id.equals("workspace.change")) {
                /* 阶段 M：上游这里是唤起系统目录选择器选本地文件夹，
                   改成列 GitHub 仓库，选完由 GhBridge 展开成 App 私有目录的只读镜像。 */
                if (!GhBridge.enabled(ctx))
                    return fail("还没有 GitHub 令牌：请先在 githup 里登录或粘贴令牌");
                Intent pick = new Intent(ctx, com.hubmobile.app.GhRepoPickerActivity.class);
                pick.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { ctx.startActivity(pick); return ok("已打开 GitHub 仓库选择器"); }
                catch (Throwable t) { return fail("打不开仓库选择器：" + t.getMessage()); }
            }
            if (id.equals("update.check")) {
                return new JSONObject().put("ok", true).put("current", "1.2.20")
                        .put("versionCode", 1002020)
                        .put("hint", "在 githup 客户端内检查更新；Buwrt/2 为当前合并版分发仓").toString();
            }

            // ---- 定时任务 ----
            if (id.equals("schedule.cancel")) {
                try {
                    JSONObject req = new JSONObject(a);
                    JSONObject r = ScheduleBridge.cancel(ctx, req.optString("source", "app"), req.optString("id", ""));
                    return r.toString();
                } catch (Throwable t) { return fail("取消失败：" + t.getMessage()); }
            }

            // ---- 插件 ----
            if (id.equals("plugin.toggle")) {
                int c = a.lastIndexOf(':');
                if (c <= 0) return fail("参数格式应为 插件名:0/1");
                String p = a.substring(0, c);
                boolean on = "1".equals(a.substring(c + 1));
                prefs().edit().putBoolean("plugin_off_" + p, !on).apply();
                return ok(p + (on ? " 已启用" : " 已停用"));
            }

            // ---- 自建编译环境 ----
            if (id.equals("buildenv.resolve")) {
                beResolving = true; beErr = ""; beStage = "正在查询两个仓库…";
                new Thread(new Runnable() { @Override public void run() {
                    try {
                        List<BuildEnvInstaller.Source> r = BuildEnvInstaller.resolveSources();
                        beSources = r;
                        beStage = r == null || r.isEmpty() ? "没有可用来源" : "找到 " + r.size() + " 个来源";
                        if (r == null || r.isEmpty()) beErr = "两个仓库都没有可用的构建环境分卷";
                    } catch (Throwable t) {
                        beErr = String.valueOf(t.getMessage()); beStage = "查询失败";
                    } finally { beResolving = false; }
                }}, "be-resolve").start();
                return ok("正在查询构建环境来源");
            }
            if (id.equals("buildenv.cancel")) {
                BuildEnvInstaller.cancel(); beInstalling = false; beStage = "已取消";
                return ok("已取消安装");
            }
            if (id.equals("buildenv.install")) {
                int idx = parseInt(a, -1);
                List<BuildEnvInstaller.Source> ss = beSources;
                if (ss == null || idx < 0 || idx >= ss.size())
                    return fail("来源已失效，请先点「查询两个仓库」");
                beInstalling = true; beStage = "准备中…"; beErr = "";
                final BuildEnvInstaller.Source src = ss.get(idx);
                new Thread(new Runnable() { @Override public void run() {
                    BuildEnvInstaller.install(ctx, src, new BuildEnvInstaller.Cb() {
                        @Override public void onStage(String stage, int pct) { beStage = stage + " " + pct + "%"; }
                        @Override public void onDone(boolean ok, String msg) {
                            beInstalling = false; beStage = msg;
                            if (!ok) beErr = msg;
                        }
                    });
                }}, "be-install").start();
                return ok("开始安装构建环境，进度在控制台刷新");
            }

            // ---- 内核热切换 ----
            if (id.equals("kernel.install")) {
                final List<KernelStore.Bundle> store = KernelStore.scanStore(ctx);
                for (final KernelStore.Bundle b : store) {
                    if (b.version.equals(a)) {
                        if (b.installed) return ok("这个内核已经装过了");
                        beInstalling = true; beStage = "安装内核 " + b.version + "…";
                        new Thread(new Runnable() { @Override public void run() {
                            KernelStore.install(ctx, b, new KernelStore.Cb() {
                                @Override public void onStage(String s, int pct) { beStage = s + " " + pct + "%"; }
                                @Override public void onDone(boolean okk, String msg) {
                                    beInstalling = false; beStage = msg; if (!okk) beErr = msg;
                                }
                            });
                        }}, "kernel-install").start();
                        return ok("开始安装内核 " + b.version);
                    }
                }
                return fail("找不到内核包：" + a + "（放到 " + KernelStore.STORE + "）");
            }
            if (id.equals("kernel.switch")) {
                String r = KernelStore.activate(ctx, a);
                return ok(r == null ? "已切换到 " + a + "，下次启动引擎生效" : r);
            }
            if (id.equals("kernel.remove")) {
                return ok(KernelStore.remove(ctx, a) ? "已移除内核 " + a : "移除失败或内核正被使用");
            }
            if (id.equals("kernel.deactivate")) {
                KernelStore.deactivate(ctx);
                return ok("已回退到内置内核，下次启动引擎生效");
            }

            // ---- 语音 ----
            if (id.equals("voice.hotword.toggle")) {
                boolean on = "1".equals(a) || "true".equalsIgnoreCase(a);
                VoiceManager.get(ctx).setHotwordEnabled(on);
                return ok(on ? "已开启语音热词唤醒" : "已关闭语音热词唤醒");
            }
            if (id.equals("voice.wakewords.set")) {
                VoiceManager.get(ctx).setWakeWords(a);
                return ok("唤醒词已保存：" + a);
            }
            if (id.equals("voice.test.tone")) {
                VoiceManager.get(ctx).playPromptTone(); return ok("已播放提示音");
            }
            if (id.equals("voice.test.tts") || id.equals("voice.test.speak")) {
                VoiceManager.get(ctx).speak("DeepSeek 语音播报测试正常，随时听候您的指令。", false);
                return ok("已播报测试语音");
            }
            if (id.equals("voice.test.listen")) {
                VoiceManager.get(ctx).triggerVoiceInteraction();
                return ok("已触发语音识别");
            }
            if (id.equals("tts.speak")) return VoiceBridge.get(ctx).tts("speak", a, false);
            if (id.equals("tts.stop")) return VoiceBridge.get(ctx).tts("stop", null, false);
            if (id.startsWith("voice.")) return VoiceBridge.get(ctx).voice(id.substring(6));

            // ---- 生活助理（照搬上游 runLifeToolCommand：起 node 跑 life.js）----
            if (id.equals("life.daemon.start")) { runLifeTool("daemon", "start"); return ok("正在启动生活助理守护进程"); }
            if (id.equals("life.daemon.stop")) { runLifeTool("daemon", "stop"); return ok("已停止守护进程"); }
            if (id.equals("life.daemon.restart")) { runLifeTool("daemon", "restart"); return ok("正在重启守护进程"); }
            if (id.equals("life.pkg.pick")) { runLifeTool("pkg", "pick", a); return ok("已标记包裹为已取件"); }
            if (id.equals("life.code.copy")) {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("验证码", a));
                return ok("验证码 [" + a + "] 已复制");
            }
            if (id.equals("life.scan")) { runLifeTool("scan", "50"); return ok("正在回溯扫描最近 50 条通知"); }
            if (id.equals("life.task.run")) { runLifeTool("cron", "run", a); return ok("已触发任务：" + a); }

            // ---- 桌宠 / 流体云 / 虚拟屏（前端控制台的 show / fluid / panel / vscreen）----
            // 前端桌宠页传的是 query-string 形式（action=show / action=fluid&on=1），
            // 控制台页传的是纯值（"1" / ""）。两种都要吃下，不能按字面比。
            if (id.equals("show") || id.equals("overlay.show") || id.equals("pet.show")) {
                boolean hide = "hide".equals(argParam(a, "action", "")) || "0".equals(a);
                OverlayService.dispatch(ctx, hide ? "hide" : "show", null);
                return ok(hide ? "桌宠已收起" : "桌宠已召回");
            }
            if (id.equals("overlay.hide") || id.equals("pet.hide")) {
                OverlayService.dispatch(ctx, "hide", null); return ok("桌宠已收起");
            }
            if (id.equals("panel") || id.equals("overlay.panel")) {
                boolean show = !"0".equals(argParam(a, "show", "1"));
                OverlayService.togglePanelFromBridge(show, true);
                return ok("已打开桌宠面板");
            }
            if (id.equals("fluid")) {
                String onRaw = argParam(a, "on", a.isEmpty() ? "1" : a);
                boolean want = "1".equals(onRaw) || "true".equalsIgnoreCase(onRaw);
                boolean on = OverlayService.setFluidFromBridge(want);
                return new JSONObject().put("ok", true).put("on", on).toString();
            }
            if (id.equals("vscreen")) {
                String onRaw = argParam(a, "on", a.isEmpty() ? "1" : a);
                boolean want = "1".equals(onRaw) || "true".equalsIgnoreCase(onRaw);
                if (want && !VsreenBridgeService.isVscreenRunning())
                    VsreenBridgeService.toggleFromUi();
                else if (!want)
                    VsreenBridgeService.destroyVscreenFromWhale();
                return new JSONObject().put("ok", true)
                        .put("on", VsreenBridgeService.isVscreenRunning())
                        .put("status", new JSONObject(VsreenBridgeService.statusJson(ctx)))
                        .put("msg", VsreenBridgeService.isVscreenRunning()
                                ? "虚拟屏已开启" : "虚拟屏不可用：" + VsreenBridgeService.lastReason()).toString();
            }

            // ---- NFC ----
            if (id.equals("nfc.arm")) return NfcStore.arm(ctx, true).toString();
            if (id.equals("nfc.disarm")) return NfcStore.arm(ctx, false).toString();
            if (id.equals("nfc.status")) return NfcStore.statusJson(ctx).toString();

            return fail("「" + id + "」需要 Shizuku 特权服务端，本版未接入");
        } catch (Throwable t) {
            return fail(String.valueOf(t.getMessage()));
        }
    }

    // ==================== 辅助 ====================

    private String openSettings(String key) {
        Intent i;
        if (key.contains("overlay")) {
            i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.getPackageName()));
        } else if (key.contains("a11y") || key.contains("accessibility")) {
            i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        } else if (key.contains("usage")) {
            i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        } else if (key.contains("write")) {
            i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + ctx.getPackageName()));
        } else if (key.contains("battery")) {
            i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        } else if (key.contains("notification")) {
            i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.getPackageName());
        } else {
            i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + ctx.getPackageName()));
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(i); return ok("已打开系统设置"); }
        catch (Throwable t) { return fail("无法打开设置：" + t.getMessage()); }
    }

    /** 真打包 dshhome 全站资产到 /sdcard/DeepSeekHarness/backups/（时光机快照）。 */
    private String exportSnapshot() {
        try {
            File home = new File(ctx.getFilesDir(), "payload/dshhome");
            if (!home.isDirectory()) return fail("引擎资产目录还没展开");
            File dir = new File(Environment.getExternalStorageDirectory(), "DeepSeekHarness/backups");
            if (!dir.exists() && !dir.mkdirs()) return fail("建不了备份目录 " + dir);
            String name = "githup-snapshot-" + System.currentTimeMillis() + ".dshbackup";
            final File out = new File(dir, name);
            final int[] count = {0};
            zipDir(home, out, count);
            return new JSONObject().put("ok", true)
                    .put("path", out.getAbsolutePath())
                    .put("size", out.length()).put("entries", count[0])
                    .put("msg", "时光机快照已生成：" + name).toString();
        } catch (Throwable t) {
            return fail("快照失败：" + t.getMessage());
        }
    }

    private static void zipDir(File root, File out, int[] count) throws Exception {
        FileOutputStream fos = new FileOutputStream(out);
        ZipOutputStream z = new ZipOutputStream(fos);
        byte[] buf = new byte[8192];
        ArrayList<File> all = new ArrayList<>();
        collect(root, all);
        String base = root.getAbsolutePath();
        for (File f : all) {
            String rel = f.getAbsolutePath().substring(base.length() + 1).replace('\\', '/');
            z.putNextEntry(new ZipEntry(rel));
            if (f.isFile()) {
                InputStream in = new FileInputStream(f);
                int n;
                while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
                in.close();
            }
            z.closeEntry(); count[0]++;
        }
        z.close(); fos.close();
    }

    private static void collect(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) { out.add(f); if (f.isDirectory()) collect(f, out); }
    }

    /** 照搬上游 runLifeToolCommand：node + payload/dshhome/skills/dsh-life/scripts/life.js */
    private void runLifeTool(final String... args) {
        new Thread(new Runnable() { @Override public void run() {
            try {
                File root = new File(ctx.getFilesDir(), "payload");
                File node = new File(root, "runtime/bin/node");
                File tool = new File(root, "dshhome/skills/dsh-life/scripts/life.js");
                if (!node.isFile() || !tool.isFile()) return;
                List<String> cmd = new ArrayList<>();
                cmd.add(node.getAbsolutePath()); cmd.add(tool.getAbsolutePath());
                for (String a : args) cmd.add(a);
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(root); pb.redirectErrorStream(true);
                Process p = pb.start(); p.waitFor();
            } catch (Throwable ignored) {}
        }}, "life-tool").start();
    }

    /** 照搬上游 readLifeDbState：pid 存活 + 只读查 life.db。 */
    private void readLifeDbState(JSONObject lf) {
        try {
            File pidFile = new File(ctx.getFilesDir(), "tmp/life-daemon.pid");
            boolean daemon = false;
            if (pidFile.isFile()) {
                try {
                    int pid = Integer.parseInt(readText(pidFile).trim());
                    daemon = pid > 0 && new File("/proc/" + pid).exists();
                } catch (Throwable ignored) {}
            }
            lf.put("daemonRunning", daemon);
            File dbFile = new File(Environment.getExternalStorageDirectory(), "DeepSeekHarness/life.db");
            if (!dbFile.isFile()) {
                lf.put("ready", false)
                        .put("summary", (daemon ? "🟢 守护中" : "⚪ 未启动") + " · 数据库就绪中");
                return;
            }
            lf.put("ready", true);
            SQLiteDatabase db = null;
            try {
                db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null,
                        SQLiteDatabase.OPEN_READONLY);
                Cursor c = db.rawQuery("SELECT count(*) FROM packages WHERE status='pending'", null);
                int pkgs = 0;
                if (c != null) { if (c.moveToFirst()) pkgs = c.getInt(0); c.close(); }
                lf.put("pendingPackages", pkgs)
                        .put("summary", (daemon ? "🟢 守护中" : "⚪ 未启动") + " · 待取包裹 " + pkgs);
            } finally { if (db != null) db.close(); }
        } catch (Throwable t) {
            try { lf.put("ready", false).put("summary", "生活助理未初始化"); } catch (Throwable ignored) {}
        }
    }

    private static String readText(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[4096]; int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close(); return new String(o.toByteArray(), "UTF-8");
    }

    private boolean engineAlive() {
        try { return HarnessBridge.isRunning(); } catch (Throwable t) { return false; }
    }

    private boolean canDraw() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx);
    }

    /** 反射探测 Shizuku；本版未打 AAR，未安装时恒为 false，不假装可用。 */
    private static boolean shizukuAvailable() {
        try {
            Class<?> c = Class.forName("rikka.shizuku.Shizuku");
            Object ping = c.getMethod("pingBinder").invoke(null);
            if (!Boolean.TRUE.equals(ping)) return false;
            Object perm = c.getMethod("checkSelfPermission").invoke(null);
            return perm instanceof Integer && ((Integer) perm) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) { return false; }
    }

    /** 解析 "action=fluid&on=1" 这类 query-string 参数；没有该 key 时回退 def。 */
    private static String argParam(String arg, String key, String def) {
        if (arg == null) return def;
        for (String kv : arg.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && key.equals(kv.substring(0, i))) return kv.substring(i + 1);
        }
        return def;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; }
    }

    private static String ok(String msg) {
        return "{\"ok\":true,\"msg\":" + LocalBridgeServer.q(msg) + "}";
    }

    private static String fail(String msg) {
        return "{\"ok\":false,\"msg\":" + LocalBridgeServer.q(msg) + "}";
    }

    // ---- 兼容上游注入脚本 ----
    @JavascriptInterface public void onBg(String state) {}
    @JavascriptInterface public String appVersion() { return "1.2.20"; }
    @JavascriptInterface public String packageName() { return ctx.getPackageName(); }

    // ---- 供 3081 HTTP 桥读取的静态状态 ----
    public static String buildEnvJson(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("installed", BuildEnvInstaller.isInstalled(c))
                    .put("statusText", BuildEnvInstaller.statusText(c))
                    .put("installing", beInstalling).put("stage", beStage)
                    .put("resolving", beResolving).put("err", beErr);
            return o.toString();
        } catch (Throwable t) { return "{\"ok\":false}"; }
    }
}
