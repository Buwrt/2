package com.hubmobile.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 精简版 DeepSeek Harness 引擎启动桥。
 *
 * 与上游 525KB 的 MainActivity 不同，这里只做让本地引擎跑起来必需的四件事：
 *   ① 把 APK 里的 assets/payload.zip 增量展开到私有目录（含执行位）
 *   ② 按上游 spawnNode() 的同一套环境变量拉起 node + dsh/lib/bin.js web
 *   ③ 从引擎 stdout 里捞出带一次性 token 的 URL
 *   ④ 把 URL 交给 WebView（303 + Set-Cookie 后靠 cookie 保持会话）
 *
 * 刻意不引入 Shizuku、R 资源、悬浮窗、虚拟屏等上游原生模块：那些需要额外的
 * AAR 与权限链，见 MERGE_PLAN.md 第三节。AI 对话本体由 payload 内的 dsh 内核提供。
 *
 * 前提：targetSdk 必须 <= 28。Android 10+ 对 targetSdk>=29 的应用禁止执行
 * 私有目录下的 ELF（W^X），node 会直接 EACCES。这也是上游锁 28 的原因。
 */
public final class HarnessBridge {

    private static final String TAG = "HarnessBridge";
    public static final int PORT = 3080;
    private static final String BASE = "http://127.0.0.1:" + PORT;

    private static volatile Process sProc;
    private static volatile String sTokenUrl;
    private static volatile int sActivePort = PORT;
    private static final Object LOCK = new Object();

    /** zip 不保留 unix 执行位，解压后必须逐项补 —— 与上游 setExecutables() 同一份清单。 */
    private static final String[] EXECS = {
            "runtime/bin/node", "bin/bash", "runtime/bin/rg", "runtime/bin/curl",
            "bin/pnpm", "bin/git",
            "git/libexec/git-core/git-remote-https", "git/libexec/git-core/git-remote-http",
            "bin/python3", "bin/python", "python/bin/python3.14",
            "bin/npm", "bin/npx", "bin/pip", "bin/pip3"
    };

    /** 构建期统计：上游 payload.zip 的条目数（见 ci/build-ai-report.txt）。 */
    private static final long TOTAL_ENTRIES = 41426L;

    public interface Callback {
        void onStage(String text);
        /** 展开进度：已扫描条目 / 本次新写入条目 / 总条目。 */
        default void onProgress(long entries, long written, long total) {}
        void onReady(String url);
        void onError(String msg);
    }

    private HarnessBridge() {}

    private static File payload(Context c) {
        return new File(c.getFilesDir(), "payload");
    }

    /** 进程仍活着时，重进 Activity 直接走根路径（cookie 已由首次加载下发）。 */
    public static String existingUrl() {
        Process p = sProc;
        if (p != null && alive(p) && sTokenUrl != null)
            return "http://127.0.0.1:" + sActivePort + "/";
        return null;
    }

    private static boolean alive(Process p) {
        try { p.exitValue(); return false; } catch (IllegalThreadStateException e) { return true; }
    }

