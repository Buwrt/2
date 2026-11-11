package com.hubmobile.app.harness;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 阶段 AB：对照上游 deepseek-harness-android 的 MainActivity 补齐的「壳级」能力。
 *
 * 上游 MainActivity 11179 行，本包用 AiPanel + HarnessActivity + HarnessBridge
 * 做同屏精简替代时漏掉了一批**与 UI 无关、但真机很要命**的机制：
 *
 *  ① onConsoleMessage 只转 logcat，没有落盘诊断缓冲（上游 diagLog / DSHJS）
 *  ② 引擎 profile 的 YAML 被插件写坏 → 启动失败死结，上游有「安全模式」旁置用户层
 *  ③ node 被系统杀掉后没有看门狗自动重拉（上游 startWatchdog / reloadWhenEngineReady）
 *  ④ 电池优化白名单只声明了权限，没有运行时检查与引导
 *  ⑤ 启动失败计数 / 提示阈值
 *
 * 本类把这些做成与 Activity 解耦的静态工具，供 HarnessBridge 与 AiPanel 调用。
 * 上游那套「开机动画 bootFilm / 原生冷启动控制台」按用户要求不搬
 *（品牌四句对话 + 单图标同屏），见 MERGE_PLAN 第三十二章。
 */
public final class UpstreamParity {

    private static final String TAG = "githup-parity";
    private static final String PREFS = "dsh_prefs";

    private static final String KEY_BOOT_FAILS = "engine_boot_failures";
    private static final String KEY_SAFE_MODE = "safe_mode_active";
    public static final int BOOT_FAIL_HINT_AT = 2;

    private static final int DIAG_MAX = 256 * 1024;
    private static final long RESPAWN_GUARD_MS = 20_000L;
    private static volatile boolean sWatchdogStarted = false;
    private static volatile long sLastRespawnAt = 0L;

    /** HarnessBridge 每次成功起引擎后递增；AiPanel 据此判断「引擎换了，该重载」。 */
    private static volatile long sReviveEpoch = 0L;

    private UpstreamParity() {}

    // ==================== ① 落盘诊断日志（上游 diagLog / DSHJS） ====================

    /** 追加一行带时间戳的诊断；超过 256KiB 保留尾部一半。 */
    public static void diagLog(Context c, String tag, String msg) {
        try {
            File f = new File(c.getFilesDir(), "diagnostics.log");
            String stamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            String line = stamp + " " + tag + " " + String.valueOf(msg) + "\n";
            byte[] add = line.getBytes(StandardCharsets.UTF_8);
            long old = f.exists() ? f.length() : 0;
            byte[] keep = new byte[0];
            if (old + add.length > DIAG_MAX) {
                long start = Math.max(0, old - DIAG_MAX / 2);
                keep = new byte[(int) (old - start)];
                FileInputStream in = new FileInputStream(f);
                long skipped = 0;
                while (skipped < start) {
                    long n = in.skip(start - skipped);
                    if (n <= 0) break;
                    skipped += n;
                }
                int off = 0, n;
                while (off < keep.length && (n = in.read(keep, off, keep.length - off)) > 0) off += n;
                in.close();
            } else if (old > 0) {
                keep = new byte[(int) old];
                FileInputStream in = new FileInputStream(f);
                int off = 0, n;
                while (off < keep.length && (n = in.read(keep, off, keep.length - off)) > 0) off += n;
                in.close();
            }
            FileOutputStream out = new FileOutputStream(f, false);
            out.write(keep);
            out.write(add);
            out.close();
        } catch (Throwable ignored) {}
    }

    public static String diagPath(Context c) {
        return new File(c.getFilesDir(), "diagnostics.log").getAbsolutePath();
    }

    /** 供兜底页 / 控制台读尾部，避免把 256KiB 全塞进 UI。 */
    public static String diagTail(Context c, int max) {
        try {
            File f = new File(c.getFilesDir(), "diagnostics.log");
            if (!f.exists()) return "(暂无诊断日志)";
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            in.close();
            String s = new String(b, 0, off, StandardCharsets.UTF_8);
            return s.length() > max ? "…" + s.substring(s.length() - max) : s;
        } catch (Throwable t) { return "(读取失败)"; }
    }

    // ==================== ② 启动失败计数 + 安全模式 ====================

    public static int bootFailures(Context c) {
        try { return prefs(c).getInt(KEY_BOOT_FAILS, 0); } catch (Throwable t) { return 0; }
    }

    public static void noteBootFailure(Context c, String why) {
        int n = bootFailures(c) + 1;
        try { prefs(c).edit().putInt(KEY_BOOT_FAILS, n).apply(); } catch (Throwable ignored) {}
        diagLog(c, "BOOT", "失败 #" + n + "：" + String.valueOf(why));
    }

    public static void clearBootFailures(Context c) {
        try { prefs(c).edit().putInt(KEY_BOOT_FAILS, 0).apply(); } catch (Throwable ignored) {}
    }

    public static boolean safeModeActive(Context c) {
        try { return prefs(c).getBoolean(KEY_SAFE_MODE, false); } catch (Throwable t) { return false; }
    }

