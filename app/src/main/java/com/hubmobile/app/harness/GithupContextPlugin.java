package com.hubmobile.app.harness;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 阶段 X：把「githup 当前页面」变成 DSH 的**每轮自动上下文**，而不是
 * 写在文档里等模型自己去读。
 *
 * 上一轮把说明写进了工作区 AGENTS.md，但 DSH 记忆系统读的是
 * $DSH_HOME/AGENTS.md，而且模型不会主动 curl /app/context —— 于是它继续
 * 检查 gh CLI、逐文件读仓库、编造界面，既「不会辅助」又「特别费 token」。
 *
 * 这里在引擎启动前做三件事（幂等、每次启动重写）：
 *   ① 把 dsh-plugin-githup-context 写进 profiles/web/node_modules/
 *   ② 往 profiles/web/cordis.patch.yml 追加 - insert: 条目启用它
 *      （语法照 dshhome/cordis.patch.yml 里已有的 insert 块）
 *   ③ 往 $DSH_HOME/AGENTS.md **追加**一段 ≤1.2KB 的省 token 规矩
 *
 * 插件在 agent/pre-step 每轮拉一次 /app/context?brief=1，把 ≤700 字符的
 * 当前页面作为一条 notice 注入 messages。模型不用探测、不用读全库。
 *
 * 只有用户在设置页点了「同意」（AppContext.consent）才启用；关掉即摘除。
 */
public final class GithupContextPlugin {

    private static final String TAG = "GithupCtxPlugin";
    private static final String B = "# githup-context-plugin begin";
    private static final String E = "# githup-context-plugin end";
    private static final String AB = "<!-- githup-context begin -->";
    private static final String AE = "<!-- githup-context end -->";

    private GithupContextPlugin() {}

    /** 在 HarnessBridge 起 node 之前调用。任何异常都吞掉，绝不阻塞引擎。 */
    public static void apply(Context c, File dshHome) {
        try {
            boolean on = AppContext.consent(c);
            File web = new File(dshHome, "profiles/web");
            File mods = new File(web, "node_modules/dsh-plugin-githup-context");
            File lib = new File(mods, "lib");
            if (!lib.exists() && !lib.mkdirs()) return;

            if (on) {
                writeAsset(c, "harness/githup-context-package.json", new File(mods, "package.json"));
                writeAsset(c, "harness/githup-context-plugin.js", new File(lib, "index.js"));
            } else {
                /* 关掉时留着文件不碍事，但绝不 insert；下次开启会重写 */
            }

            /* ② cordis.patch.yml —— 幂等块，照 AccountDefaultRoute 的写法 */
            File patch = new File(web, "cordis.patch.yml");
            String old = read(patch);
            String clean = strip(old, B, E);
            String next = clean;
            if (on) {
                if (!next.endsWith("\n")) next += "\n";
                next += "\n" + B + "\n"
                        + "# 每轮自动把 githup 当前页面/仓库注入模型上下文。\n"
                        + "- insert:\n"
                        + "    - id: githup-context\n"
                        + "      name: dsh-plugin-githup-context\n"
                        + E + "\n";
            }
            if (!next.equals(old)) write(patch, next);

            /* ③ $DSH_HOME/AGENTS.md —— 追加，不覆盖记忆系统已有内容 */
            File agents = new File(dshHome, "AGENTS.md");
            String ao = read(agents);
            String ac = strip(ao, AB, AE);
            String an = ac;
            if (on) {
                if (!an.endsWith("\n")) an += "\n";
                String block = asset(c, "harness/githup-AGENTS.md");
                an += "\n" + block.trim() + "\n";
            }
            if (!an.equals(ao)) write(agents, an);

            Log.i(TAG, "apply consent=" + on + " dshHome=" + dshHome.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "apply failed", t);
        }
    }

    private static void writeAsset(Context c, String name, File out) throws Exception {
        write(out, asset(c, name));
    }

    private static String asset(Context c, String name) throws Exception {
        AssetManager am = c.getAssets();
        InputStream in = am.open(name);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String read(File f) {
        try {
            if (!f.isFile()) return "";
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0;
            while (off < b.length) { int n = in.read(b, off, b.length - off); if (n < 0) break; off += n; }
            in.close();
            return new String(b, 0, off, StandardCharsets.UTF_8);
        } catch (Throwable t) { return ""; }
    }

    private static void write(File f, String s) throws Exception {
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(f, false);
        o.write(s.getBytes(StandardCharsets.UTF_8));
        o.close();
    }

    private static String strip(String s, String begin, String end) {
        if (s == null) return "";
        int a = s.indexOf(begin);
        if (a < 0) return s;
        int b = s.indexOf(end, a);
        if (b < 0) return s.substring(0, a);
        return s.substring(0, a) + s.substring(b + end.length());
    }
}