    public static void prepare(final Context app, final Callback cb) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    stage(ui, cb, "正在准备 AI 引擎文件…");
                    extract(app, ui, cb);
                    stage(ui, cb, "正在启动本地引擎…");
                    final String url = start(app, ui, cb);
                    ui.post(new Runnable() {
                        @Override public void run() { cb.onReady(url); }
                    });
                } catch (Throwable t) {
                    Log.e(TAG, "boot failed", t);
                    final String msg = String.valueOf(t.getMessage());
                    ui.post(new Runnable() {
                        @Override public void run() { cb.onError(msg); }
                    });
                }
            }
        }, "harness-boot").start();
    }

    private static void stage(Handler ui, final Callback cb, final String s) {
        ui.post(new Runnable() {
            @Override public void run() { cb.onStage(s); }
        });
    }

    private static void progress(Handler ui, final Callback cb,
                                 final long entries, final long written) {
        ui.post(new Runnable() {
            @Override public void run() { cb.onProgress(entries, written, TOTAL_ENTRIES); }
        });
    }

    /** 流式展开，避免把 160MB zip 一次性读进内存；已存在且大小一致则跳过。 */
    private static void extract(Context c, Handler ui, Callback cb) throws IOException {
        File dir = payload(c);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建 payload 目录");

        AssetManager am = c.getAssets();
        InputStream raw = am.open("payload.zip");
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(raw, 64 * 1024));
        byte[] buf = new byte[64 * 1024];
        ZipEntry e;
        long scanned = 0, written = 0;
        while ((e = zis.getNextEntry()) != null) {
            scanned++;
            String name = e.getName();
            if (name.startsWith("/") || name.contains("..")) { zis.closeEntry(); continue; }
            File out = new File(dir, name);
            if (e.isDirectory()) {
                out.mkdirs();
                zis.closeEntry();
                if (scanned % 400 == 0) progress(ui, cb, scanned, written);
                continue;
            }
            long sz = e.getSize();
            // 覆盖安装时 files/payload 会保留：大小一致就跳过，不算「新增」
            if (sz >= 0 && out.exists() && out.length() == sz) {
                zis.closeEntry();
                if (scanned % 400 == 0) progress(ui, cb, scanned, written);
                continue;
            }
            File parent = out.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs())
                throw new IOException("无法创建目录 " + parent);
            FileOutputStream fos = new FileOutputStream(out);
            int n;
            while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            if (needExec(name)) out.setExecutable(true, false);
            written++;
            zis.closeEntry();
            if (scanned % 400 == 0) progress(ui, cb, scanned, written);
        }
        zis.close();
        raw.close();
        progress(ui, cb, scanned, written);

        // 覆盖升级后执行位可能丢失，每次都补一遍（成本极低）
        for (String p : EXECS) {
            File f = new File(dir, p);
            if (f.exists()) f.setExecutable(true, false);
        }
        // payload.zip 打包时把软链拆成了 LINKS.txt 清单（zip 不存 symlink）。
        // node 的 DT_NEEDED 是 libz.so.1 / libsqlite3.so / libicui18n.so.78 / libicuuc.so.78，
        // 而 lib/ 下只有 libz.so.1.3.2 这类带完整版本号的真身。不还原软链，
        // linker 直接报 CANNOT LINK EXECUTABLE: library "libz.so.1" not found。
        // 上游 MainActivity 的 applyLinks() 干的就是这件事，精简桥初版漏了。
        // 幂等：每次启动都跑，覆盖安装后旧数据目录也能自愈，无需清数据。
        final int made = applyLinks(dir);
        ensureProfileKernelLinks(dir);
        if (made > 0) {
            stage(ui, cb, "已还原 " + made + " 个运行库软链，准备启动…");
            Log.i(TAG, "runtime/lib links restored: " + made);
        }

        File node = new File(dir, "runtime/bin/node");
        File binjs = new File(dir, "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js");
        if (!node.exists() || !binjs.exists()) throw new IOException("payload 结构不完整：缺 node 或 dsh/bin.js");
    }

    /**
     * 还原 runtime/lib/LINKS.txt 里记录的软链。
     * 格式（tab 分隔）：链接名 \t 真身文件名
     *
     * 优先硬链（同分区、最稳），不行退软链，再不行复制一份真身过来 ——
     * 与上游 applyLinks() 完全一致，保证 FUSE / 某些厂商文件系统也能用。
     */
    private static int applyLinks(File payload) {
        File lib = new File(payload, "runtime/lib");
        File list = new File(lib, "LINKS.txt");
        if (!list.exists()) return 0;
        int made = 0;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(list), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\t+");
                if (parts.length < 2) continue;
                String linkName = parts[0].trim(), target = parts[1].trim();
                File link = new File(lib, linkName);
                File src = new File(lib, target);
                if (!src.exists()) continue;
                if (link.exists()) {
                    // 硬链/复制出来的长度必须等于真身；软链则 readlink 能成功。
                    // 其余情况视为坏的占位文件（比如只写了目标路径文本），删掉重建。
                    boolean soft = false;
                    try { Os.readlink(link.getAbsolutePath()); soft = true; } catch (Throwable ignored) {}
                    if (soft || link.length() == src.length()) continue;
                    if (!link.delete()) continue;
                }
                try {
                    Os.link(src.getAbsolutePath(), link.getAbsolutePath());
                    made++;
                } catch (Throwable e1) {
                    try {
                        Os.symlink(target, link.getAbsolutePath());
                        made++;
                    } catch (Throwable e2) {
                        try { copyFile(src, link); made++; }
                        catch (Throwable e3) { Log.w(TAG, "link failed " + linkName, e3); }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyLinks failed", t);
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
        }
        return made;
    }

    /** profile 树补上指向内核树的包软链，缺了会让部分插件静默失效。与上游同名方法一致。 */
    private static void ensureProfileKernelLinks(File payload) {
        File list = new File(payload, "dshhome/profiles/web/PROFILE-KERNEL-LINKS.txt");
        File kernel = new File(payload,
                "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai");
        File prof = new File(payload, "dshhome/profiles/web/node_modules/@deepseek-ai");
        if (!list.exists() || !kernel.isDirectory() || !prof.isDirectory()) return;
        BufferedReader r = null;
        int made = 0;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(list), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                File src = new File(kernel, line), dst = new File(prof, line);
                if (!src.isDirectory() || dst.exists()) continue;
                try { Os.symlink(src.getAbsolutePath(), dst.getAbsolutePath()); made++; }
                catch (Throwable t) { Log.w(TAG, "profile kernel link failed: " + line, t); }
            }
            if (made > 0) Log.i(TAG, "profile kernel links restored: " + made);
        } catch (Throwable t) {
            Log.w(TAG, "ensureProfileKernelLinks failed", t);
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] b = new byte[128 * 1024];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        out.close();
        in.close();
    }

    private static boolean needExec(String name) {
        for (String p : EXECS) if (p.equals(name)) return true;
        return false;
    }

    private static String start(Context c, Handler ui, Callback cb) throws Exception {
        synchronized (LOCK) {
            String reuse = existingUrl();
            if (reuse != null) return reuse;

            File dir = payload(c);
            File node = new File(dir, "runtime/bin/node");
            File binjs = new File(dir, "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js");
            File tmp = new File(c.getCacheDir(), "tmp");
            if (!tmp.exists()) tmp.mkdirs();
            File log = new File(c.getFilesDir(), "dsh-web.log");
            node.setExecutable(true, false);

            // 覆盖安装 / 反复重进时，App 被系统杀掉但上一轮的 node 子进程不一定跟着退，
            // 会变成孤儿继续占着 3080；新进程 listen EADDRINUSE 立刻退出 —— 这正是
            // 「引擎进程提前退出」最常见的原因。上游 MainActivity 有 findEnginePid/killEngineNow，
            // 精简桥这里必须补上等价逻辑，只杀 cmdline 带本 payload 路径的自己的引擎。
            killStaleEngines(dir, PORT);
            int port = pickPort(PORT);
            sActivePort = port;
            final String base = "http://127.0.0.1:" + port;
            stage(ui, cb, "本地引擎端口 " + port + "，正在拉起 node…");
            // 清空旧日志，避免抓到上一次启动已作废的 token（必须在 kill 之后）
            FileOutputStream clr = new FileOutputStream(log, false);
            clr.close();

            List<String> cmd = new ArrayList<String>();
            cmd.add(node.getAbsolutePath());
            cmd.add("--expose-internals");
            cmd.add(binjs.getAbsolutePath());
            cmd.add("web");
            cmd.add("--host"); cmd.add("127.0.0.1");
            cmd.add("--port"); cmd.add(String.valueOf(port));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            Map<String, String> env = pb.environment();
            // libc / libm / libdl 由系统提供，把系统库目录一并带上，
            // 避免部分 ROM 的 linker namespace 只认这几个路径。
            String nld = "";
            try { nld = c.getApplicationInfo().nativeLibraryDir; } catch (Throwable ignored) {}
            if (nld == null) nld = "";
            env.put("LD_LIBRARY_PATH", new File(dir, "runtime/lib").getAbsolutePath()
                    + (nld.isEmpty() ? "" : ":" + nld)
                    + ":/system/lib64:/vendor/lib64:/system/lib:/vendor/lib");
            env.put("HOME", c.getFilesDir().getAbsolutePath());
            env.put("DSH_HOME", new File(dir, "dshhome").getAbsolutePath());
            env.put("TMPDIR", tmp.getAbsolutePath());
            env.put("SHELL", "/system/bin/sh");
            env.put("TERM", "xterm");
            env.put("PATH", new File(dir, "bin").getAbsolutePath() + ":"
                    + new File(dir, "runtime/bin").getAbsolutePath() + ":"
                    + new File(c.getFilesDir(), "bin").getAbsolutePath()
                    + ":/system/bin:/system/xbin");
            File ossl = new File(dir, "runtime/etc/openssl.cnf");
            if (ossl.exists()) env.put("OPENSSL_CONF", ossl.getAbsolutePath());
            env.put("GIT_EXEC_PATH", new File(dir, "git/libexec/git-core").getAbsolutePath());
            env.put("GIT_TEMPLATE_DIR", new File(dir, "git/templates").getAbsolutePath());
            env.put("GIT_PAGER", "cat");
            env.put("GIT_TERMINAL_PROMPT", "0");
            env.put("SHIZUKU_APP_ID", c.getPackageName());
            env.put("SHIZUKU_AVAILABLE", "0");
            env.put("ROOT_AVAILABLE", "0");
            File ca = systemCaBundle(c);
            if (ca != null) {
                env.put("SSL_CERT_FILE", ca.getAbsolutePath());
                env.put("CURL_CA_BUNDLE", ca.getAbsolutePath());
                env.put("GIT_SSL_CAINFO", ca.getAbsolutePath());
            }
            pb.redirectErrorStream(true);

            final Process p = pb.start();
            sProc = p;

            final StringBuilder tail = new StringBuilder();
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    InputStream is = null;
                    FileOutputStream fos = null;
                    try {
                        is = p.getInputStream();
                        fos = new FileOutputStream(log, true);
                        byte[] b = new byte[4096];
                        int n;
                        while ((n = is.read(b)) > 0) {
                            fos.write(b, 0, n);
                            fos.flush();
                            tail.append(new String(b, 0, n, "UTF-8"));
                            if (tail.length() > 512 * 1024) tail.delete(0, tail.length() - 256 * 1024);
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
                        try { if (is != null) is.close(); } catch (Throwable ignored) {}
                    }
                }
            }, "harness-log");
            reader.setDaemon(true);
            reader.start();

            // 引擎打印 http://127.0.0.1:3080/?token=xxxxx；token 一次性，只认最后一条
            Pattern pat = Pattern.compile("https?://[^\\s\"'<>]*[?&]token=([A-Za-z0-9_\\-]+)");
            long deadline = System.currentTimeMillis() + 180000L;
            String token = null;
            long lastTick = 0;
            while (System.currentTimeMillis() < deadline) {
                if (!alive(p)) {
                    // 等日志线程把 stdout 写完，再把真实原因显示到开机页
                    try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                    String why = tailOf(log, 1200);
                    if (why.isEmpty()) why = tail.toString();
                    if (why.length() > 1200) why = why.substring(why.length() - 1200);
                    throw new IOException("引擎进程提前退出" + (why.isEmpty() ? "" : "：\n" + why));
                }
                Matcher m = pat.matcher(tail);
                while (m.find()) token = m.group(1);
                if (token != null) break;
                long now = System.currentTimeMillis();
                if (now - lastTick > 5000) {
                    lastTick = now;
                    stage(ui, cb, "等待引擎就绪…（" + ((now - (deadline - 180000L)) / 1000) + "s）");
                }
                Thread.sleep(300);
            }
            if (token == null) {
                String why = tailOf(log, 800);
                throw new IOException("引擎 180s 内未返回访问令牌"
                        + (why.isEmpty() ? "" : "：\n" + why));
            }

            String url = base + "/?token=" + token;
            sTokenUrl = url;
            return url;
        }
    }

    /** 读文件尾部，用于把 node 的真实报错显示到开机页。 */
    private static String tailOf(File f, int max) {
        try {
            if (!f.exists()) return "";
            byte[] all = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int off = 0, n;
            while (off < all.length && (n = in.read(all, off, all.length - off)) > 0) off += n;
            in.close();
            String s = new String(all, 0, off, "UTF-8");
            return s.length() > max ? s.substring(s.length() - max) : s;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String readCmdline(int pid) {
        try {
            File f = new File("/proc/" + pid + "/cmdline");
            if (!f.exists()) return null;
            byte[] b = new byte[8192];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(b);
            in.close();
            if (n <= 0) return null;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) sb.append(b[i] == 0 ? ' ' : (char) b[i]);
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 只杀「本应用 payload 目录下、且带 web --port <port>」的残留 node。
     * 路径锚定保证绝不会误伤别的应用或 Termux 里的 node。
     */
    private static void killStaleEngines(File dir, int port) {
        String marker = dir.getAbsolutePath();
        File[] pids = new File("/proc").listFiles();
        if (pids == null) return;
        int killed = 0;
        for (File f : pids) {
            String name = f.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            int pid;
            try { pid = Integer.parseInt(name); } catch (Throwable t) { continue; }
            if (pid == android.os.Process.myPid()) continue;
            String cmd = readCmdline(pid);
            if (cmd == null || !cmd.contains(marker)) continue;
            if (!cmd.contains("bin.js") || !cmd.contains("web")) continue;
            try {
                android.system.Os.kill(pid, 9);   // SIGKILL：端口必须立刻释放
                killed++;
            } catch (Throwable ignored) {}
        }
        if (killed > 0) {
            Log.w(TAG, "清理残留引擎进程 " + killed + " 个");
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
        }
    }

    private static boolean portBusy(int port) {
        java.net.Socket s = null;
        try {
            s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 250);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (s != null) s.close(); } catch (Throwable ignored) {}
        }
    }

    /** 万一孤儿杀不掉或端口被别的进程占着，顺延找空端口，不让引擎直接崩。 */
    private static int pickPort(int from) {
        for (int i = 0; i < 12; i++) {
            if (!portBusy(from + i)) return from + i;
        }
        return from;
    }

    /** 把 /system/etc/security/cacerts 合成一份 PEM，供内置 curl / git 走 HTTPS。 */
    private static File systemCaBundle(Context c) {
        try {
            File out = new File(c.getCacheDir(), "cacert.pem");
            if (out.exists() && out.length() > 1024) return out;
            File dir = new File("/system/etc/security/cacerts");
            File[] fs = dir.listFiles();
            if (fs == null || fs.length == 0) return null;
            FileOutputStream fos = new FileOutputStream(out);
            byte[] one = new byte[8192];
            for (File f : fs) {
                FileInputStream in = new FileInputStream(f);
                int n;
                while ((n = in.read(one)) > 0) fos.write(one, 0, n);
                in.close();
                fos.write('\n');
            }
            fos.close();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }
}