    /**
     * 上游 enterSafeMode 的等价实现：把**用户层 patch**旁置到一个带时间戳的目录，
     * 让引擎下次用 zip 原版（出厂 profile）启动，但**不动会话 / 凭据 / 设置**。
     *
     * 本包的用户层写入点有两个：
     *   dshhome/cordis.patch.yml               （GithupContextPlugin / EnginePorts）
     *   dshhome/profiles/web/cordis.patch.yml  （插件 insert 块）
     * 另外 AccountDefaultRoute 会写 profile 默认路由，一并旁置。
     *
     * 只旁置「本包自己生成」的文件，绝不删用户的会话数据。
     */
    public static String enterSafeMode(Context c) {
        try {
            File home = new File(c.getFilesDir(), "payload/dshhome");
            if (!home.isDirectory()) return "引擎文件尚未展开，无需进入安全模式";
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File stash = new File(home, "web.userlayer-" + stamp);
            if (!stash.mkdirs()) return "无法创建旁置目录";
            String[] rels = {
                    "cordis.patch.yml",
                    "profiles/web/cordis.patch.yml",
                    "profiles/web/profile.yml",
                    "AGENTS.md"
            };
            int moved = 0;
            for (String r : rels) {
                File f = new File(home, r);
                if (!f.isFile()) continue;
                File dst = new File(stash, r.replace('/', '_'));
                if (f.renameTo(dst)) moved++;
            }
            prefs(c).edit().putBoolean(KEY_SAFE_MODE, true)
                    .putString("safe_mode_stash", stash.getAbsolutePath()).apply();
            diagLog(c, "SAFE", "进入安全模式，旁置 " + moved + " 个用户层文件 → " + stash.getName());
            return "已旁置 " + moved + " 个配置文件（会话与凭据保留）。\n"
                    + "请完全退出 App 重进；引擎会用出厂配置启动。";
        } catch (Throwable t) {
            return "进入安全模式失败：" + t.getMessage();
        }
    }

    /** 把旁置的文件放回原位（引擎已能正常启动时调用）。 */
    public static String exitSafeMode(Context c) {
        try {
            File home = new File(c.getFilesDir(), "payload/dshhome");
            String path = prefs(c).getString("safe_mode_stash", "");
            File stash = path.isEmpty() ? null : new File(path);
            int back = 0;
            if (stash != null && stash.isDirectory()) {
                String[] rels = {
                        "cordis.patch.yml",
                        "profiles/web/cordis.patch.yml",
                        "profiles/web/profile.yml",
                        "AGENTS.md"
                };
                for (String r : rels) {
                    File f = new File(stash, r.replace('/', '_'));
                    if (!f.isFile()) continue;
                    File dst = new File(home, r);
                    if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
                    if (f.renameTo(dst)) back++;
                }
            }
            prefs(c).edit().putBoolean(KEY_SAFE_MODE, false).remove("safe_mode_stash").apply();
            clearBootFailures(c);
            diagLog(c, "SAFE", "退出安全模式，恢复 " + back + " 个配置");
            return "已恢复 " + back + " 个配置文件。完全退出 App 重进即可。";
        } catch (Throwable t) {
            return "退出安全模式失败：" + t.getMessage();
        }
    }

    // ==================== ③ 引擎看门狗（上游 startWatchdog） ====================

    /**
     * node 进程死亡 + 端口不通时自动重拉，避免「挂后台回来 AI 一直转圈/白屏」。
     * 与上游一致：20s 防风暴、用户主动 stop 后不自动拉、等真就绪再广播重载。
     */
    public static void startWatchdog(final Context app) {
        if (sWatchdogStarted) return;
        sWatchdogStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                    try {
                        if (userStopped(app)) continue;
                        if (com.hubmobile.app.HarnessBridge.isRunning()) continue;
                        // 引擎没在跑：只有在「本包确实启动过一次」之后才自愈，
                        // 避免冷启动 700ms 延迟期间被误判成「死了」。
                        if (!prefs(app).getBoolean("engine_ever_started", false)) continue;
                        long now = System.currentTimeMillis();
                        if (now - sLastRespawnAt < RESPAWN_GUARD_MS) continue;
                        sLastRespawnAt = now;
                        diagLog(app, "WATCHDOG", "引擎不可用，自动重拉");
                        boolean ok = com.hubmobile.app.HarnessBridge.ensureEngine(app);
                        diagLog(app, "WATCHDOG", ok ? "重拉成功" : "重拉失败");
                        if (ok) {
                            sReviveEpoch++;
                            clearBootFailures(app);
                        } else {
                            noteBootFailure(app, "看门狗重拉失败");
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }, "githup-watchdog");
        t.setDaemon(true);
        t.start();
    }

    public static long reviveEpoch() { return sReviveEpoch; }

    public static void noteEngineStarted(Context c) {
        try { prefs(c).edit().putBoolean("engine_ever_started", true).apply(); } catch (Throwable ignored) {}
    }

    public static void setUserStopped(Context c, boolean v) {
        try { prefs(c).edit().putBoolean("engine_stopped_by_user", v).apply(); } catch (Throwable ignored) {}
    }

    public static boolean userStopped(Context c) {
        try { return prefs(c).getBoolean("engine_stopped_by_user", false); } catch (Throwable t) { return false; }
    }

    // ==================== ④ 电池优化白名单（上游 checkBatteryOptimization） ====================

    public static boolean batteryOptimized(Context c) {
        try {
            if (Build.VERSION.SDK_INT < 23) return false;
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && !pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable t) { return false; }
    }

    /** 打开系统「忽略电池优化」请求；失败退回电池设置页。 */
    public static void requestIgnoreBattery(Context c) {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable t) {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Throwable ignored) {}
        }
    }

    /** 冷启动后一次性温和提示（每 24h 最多一次），不弹阻塞对话框。 */
    public static boolean shouldHintBattery(Context c) {
        if (!batteryOptimized(c)) return false;
        long last = prefs(c).getLong("battery_hint_at", 0L);
        return System.currentTimeMillis() - last > 24L * 3600_000L;
    }

    public static void markBatteryHinted(Context c) {
        try { prefs(c).edit().putLong("battery_hint_at", System.currentTimeMillis()).apply(); }
        catch (Throwable ignored) {}
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
