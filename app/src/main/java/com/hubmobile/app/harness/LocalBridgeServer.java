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
            if (path.startsWith("/open")) return open(body);
            if (path.startsWith("/status")) return status();
            if (path.startsWith("/changelog")) return changelog();
            if (path.startsWith("/notify")) return notify(body);
            if (path.startsWith("/clipboard")) return clipboard(query, body);
            if (path.startsWith("/setting")) return setting(query);
            if (path.startsWith("/shell")) return shell(body);
            if (path.startsWith("/usage")) return unsupported("usage stats");
            if (path.startsWith("/schedule")) return unsupported("scheduled tasks");
            if (path.startsWith("/voice")) return unsupported("speech recognition");
            if (path.startsWith("/tts")) return unsupported("text to speech");
            if (path.startsWith("/lan")) return unsupported("lan access");
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
        // 服务是异步拉起的，status 立即读可能还是旧值：show 后补一个 running 预期
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
                + "\"note\":\"虚拟屏/特权 shell 需要 Shizuku，本版未接入\"}";
    }

    private String changelog() {
        return "{\"ok\":true,\"version\":\"1.2.20\",\"notes\":"
                + q("githup 合并版：底座 + DeepSeek Harness 引擎 + App 本地桥 + 悬浮桌宠 + 无障碍服务")
                + "}";
    }

    private String open(String body) {
        try {
            String p = json(body, "path");
            String act = json(body, "action");
            if (p == null || p.isEmpty()) return "{\"ok\":false,\"error\":\"path required\"}";
            java.io.File f = new java.io.File(p);
            if (!f.exists()) return "{\"ok\":false,\"error\":\"file not found: " + p + "\"}";
            Uri uri;
            String mime;
            try {
                uri = LogShareProvider.uriForAbs(app, f.getAbsolutePath());
                mime = LogShareProvider.mimeOfName(f.getName());
            } catch (Throwable t) {
                return "{\"ok\":false,\"error\":\"content uri unavailable\"}";
            }
            if ("share".equals(act)) {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(mime == null ? "*/*" : mime);
                i.putExtra(Intent.EXTRA_STREAM, uri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                app.startActivity(Intent.createChooser(i, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if ("reveal".equals(act)) {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.parse("content://com.android.externalstorage.documents"), null);
                // 没有通用「打开所在文件夹」标准，退回用文件管理器打开父目录
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

    private String clipboard(String query, String body) {
        try {
            ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
            String set = json(body, "text");
            if (set != null) {
                cm.setPrimaryClip(ClipData.newPlainText("githup", set));
                return "{\"ok\":true}";
            }
            CharSequence c = cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0
                    ? cm.getPrimaryClip().getItemAt(0).getText() : "";
            return "{\"ok\":true,\"text\":" + q(String.valueOf(c)) + "}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":" + q(String.valueOf(t.getMessage())) + "}";
        }
    }

    private String setting(String query) {
        String which = param(query, "which", "");
        try {
            Intent i;
            if ("overlay".equals(which)) {
                i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + app.getPackageName()));
            } else if ("a11y".equals(which)) {
                i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            } else if ("battery".equals(which)) {
                i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
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

    /** 非特权 shell：只在 App 自己的 uid 下执行，不冒充 Shizuku。 */
    private String shell(String body) {
        String cmd = json(body, "command");
        if (cmd == null || cmd.isEmpty()) return "{\"ok\":false,\"error\":\"command required\"}";
        if (cmd.length() > 4096) return "{\"ok\":false,\"error\":\"command too long\"}";
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null && sb.length() < 32768) sb.append(l).append('\n');
            r.close();
            try { p.waitFor(); } catch (InterruptedException ignored) {}
            return "{\"ok\":true,\"privileged\":false,\"stdout\":" + q(sb.toString()) + "}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":" + q(String.valueOf(t.getMessage())) + "}";
        }
    }

    private String unsupported(String what) {
        return "{\"ok\":false,\"error\":" + q(what + " 需要 Shizuku 特权或上游原生壳，本版未接入") + "}";
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
