package com.hubmobile.app.harness;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import com.hubmobile.app.HarnessActivity;
import com.hubmobile.app.R;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Locale;

/**
 * 127.0.0.1:3081 —— App 本地桥（第二层能力的服务端）。
 *
 * 上游 Harness 的 Web UI 把「原生动作」都打到这个端口：
 *   /overlay   桌宠悬浮窗（设置页里那三个开关 + 召回/面板按钮）
 *   /open      用系统应用打开文件 / 定位目录 / 分享
 *   /status    壳状态（控制台页）
 *   /notify    AI 发通知
 *   /clipboard 剪贴板
 *   /setting   跳系统设置（悬浮窗 / 无障碍 / 电池）
 *   /shell     非特权 shell（无 Shizuku，明确标注）
 *   /changelog 更新日志
 *
 * 网页从 127.0.0.1:<enginePort> 跨端口 fetch 本桥，必须回 CORS 头；
 * Origin/Referer 被严格限制在回环 3080~3091，别的页面连不上。
 */
public final class LocalBridgeServer {

    private static final String TAG = "gh-bridge";
    public static final int PORT = 3081;
    private static final int ENGINE_MIN = 3080, ENGINE_MAX = 3092;

    private static volatile LocalBridgeServer sInstance;
    private final Context app;
    private ServerSocket ss;
    private Thread thread;
    private volatile boolean running = false;

    private LocalBridgeServer(Context c) { app = c.getApplicationContext(); }

    public static synchronized void start(Context c) {
        if (sInstance != null && sInstance.running) return;
        if (sInstance != null) sInstance.stop();
        LocalBridgeServer s = new LocalBridgeServer(c);
        sInstance = s;
        s.run();
    }

    public static boolean isRunning() {
        return sInstance != null && sInstance.running;
    }

