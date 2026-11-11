package com.hubmobile.app.harness;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 阶段 Z：引擎端口的**唯一真相来源**。
 *
 * 为什么不再用 3080：真机连续两轮报
 * `listen EADDRINUSE: 127.0.0.1:3080`（webserver required 失败 → 10 个插件
 * pending → StartupError）。上一轮统一了启动入口，但 pickPort 仍用
 * `Socket.connect(250ms)` 判断端口是否空闲：它只能说明「有没有人在 listen」，
 * 在部分 ROM 上会因 connect 超时/回环策略把**已被占用**的 3080 误判为空闲，
 * 于是 CLI 照传 `--port 3080`，node 几秒后 listen 直接撞上。
 *
 * 占用者可能是：覆盖安装前旧版本留下的孤儿 node（同 uid）、或手机上仍装着的
 * 上游原版 com.deepseek.harness（不同 uid，按设计不杀别的 App）。
 * killStaleEngines 依赖 /proc/<pid>/cmdline，Android 10+ 对其它进程有 SELinux
 * 读取限制，真机上很可能一个都杀不掉 —— 不能把它当兜底。
 *
 * 本类做三件事：
 *   ① 默认端口改成 3090，彻底离开 3080/3082/3084（上游三版本）与 3081（本包本地桥）
 *   ② 提供 ServerSocket **真 bind** 探测（bind 成功才说明「我能 listen」）
 *   ③ 往 $DSH_HOME/cordis.patch.yml 写 `- id: webserver` 覆盖块，把
 *      `ctx.webStartup.port ?? <选定端口>` 定死 —— 即便 CLI --port 没被解析，
 *      引擎也不会回落到内核默认的 3080。
 */
public final class EnginePorts {

    /** 起步端口；远离 3080/3082/3084（上游三版本）与 3081（本地桥）。 */
    public static final int DEFAULT = 3090;
    /** 围栏与顺延的上界（不含）。 */
    public static final int MAX = 3100;

    private static final String PREFS = "dsh_prefs";
    private static final String KEY = "engine_port";

    private static final String B = "# >>> githup engine-port begin >>>";
    private static final String E = "# <<< githup engine-port end <<<";

    private EnginePorts() {}

    /** 当前引擎端口：优先读启动时落盘的真实端口。 */
    public static int get(Context c) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int v = sp.getInt(KEY, 0);
            if (v >= DEFAULT && v < MAX) return v;
        } catch (Throwable ignored) {}
        return DEFAULT;
    }

    public static void save(Context c, int port) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY, port).apply();
        } catch (Throwable ignored) {}
    }

    /**
     * 真 bind 探测：bind 成功 = 这个端口我确实能 listen。
     * 比 connect 可靠得多 —— connect 只说明「有人在听」，且 250ms 超时易误判。
     */
    public static boolean bindable(int port) {
        java.net.ServerSocket s = null;
        try {
            s = new java.net.ServerSocket();
            s.setReuseAddress(false);
            s.bind(new java.net.InetSocketAddress("127.0.0.1", port), 1);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (s != null) s.close(); } catch (Throwable ignored) {}
        }
    }

    /** 从 DEFAULT 起找第一个真能 bind 的端口；全失败时回 DEFAULT 交给重试逻辑。 */
    public static int pick(int from) {
        int base = from < DEFAULT ? DEFAULT : from;
        for (int p = base; p < MAX; p++) {
            if (bindable(p)) return p;
        }
        return DEFAULT;
    }

    /**
     * 把选定端口写进用户层 patch，覆盖 webserver 行的 config。
     *
     * 注意 dsh-web-app 的 bundle 层是：
     *   port: !!js ctx.webStartup.port ?? 3080
     * 用户层 patch 按 id 替换**整份 config**，所以这里把 6 个键全部 restate，
     * 只把兜底默认值换成选定端口；CLI 若正常解析则 webStartup.port 优先。
     *
     * 幂等：BEGIN/END 块每次启动重写，且 extract() 会先把 patch 还原成
     * zip 原版 —— 所以必须在起 node 前、每次启动都调。
     */
    public static void applyPatch(File dshHome, int port) {
        try {
            File f = new File(dshHome, "cordis.patch.yml");
            String old = read(f);
            String cur = strip(old);
            if (!cur.endsWith("\n")) cur += "\n";
            cur += "\n" + B + "\n"
                    + "# githup：引擎端口离开 3080，避开上游原版/旧残留；CLI 优先，兜底用实测端口。\n"
                    + "- id: webserver\n"
                    + "  name: '@deepseek-ai/dsh-host-webserver'\n"
                    + "  inject: [webStartup]\n"
                    + "  config:\n"
                    + "    host: !!js ctx.webStartup.host ?? '127.0.0.1'\n"
                    + "    port: !!js ctx.webStartup.port ?? " + port + "\n"
                    + "    tls: !!js ctx.webStartup.tls\n"
                    + "    compression: gzip\n"
                    + "    compressionLevel: 1\n"
                    + "    compressionThresholdBytes: 1024\n"
                    + E + "\n";
            if (!cur.equals(old)) {
                FileOutputStream fos = new FileOutputStream(f, false);
                fos.write(cur.getBytes(StandardCharsets.UTF_8));
                fos.close();
            }
        } catch (Throwable ignored) {}
    }

    private static String read(File f) {
        try {
            if (!f.exists()) return "";
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            in.close();
            return new String(b, 0, off, StandardCharsets.UTF_8);
        } catch (Throwable t) { return ""; }
    }

    private static String strip(String s) {
        int a = s.indexOf(B), z = s.indexOf(E);
        if (a < 0 || z < 0 || z <= a) return s;
        return s.substring(0, a) + s.substring(z + E.length());
    }
}
