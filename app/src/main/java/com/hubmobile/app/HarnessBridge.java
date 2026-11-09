package com.hubmobile.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
    private static final Object LOCK = new Object();

    /** zip 不保留 unix 执行位，解压后必须逐项补 —— 与上游 setExecutables() 同一份清单。 */
    private static final String[] EXECS = {
            "runtime/bin/node", "bin/bash", "runtime/bin/rg", "runtime/bin/curl",
            "bin/pnpm", "bin/git",
            "git/libexec/git-core/git-remote-https", "git/libexec/git-core/git-remote-http",
            "bin/python3", "bin/python", "python/bin/python3.14",
            "bin/npm", "bin/npx", "bin/pip", "bin/pip3"
    };

    public interface Callback {
        void onStage(String text);
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
        if (p != null && alive(p) && sTokenUrl != null) return BASE + "/";
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

    /** 流式展开，避免把 160MB zip 一次性读进内存；已存在且大小一致则跳过。 */
    private static void extract(Context c, Handler ui, Callback cb) throws IOException {
        File dir = payload(c);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建 payload 目录");

        AssetManager am = c.getAssets();
        InputStream raw = am.open("payload.zip");
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(raw, 64 * 1024));
        byte[] buf = new byte[64 * 1024];
        ZipEntry e;
        int count = 0, written = 0;
        while ((e = zis.getNextEntry()) != null) {
            String name = e.getName();
            if (name.startsWith("/") || name.contains("..")) { zis.closeEntry(); continue; }
            File out = new File(dir, name);
            if (e.isDirectory()) {
                out.mkdirs();
                zis.closeEntry();
                continue;
            }
            long sz = e.getSize();
            if (sz >= 0 && out.exists() && out.length() == sz) {
                zis.closeEntry();
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
            count++;
            if (count % 2000 == 0) {
                final int k = count, w = written;
                stage(ui, cb, "正在展开引擎文件 " + k + " 项（新增 " + w + "）…");
            }
        }
        zis.close();
        raw.close();

        // 覆盖升级后执行位可能丢失，每次都补一遍（成本极低）
        for (String p : EXECS) {
            File f = new File(dir, p);
            if (f.exists()) f.setExecutable(true, false);
        }
        File node = new File(dir, "runtime/bin/node");
        File binjs = new File(dir, "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js");
        if (!node.exists() || !binjs.exists()) throw new IOException("payload 结构不完整：缺 node 或 dsh/bin.js");
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
            // 清空旧日志，避免抓到上一次启动已作废的 token
            FileOutputStream clr = new FileOutputStream(log, false);
            clr.close();
            node.setExecutable(true, false);

            List<String> cmd = new ArrayList<String>();
            cmd.add(node.getAbsolutePath());
            cmd.add("--expose-internals");
            cmd.add(binjs.getAbsolutePath());
            cmd.add("web");
            cmd.add("--host"); cmd.add("127.0.0.1");
            cmd.add("--port"); cmd.add(String.valueOf(PORT));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            Map<String, String> env = pb.environment();
            env.put("LD_LIBRARY_PATH", new File(dir, "runtime/lib").getAbsolutePath());
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
                if (!alive(p)) throw new IOException("引擎进程提前退出，日志见 /files/dsh-web.log");
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
            if (token == null) throw new IOException("引擎 180s 内未返回访问令牌");

            String url = BASE + "/?token=" + token;
            sTokenUrl = url;
            return url;
        }
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