    private void run() {
        thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress("127.0.0.1", PORT));
                    running = true;
                    Log.i(TAG, "local bridge listening on " + PORT);
                    while (running && !Thread.currentThread().isInterrupted()) {
                        try {
                            final Socket s = ss.accept();
                            new Thread(new Runnable() {
                                @Override public void run() { handle(s); }
                            }, "gh-bridge-conn").start();
                        } catch (Throwable t) {
                            if (!running) break;
                            try { Thread.sleep(100); } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "local bridge stopped", t);
                } finally {
                    running = false;
                    try { if (ss != null) ss.close(); } catch (Throwable ignored) {}
                }
            }
        }, "gh-bridge");
        thread.setDaemon(true);
        thread.start();
    }

    private void stop() {
        running = false;
        try { if (ss != null) ss.close(); } catch (Throwable ignored) {}
    }

    /** 回环引擎源（端口可顺延）白名单。 */
    private boolean originOk(String v) {
        if (v == null || v.isEmpty()) return false;
        try {
            Uri u = Uri.parse(v);
            String host = u.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.US);
            if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) return false;
            if (!"http".equalsIgnoreCase(u.getScheme())) return false;
            int p = u.getPort();
            return p >= ENGINE_MIN && p < ENGINE_MAX;
        } catch (Throwable t) { return false; }
    }

    private String header(String raw, String key) {
        String low = raw.toLowerCase(Locale.US);
        int i = low.indexOf("\r\n" + key + ":");
        if (i < 0) return null;
        int s = i + key.length() + 4;
        int e = raw.indexOf('\r', s);
        if (e < 0) e = raw.length();
        return raw.substring(s, e).trim();
    }

    private static String readHeader(InputStream in) throws Exception {
        StringBuilder b = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            b.append((char) c);
            if (b.length() > 16384) throw new Exception("header too large");
            if (b.length() >= 4 && b.substring(b.length() - 4).equals("\r\n\r\n")) return b.toString();
        }
        throw new Exception("incomplete header");
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(8000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String h = readHeader(in);

            String[] first = h.split("\r\n", 2)[0].split(" ");
            String method = first.length > 0 ? first[0] : "";
            String full = first.length > 1 ? first[1] : "/";
            String origin = header(h, "origin");
            String referer = header(h, "referer");
            String host = header(h, "host");

            // Host 只认本桥
            if (host == null || !(host.startsWith("127.0.0.1:" + PORT)
                    || host.startsWith("localhost:" + PORT) || host.equals("127.0.0.1"))) {
                write(out, 403, cors(origin), "{\"ok\":false,\"error\":\"bad host\"}");
                s.close(); return;
            }
            // 浏览器跨站必须带回环引擎源
            if (origin != null && !originOk(origin)) {
                write(out, 403, cors(origin), "{\"ok\":false,\"error\":\"origin rejected\"}");
                s.close(); return;
            }
            if (referer != null && !originOk(referer)) {
                write(out, 403, cors(origin), "{\"ok\":false,\"error\":\"referer rejected\"}");
                s.close(); return;
            }
            // CORS 预检
            if ("OPTIONS".equals(method)) {
                write(out, 204, cors(origin) + "Access-Control-Max-Age: 600\r\n", "");
                s.close(); return;
            }

            int q = full.indexOf('?');
            String path = q >= 0 ? full.substring(0, q) : full;
            String query = q >= 0 ? full.substring(q + 1) : "";

            int len = 0;
            String cl = header(h, "content-length");
            if (cl != null) try { len = Integer.parseInt(cl.trim()); } catch (Throwable ignored) {}
            if (len < 0 || len > 65536) len = 0;
            StringBuilder body = new StringBuilder();
            if (len > 0) {
                byte[] buf = new byte[len];
                int off = 0, n;
                while (off < len && (n = in.read(buf, off, len - off)) > 0) off += n;
                body.append(new String(buf, 0, off, "UTF-8"));
            }

            // 免密路由：网页设置页直连的三个 /overlay 开关、只读状态、文件打开
            boolean open = path.startsWith("/overlay") || path.startsWith("/status")
                    || path.startsWith("/changelog") || path.startsWith("/open");
            if (!open && !LocalBridgeAuth.authorized(h, body.toString(), app)) {
                write(out, 403, cors(origin), "{\"ok\":false,\"error\":\"unauthorized\"}");
                s.close(); return;
            }

            String resp = dispatch(path, query, body.toString());
            write(out, 200, cors(origin), resp);
            s.close();
        } catch (Throwable t) {
            Log.w(TAG, "conn error", t);
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    private String cors(String origin) {
        String allow = originOk(origin) ? origin : "http://127.0.0.1:3080";
        return "Access-Control-Allow-Origin: " + allow + "\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: content-type, x-dsh-token\r\n"
                + "Access-Control-Allow-Credentials: true\r\n"
                + "Vary: Origin\r\n";
    }

    private void write(OutputStream out, int code, String extra, String body) throws Exception {
        byte[] b = body.getBytes("UTF-8");
        String reason = code == 200 ? "OK" : code == 204 ? "No Content" : "Rejected";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n" + extra
                + "Cache-Control: no-store\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(b);
        out.flush();
    }

    private String dispatch(String path, String query, String body) {
        try {
            if (path.startsWith("/overlay")) return overlay(query);
            if (path.startsWith("/open-file")) return open(body);   // dsh-native-command 用这个路径
            if (path.startsWith("/open")) return open(body);
            if (path.startsWith("/status")) return status();
            if (path.startsWith("/changelog")) return changelog();
            if (path.startsWith("/notify")) return notify(body);
            if (path.startsWith("/clipboard")) return clipboard(body);
            if (path.startsWith("/setting")) return setting(body);
            if (path.startsWith("/shell")) return shell(body);
            if (path.startsWith("/usage")) return usage(query, body);
            if (path.startsWith("/voice")) return voice(body);
            if (path.startsWith("/tts")) return tts(body);
            if (path.startsWith("/schedule")) return schedule(path, query, body);
            if (path.startsWith("/lan")) return lan(query, body);
            if (path.startsWith("/nfc-")) return nfc(path, query, body);
            if (path.startsWith("/notifications")) return notifications(path, query);
            if (path.startsWith("/log")) return logs(query);
            if (path.startsWith("/backup")) return backup(query, body);
            if (path.startsWith("/update")) return update();
            if (path.startsWith("/kernel")) return unsupported("内核热切换");
            if (path.startsWith("/buildenv")) return unsupported("手机内自建编译环境");
            return "{\"ok\":false,\"error\":\"unknown route: " + path + "\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":" + q(String.valueOf(t.getMessage())) + "}";
        }
    }

    private String overlay(String query) {
        String action = param(query, "action", "status");
        String on = param(query, "on", "");
        if ("fluid".equals(action)) {
            OverlayService s = OverlayService.get();
            boolean v = "1".equals(on) || "true".equals(on);
            if (s != null) s.setFluid(v);
            app.getSharedPreferences("dsh_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("fluid", v).apply();
        } else if ("vscreen".equals(action)) {
            OverlayService s = OverlayService.get();
            boolean v = "1".equals(on) || "true".equals(on);
            if (s != null) s.setVscreen(v);
            app.getSharedPreferences("dsh_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("vscreen", v).apply();
        } else if ("show".equals(action)) {
            OverlayService.dispatch(app, "show", null);
        } else if ("hide".equals(action)) {
            OverlayService.dispatch(app, "hide", null);
        } else if ("panel".equals(action)) {
            OverlayService.dispatch(app, "panel", null);
        }
        String st = OverlayService.statusJson(app);
        if ("show".equals(action) && !st.contains("\"running\":true")) {
            st = st.replace("\"running\":false", "\"running\":true")
                   .replace("\"visible\":false", "\"visible\":true");
        }
        return st;
    }

    private String status() {
        boolean a11y = HarnessAccessibilityService.getInstance() != null;
        return "{\"ok\":true,\"app\":\"githup\",\"version\":\"1.2.20\","
                + "\"versionCode\":1002020,\"enginePort\":3080,\"bridgePort\":3081,"
                + "\"a11yPort\":3181,\"overlay\":" + OverlayService.statusJson(app)
                + ",\"a11yConnected\":" + a11y
                + ",\"shizuku\":false,\"vscreenPrivilege\":false,"
                + "\"root\":" + RootShell.probe().ok + ","
                + "\"note\":\"虚拟屏/特权进程需 Shizuku；root 可用时 /shell 自动提权\"}";
    }

    private String changelog() {
        return "{\"ok\":true,\"version\":\"1.2.20\",\"notes\":"
                + q("githup 合并版：底座 + DeepSeek Harness 引擎 + 本地桥全路由 + 悬浮桌宠 + 无障碍 + 语音/定时任务")
                + "}";
    }

    private String open(String body) {
        try {
            String p = json(body, "path");
            String act = json(body, "action");
            if (p == null || p.isEmpty()) return "{\"ok\":false,\"error\":\"path required\"}";
            java.io.File f = new java.io.File(p);
            if (!f.exists()) return "{\"ok\":false,\"error\":\"file not found: " + p + "\"}";
            Uri uri = LogShareProvider.uriForAbs(app, f.getAbsolutePath());
            String mime = LogShareProvider.mimeOfName(f.getName());
            if ("share".equals(act)) {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(mime == null ? "*/*" : mime);
                i.putExtra(Intent.EXTRA_STREAM, uri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                app.startActivity(Intent.createChooser(i, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if ("reveal".equals(act)) {
                Intent d = new Intent(Intent.ACTION_VIEW);
                d.setData(Uri.parse("file://" + f.getParent()));
                d.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { app.startActivity(d); }
                catch (Throwable ignored) { return "{\"ok\":false,\"error\":\"no file manager\"}"; }
            } else {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, mime == null ? "*/*" : mime);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                try { app.startActivity(i); }
                catch (Throwable t) { return "{\"ok\":false,\"error\":\"no app can open this file\"}"; }
            }
            return "{\"ok\":true}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":" + q(String.valueOf(t.getMessage())) + "}";
        }
    }

    private String notify(String body) {
        try {
            String title = json(body, "title");
            String text = json(body, "text");
            if (title == null) title = "githup AI";
            if (text == null) text = "";
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return "{\"ok\":false,\"error\":\"notification service unavailable\"}";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel("githup_ai_msg", "githup AI 消息",
                        NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(app, HarnessActivity.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(app, (int) System.currentTimeMillis(), open, flags);
            android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new android.app.Notification.Builder(app, "githup_ai_msg")
                    : new android.app.Notification.Builder(app);
            nm.notify((int) (System.currentTimeMillis() % 100000),
                    b.setContentTitle(title).setContentText(text)
                            .setSmallIcon(R.mipmap.ic_launcher)
                            .setContentIntent(pi).setAutoCancel(true).build());
            return "{\"ok\":true}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":" + q(String.valueOf(t.getMessage())) + "}";
        }
    }

    /** 插件 android_clipboard：{action:read|write, content}，返回 {ok, content}。 */
    private String clipboard(String body) {
        try {
            ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return "{\"ok\":false,\"error\":\"clipboard unavailable\"}";
            String action = json(body, "action");
            String content = json(body, "content");
            if ("write".equals(action)) {
                cm.setPrimaryClip(ClipData.newPlainText("githup", content == null ? "" : content));
                return "{\"ok\":true}";
            }
            CharSequence c = cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0
                    ? cm.getPrimaryClip().getItemAt(0).getText() : "";
            return "{\"ok\":true,\"content\":" + q(String.valueOf(c)) + "}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":" + q(String.valueOf(t.getMessage())) + "}";
        }
    }

    /** 插件 android_setting_app：{key,value} 写 Settings.System；没权限就引导授权。 */
    private String setting(String body) {
        try {
            String key = json(body, "key");
            String value = json(body, "value");
            // 兼容网页设置页的 ?which=overlay|a11y|battery
            if (key == null || key.isEmpty()) return jumpSetting(param("", "which", ""));
            if (key.startsWith("open.") || key.equals("overlay") || key.equals("a11y")
                    || key.equals("battery")) return jumpSetting(key);
            if (!Settings.System.canWrite(app)) {
                Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + app.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { app.startActivity(i); } catch (Throwable ignored) {}
                return "{\"ok\":false,\"error\":\"需要先授予「修改系统设置」权限，已打开设置页\"}";
            }
            if (value == null) value = "";
            boolean ok = Settings.System.putString(app.getContentResolver(), key, value);
            // 音量类用 AudioManager 同步一下，避免只写了数据库不生效
            if (key.startsWith("volume_")) {
                try {
                    android.media.AudioManager am = (android.media.AudioManager)
                            app.getSystemService(Context.AUDIO_SERVICE);
                    int v = Integer.parseInt(value.trim());
                    int stream = "volume_ring".equals(key) ? android.media.AudioManager.STREAM_RING
                            : "volume_alarm".equals(key) ? android.media.AudioManager.STREAM_ALARM
                            : "volume_notification".equals(key) ? android.media.AudioManager.STREAM_NOTIFICATION
                            : android.media.AudioManager.STREAM_MUSIC;
                    if (am != null) am.setStreamVolume(stream, Math.max(0, Math.min(v, 15)), 0);
                } catch (Throwable ignored) {}
            }
            return "{\"ok\":" + ok + ",\"key\":" + q(key) + ",\"value\":" + q(value) + "}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":" + q(String.valueOf(t.getMessage())) + "}";
        }
    }

    private String jumpSetting(String which) {
        try {
            Intent i;
            if ("overlay".equals(which) || "open.overlay".equals(which)) {
                i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + app.getPackageName()));
            } else if ("a11y".equals(which) || "open.a11y".equals(which)) {
                i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            } else if ("battery".equals(which)) {
                i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            } else if ("notification".equals(which)) {
                i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, app.getPackageName());
            } else {
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + app.getPackageName()));
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
            return "{\"ok\":true}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":" + q(String.valueOf(t.getMessage())) + "}";
        }
    }

    /** /shell：App uid 执行；探测到 su 则自动提权并如实标注 privileged。 */
    private String shell(String body) {
        String cmd = json(body, "command");
        if (cmd == null || cmd.isEmpty()) return "{\"ok\":false,\"error\":\"command required\"}";
        if (cmd.length() > 65536) return "{\"ok\":false,\"error\":\"command too long\"}";
        long timeout = 30000;
        String ts = json(body, "timeout_ms");
        if (ts != null) try { timeout = Math.max(1000, Math.min(Long.parseLong(ts), 120000)); }
        catch (Throwable ignored) {}
        RootShell.Probe probe = RootShell.probe();
        final String[] runner = probe.ok
                ? new String[]{probe.suPath, "-c", cmd}
                : new String[]{"/system/bin/sh", "-c", cmd};
        try {
            final Process p = Runtime.getRuntime().exec(runner);
            final java.io.ByteArrayOutputStream so = new java.io.ByteArrayOutputStream();
            final java.io.ByteArrayOutputStream se = new java.io.ByteArrayOutputStream();
            Thread a = new Thread(new Runnable() {
                @Override public void run() { pipe(p.getInputStream(), so); }
            });
            Thread b = new Thread(new Runnable() {
                @Override public void run() { pipe(p.getErrorStream(), se); }
            });
            a.setDaemon(true); b.setDaemon(true); a.start(); b.start();
            final long[] done = {0};
            final Object lock = new Object();
            Thread waiter = new Thread(new Runnable() {
                @Override public void run() {
                    try { int code = p.waitFor(); synchronized (lock) { done[0] = 1; lock.notifyAll(); } }
                    catch (Throwable ignored) {}
                }
            });
            waiter.setDaemon(true); waiter.start();
            synchronized (lock) { if (done[0] == 0) lock.wait(timeout); }
            if (done[0] == 0) { p.destroy(); }
            a.join(400); b.join(400);
            String out = new String(so.toByteArray(), "UTF-8");
            String errs = new String(se.toByteArray(), "UTF-8");
            int code = done[0] == 1 ? exitOf(p) : -1;
            JSONObject o = new JSONObject();
            o.put("ok", code == 0).put("exitCode", code).put("code", code)
                    .put("stdout", out).put("stderr", errs)
                    .put("output", out + errs)
                    .put("privileged", probe.ok)
                    .put("transport", false)
                    .put("note", probe.ok ? "经 su 提权执行" : "仅在 App 自身 uid 下执行，非 Shizuku特权");
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":" + q(String.valueOf(t.getMessage())) + "}";
        }
    }

    private static void pipe(InputStream in, java.io.ByteArrayOutputStream out) {
        try {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0 && out.size() < 512 * 1024) out.write(b, 0, n);
        } catch (Throwable ignored) {}
    }

    private static int exitOf(Process p) {
        try { return p.exitValue(); } catch (Throwable t) { return -1; }
    }

    /** /usage：应用使用统计（需 PACKAGE_USAGE_STATS，Manifest 已声明）。 */
    private String usage(String query, String body) {
        try {
            int days = 1;
            String d = param(query, "days", "");
            if (d.isEmpty()) d = json(body, "days");
            if (d != null && !d.isEmpty()) try { days = Math.max(1, Math.min(Integer.parseInt(d), 30)); }
            catch (Throwable ignored) {}
            if (!UsageStatsHelper.permissionGranted(app)) {
                Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { app.startActivity(i); } catch (Throwable ignored) {}
                return "{\"ok\":false,\"error\":\"需要先授予「使用情况访问权限」，已打开设置页\"}";
            }
            return UsageStatsHelper.queryUsageJson(app, days);
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":" + q(String.valueOf(t.getMessage())) + "}";
        }
    }

    /** /voice：android_voice 工具。 */
    private String voice(String body) {
        String action = json(body, "action");
        return VoiceBridge.get(app).voice(action);
    }

    /** /tts：android_tts 工具。 */
    private String tts(String body) {
        String action = json(body, "action");
        String text = json(body, "text");
        String qv = json(body, "queue");
        boolean queue = "true".equals(qv) || "1".equals(qv);
        return VoiceBridge.get(app).tts(action, text, queue);
    }

    /** /schedule、/schedule/add、/schedule/list、/schedule/cancel。 */
    private String schedule(String path, String query, String body) {
        try {
            if (path.endsWith("/list")) return ScheduleBridge.list(app).toString();
            if (path.endsWith("/cancel")) {
                return ScheduleBridge.cancel(app,
                        json(body, "source") == null ? "app" : json(body, "source"),
                        json(body, "taskId")).toString();
            }
            String text = json(body, "text");
            if (text == null || text.isEmpty()) text = json(body, "name");
            if (text == null || text.isEmpty()) return "{\"ok\":false,\"error\":\"text required\"}";
            String when = json(body, "when");
            String at = json(body, "at");
            long triggerAt;
            if (when == null || when.isEmpty()) when = at;
            if (when == null || when.isEmpty()) {
                triggerAt = System.currentTimeMillis() + 60000;
            } else if (when.matches("\\d+")) {
                triggerAt = System.currentTimeMillis() + Long.parseLong(when) * 1000L;
            } else {
                triggerAt = parseWhen(when);
            }
            if (triggerAt <= 0) return "{\"ok\":false,\"error\":\"when 格式无法解析\"}";
            String repeat = json(body, "repeat");
            if (repeat == null || repeat.isEmpty()) repeat = "once";
            int intervalMin = 0;
            String im = json(body, "intervalMin");
            if (im != null) try { intervalMin = Math.max(1, Integer.parseInt(im)); }
            catch (Throwable ignored) {}
            String id = "t" + System.currentTimeMillis() + "_" + (int) (Math.random() * 10000);
            ScheduledTaskStore.Task t = new ScheduledTaskStore.Task(
                    id, triggerAt, repeat, intervalMin, text);
            synchronized (ScheduledTaskStore.LOCK) {
                new ScheduledTaskStore(app.getFilesDir()).put(t);
                ScheduleAlarms.register(app, t);
            }
            ScheduleExecutor.log(app, "新建任务 taskId=" + id + " at=" + triggerAt + " text=" + text);
            JSONObject o = new JSONObject();
            o.put("ok", true).put("taskId", id).put("triggerAt", triggerAt)
                    .put("at", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                            java.util.Locale.CHINA).format(new java.util.Date(triggerAt)))
                    .put("repeat", repeat)
                    .put("hint", "到点会发通知并尝试投递给 AI 引擎；用 /schedule/cancel 取消");
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":" + q(String.valueOf(t.getMessage())) + "}";
        }
    }

    private static long parseWhen(String s) {
        try {
            java.text.SimpleDateFormat f1 = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA);
            try { return f1.parse(s).getTime(); } catch (Throwable ignored) {}
            java.text.SimpleDateFormat f2 = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA);
            java.util.Calendar cal = java.util.Calendar.getInstance();
            java.util.Date d = f2.parse(s);
            java.util.Calendar want = java.util.Calendar.getInstance();
            want.setTime(d);
            cal.set(java.util.Calendar.HOUR_OF_DAY, want.get(java.util.Calendar.HOUR_OF_DAY));
            cal.set(java.util.Calendar.MINUTE, want.get(java.util.Calendar.MINUTE));
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            if (cal.getTimeInMillis() <= System.currentTimeMillis()) cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
            return cal.getTimeInMillis();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** /lan：本机回环与局域网地址，供 lan-mesh 插件展示。 */
    private String lan(String query, String body) {
        try {
            org.json.JSONArray ips = new org.json.JSONArray();
            java.util.Enumeration<java.net.NetworkInterface> ns =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ns.hasMoreElements()) {
                java.net.NetworkInterface ni = ns.nextElement();
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress a = as.nextElement();
                    if (a.isLoopbackAddress() || a instanceof java.net.Inet6Address) continue;
                    String h = a.getHostAddress();
                    if (h != null) ips.put(h);
                }
            }
            JSONObject o = new JSONObject();
            o.put("ok", true).put("ips", ips).put("host", "127.0.0.1")
                    .put("enabled", false).put("urls", new org.json.JSONArray())
                    .put("note", "局域网共享由引擎侧 lan-mesh 插件提供，本桥只报地址");
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":" + q(String.valueOf(t.getMessage())) + "}";
        }
    }

    /** /nfc-*：NfcStore 的只读/布防；真正读卡需在 HarnessActivity 开 reader mode。 */
    private String nfc(String path, String query, String body) {
        try {
            if (path.endsWith("status")) return NfcStore.statusJson(app).toString();
            if (path.endsWith("clear")) return NfcStore.clearJson().toString();
            if (path.endsWith("last")) return NfcStore.lastJsonObj(0).toString();
            if (path.endsWith("history")) {
                JSONObject o = new JSONObject();
                o.put("ok", true).put("count", NfcStore.currentSeq())
                        .put("items", NfcStore.historyJson());
                return o.toString();
            }
            if (path.endsWith("arm")) {
                String on = json(body, "on");
                boolean v = "1".equals(on) || "true".equals(on) || "on".equals(on);
                return NfcStore.arm(app, v).toString();
            }
            return unsupported("nfc " + path);
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":" + q(String.valueOf(t.getMessage())) + "}";
        }
    }

    /** /notifications*：优先代理到 3181 无障碍服务的实时捕获，退回归档文件。 */
    private String notifications(String path, String query) {
        String proxied = proxyToA11y(path, query);
        if (proxied != null) return proxied;
        if (path.endsWith("clear")) {
            try {
                java.io.File dir = new java.io.File(app.getFilesDir(), "notification-history");
                java.io.File[] fs = dir.listFiles();
                int n = 0;
                if (fs != null) for (java.io.File f : fs) if (f.delete()) n++;
                return "{\"ok\":true,\"cleared\":" + n + "}";
            } catch (Throwable t) {
                return "{\"ok\":false,\"error\":" + q(String.valueOf(t.getMessage())) + "}";
            }
        }
        if (path.endsWith("status")) {
            boolean a11y = HarnessAccessibilityService.getInstance() != null;
            return "{\"ok\":true,\"a11yConnected\":" + a11y
                    + ",\"hint\":\"开启系统无障碍服务后可实时捕获通知\"}";
        }
        try {
            org.json.JSONArray arr = NotificationArchive.list(app.getFilesDir());
            JSONObject o = new JSONObject();
            o.put("ok", true).put("items", arr).put("count", arr.length());
            if (arr.length() == 0) o.put("hint", "暂无归档；开启无障碍服务后由 3181 实时捕获");
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":" + q(String.valueOf(t.getMessage())) + "}";
        }
    }

    /** 把通知类请求原样转给 3181；带共享密钥，超时 4 秒。 */
    private String proxyToA11y(String path, String query) {
        if (HarnessAccessibilityService.getInstance() == null) return null;
        java.net.HttpURLConnection c = null;
        try {
            String url = "http://127.0.0.1:3181" + path
                    + (query == null || query.isEmpty() ? "" : "?" + query);
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(4000);
            c.setRequestMethod("GET");
            c.setRequestProperty("X-DSH-Token", LocalBridgeAuth.token(app));
            int code = c.getResponseCode();
            java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (in != null && (n = in.read(buf)) > 0 && o.size() < 262144) o.write(buf, 0, n);
            if (in != null) in.close();
            String body = new String(o.toByteArray(), "UTF-8");
            return body.isEmpty() ? null : body;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** /log：返回引擎日志尾部，供控制台「日志」页与导出。 */
    private String logs(String query) {
        try {
            java.io.File f = new java.io.File(app.getFilesDir(), "dsh-web.log");
            int max = 32768;
            String m = param(query, "max", "");
            if (!m.isEmpty()) try { max = Math.max(1024, Math.min(Integer.parseInt(m), 262144)); }
            catch (Throwable ignored) {}
            String tail = tailOf(f, max);
            JSONObject o = new JSONObject();
            o.put("ok", true).put("size", f.exists() ? f.length() : 0)
                    .put("log", tail)
                    .put("shareUri", LogShareProvider.uriForAbs(app, f.getAbsolutePath()).toString());
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":" + q(String.valueOf(t.getMessage())) + "}";
        }
    }

    private static String tailOf(java.io.File f, int max) {
        try {
            if (!f.exists()) return "";
            byte[] all = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0, n;
            while (off < all.length && (n = in.read(all, off, all.length - off)) > 0) off += n;
            in.close();
            String s = new String(all, 0, off, "UTF-8");
            return s.length() > max ? s.substring(s.length() - max) : s;
        } catch (Throwable t) {
            return "";
        }
    }

    /** /backup：给出可备份目录清单与体积，不假装做完整时光机备份。 */
    private String backup(String query, String body) {
        try {
            java.io.File payload = new java.io.File(app.getFilesDir(), "payload");
            org.json.JSONArray dirs = new org.json.JSONArray();
            long total = 0;
            for (String d : new String[]{"payload/dshhome", "payload/dshroot", "payload/runtime"}) {
                java.io.File f = new java.io.File(app.getFilesDir(), d);
                long sz = f.exists() ? folderSize(f) : 0;
                total += sz;
                dirs.put(new JSONObject().put("path", d).put("bytes", sz));
            }
            JSONObject o = new JSONObject();
            o.put("ok", true).put("dirs", dirs).put("totalBytes", total)
                    .put("hint", "完整时光机备份/恢复需上游原生壳流程；本桥提供目录清单与体积");
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":" + q(String.valueOf(t.getMessage())) + "}";
        }
    }

    private static long folderSize(java.io.File f) {
        if (f.isFile()) return f.length();
        java.io.File[] fs = f.listFiles();
        if (fs == null) return 0;
        long s = 0;
        for (java.io.File x : fs) s += folderSize(x);
        return s;
    }

    private String update() {
        return "{\"ok\":true,\"current\":\"1.2.20\",\"versionCode\":1002020,"
                + "\"channel\":\"Buwrt/2\",\"hint\":\"在 githup 客户端内检查更新即可\"}";
    }

    private String unsupported(String what) {
        return "{\"ok\":false,\"error\":" + q(what + " 需要 Shizuku 特权或上游完整原生壳，本版未接入") + "}";
    }

    private static String param(String query, String key, String def) {
        if (query == null) return def;
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            if (key.equals(kv.substring(0, i))) {
                try { return URLDecoder.decode(kv.substring(i + 1), "UTF-8"); }
                catch (Throwable t) { return kv.substring(i + 1); }
            }
        }
        return def;
    }

    /** 极简 JSON 字符串字段提取，够用且不引第三方。 */
    static String json(String s, String key) {
        if (s == null) return null;
        String needle = "\"" + key + "\"";
        int i = s.indexOf(needle);
        if (i < 0) return null;
        int c = s.indexOf(':', i + needle.length());
        if (c < 0) return null;
        int q1 = s.indexOf('"', c + 1);
        if (q1 < 0) return null;
        StringBuilder b = new StringBuilder();
        for (int k = q1 + 1; k < s.length(); k++) {
            char ch = s.charAt(k);
            if (ch == '"') break;
            if (ch == '\\' && k + 1 < s.length()) {
                char nx = s.charAt(++k);
                if (nx == 'n') b.append('\n');
                else if (nx == 't') b.append('\t');
                else if (nx == 'r') b.append('\r');
                else b.append(nx);
            } else b.append(ch);
        }
        return b.toString();
    }

    static String q(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
