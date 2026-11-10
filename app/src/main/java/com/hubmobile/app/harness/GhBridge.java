package com.hubmobile.app.harness;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.hubmobile.app.SecurePrefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 阶段 M：把 githup 的 GitHub 能力「授权」给 DSH，但令牌不出设备。
 *
 * 关键设计（为什么不把 token 交给 DSH）：
 *   ① 底座对用户的承诺是「令牌只保存在设备本机、不会上传到任何服务器」；
 *   ② DSH 自带的 forge-github 插件会主动把子进程环境里名称含 TOKEN 的变量
 *      全部剥掉，所以「塞进 env / 配置文件」这条路根本走不通；
 *   ③ 把令牌写进 payload 文件更糟 —— 任何能读私有目录的进程都能拿走。
 *
 * 所以「授权」的实质是：在 127.0.0.1:3081 开一条 **本机只读代理**。
 * token 继续锁在 Android Keystore（SecurePrefs，AES-GCM），只在 App 进程内
 * 附加到发往 api.github.com 的请求头；DSH / node 侧拿到的是一个不含任何
 * 凭据的回环 URL。令牌既不进 DSH 的环境变量，也不进它的任何文件。
 *
 * 三道闸门：
 *   · 设置页开关默认关（ai_github_on），关着时 /gh/* 全部 403
 *   · 只放行 GET，POST/PUT/PATCH/DELETE 一律拒绝（AI 改不了仓库）
 *   · 目标域只允许 api.github.com 与 codeload.github.com（zipball 重定向）
 */
public final class GhBridge {

    public static final String API = "https://api.github.com";
    private static final String PREF = "dsh_prefs";
    private static final String KEY_ON = "ai_github_on";
    private static final String KEY_WS = "gh_workspace";

    private static final long MAX_ZIP = 60L * 1024 * 1024;
    private static final long MAX_ENTRY = 2L * 1024 * 1024;
    private static final int MAX_ENTRIES = 4000;

    private GhBridge() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ==================== 开关 ====================

    public static boolean enabled(Context c) {
        return prefs(c).getBoolean(KEY_ON, false);
    }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_ON, on).apply();
        if (!on) setCurrent(c, "");   // 关掉时顺手清掉已选工作区
    }

    // ==================== 令牌（只在 App 进程内可见）====================

    /** 当前生效令牌；用户退出登录时回落到「记住的令牌」。为空表示还没登录。 */
    public static String token(Context c) {
        String t = SecurePrefs.get(c, "gh_token", "");
        if (t == null || t.isEmpty()) t = SecurePrefs.get(c, "gh_token_saved", "");
        return t == null ? "" : t.trim();
    }

    private static Map<String, String> headers(Context c) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Authorization", "Bearer " + token(c));
        h.put("Accept", "application/vnd.github+json");
        h.put("X-GitHub-Api-Version", "2022-11-28");
        h.put("User-Agent", "githup-ai-bridge/1.0");
        return h;
    }

    // ==================== 只读 REST 代理 ====================

    /**
     * 只放行 GET。apiPath 形如 "/user/repos?per_page=100" 或
     * "/repos/owner/name/pulls"。任何写方法都拒绝，避免 AI 被提示词注入后改动仓库。
     */
    public static String proxy(Context c, String method, String apiPath, String body) {
        if (!enabled(c)) return err("AI 辅助 githup 未开启：请到「我的 → 设置」打开开关");
        if (token(c).isEmpty()) return err("还没有 GitHub 令牌：请先在 githup 里登录或粘贴令牌");
        if (method != null && !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method))
            return err("只读授权：不允许 " + method + " 写入操作");
        if (apiPath == null || !apiPath.startsWith("/") || apiPath.contains("..")
                || apiPath.contains("://") || apiPath.contains("\n"))
            return err("非法的 API 路径");

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(API + apiPath).openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            for (Map.Entry<String, String> e : headers(c).entrySet())
                conn.setRequestProperty(e.getKey(), e.getValue());
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            if (in != null) {
                byte[] b = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(b)) > 0) {
                    total += n;
                    if (total > 8L * 1024 * 1024) break;   // 单次响应上限 8MB
                    o.write(b, 0, n);
                }
                in.close();
            }
            String text = new String(o.toByteArray(), "UTF-8");
            JSONObject out = new JSONObject();
            out.put("ok", code >= 200 && code < 300).put("status", code);
            // 有些 404/403 的 body 不是 JSON，包一层免得前端 parse 炸
            try { out.put("data", new JSONObject(text)); }
            catch (Throwable t) {
                try { out.put("data", new JSONArray(text)); }
                catch (Throwable t2) { out.put("data", text); }
            }
            return out.toString();
        } catch (Throwable t) {
            return err("GitHub 请求失败：" + t.getMessage());
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /** 当前令牌能看到的仓库（含私有），供工作区选择器列清单。 */
    public static String repos(Context c) {
        if (!enabled(c)) return err("AI 辅助 githup 未开启");
        if (token(c).isEmpty()) return err("还没有 GitHub 令牌，请先在 githup 登录");
        String raw = proxy(c, "GET",
                "/user/repos?per_page=100&sort=updated&affiliation=owner,collaborator,organization_member", null);
        try {
            JSONObject o = new JSONObject(raw);
            if (!o.optBoolean("ok")) return raw;
            Object d = o.get("data");
            if (!(d instanceof JSONArray)) return err("仓库列表格式异常");
            JSONArray src = (JSONArray) d, out = new JSONArray();
            for (int i = 0; i < src.length(); i++) {
                JSONObject r = src.getJSONObject(i);
                out.put(new JSONObject()
                        .put("full_name", r.optString("full_name"))
                        .put("private", r.optBoolean("private"))
                        .put("default_branch", r.optString("default_branch", "main"))
                        .put("description", r.optString("description", "")));
            }
            return new JSONObject().put("ok", true).put("repos", out).toString();
        } catch (Throwable t) {
            return err("解析仓库列表失败：" + t.getMessage());
        }
    }

    // ==================== 工作区 ====================

    public static String current(Context c) {
        return prefs(c).getString(KEY_WS, "");
    }

    public static void setCurrent(Context c, String fullName) {
        prefs(c).edit().putString(KEY_WS, fullName == null ? "" : fullName).apply();
    }

    public static File workspaceRoot(Context c) {
        return new File(c.getFilesDir(), "gh-workspace");
    }

    public static File workspaceDir(Context c, String fullName) {
        return new File(workspaceRoot(c),
                fullName.replace("/", "__").replace("\\", "_"));
    }

    public static String workspaceState(Context c) {
        try {
            String name = current(c);
            File dir = name.isEmpty() ? null : workspaceDir(c, name);
            boolean ready = dir != null && dir.isDirectory();
            int files = ready ? countFiles(dir) : 0;
            return new JSONObject().put("ok", true)
                    .put("enabled", enabled(c))
                    .put("repo", name)
                    .put("path", ready ? dir.getAbsolutePath() : "")
                    .put("unset", name.isEmpty())
                    .put("ready", ready)
                    .put("files", files)
                    .put("bridge", "http://127.0.0.1:3081/gh/api/")
                    .put("readonly", true).toString();
        } catch (Throwable t) {
            return err(String.valueOf(t.getMessage()));
        }
    }

    private static int countFiles(File d) {
        int n = 0;
        File[] fs = d.listFiles();
        if (fs == null) return 0;
        for (File f : fs) {
            if (n > 5000) return n;
            n += f.isDirectory() ? countFiles(f) : 1;
        }
        return n;
    }

    /**
     * 把选中仓库展开成 App 私有目录里的**只读镜像**，让 DSH 的 file/bash 工具
     * 能直接读代码。用 zipball 单次下载（不是逐个文件拉 contents），
     * 重定向只跟随 GitHub 官方域，且只在 api.github.com 上带 Authorization。
     */
    public static String materialize(Context c, String fullName) {
        if (!enabled(c)) return err("AI 辅助 githup 未开启");
        if (token(c).isEmpty()) return err("还没有 GitHub 令牌");
        if (fullName == null || !fullName.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
            return err("仓库名格式不对（应为 owner/repo）");

        File dir = workspaceDir(c, fullName);
        try { deleteTree(dir); } catch (Throwable ignored) {}
        if (!dir.exists() && !dir.mkdirs()) return err("建不了工作区目录");

        HttpURLConnection conn = null;
        try {
            String url = API + "/repos/" + fullName + "/zipball";
            conn = openWithOfficialRedirect(c, url);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return err("下载仓库快照失败：HTTP " + code);
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            long total = 0;
            while ((n = in.read(b)) > 0) {
                total += n;
                if (total > MAX_ZIP) return err("仓库快照超过 60MB，已取消展开（可改用 /gh/api/ 按需读取）");
                buf.write(b, 0, n);
            }
            in.close();

            ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(buf.toByteArray()));
            ZipEntry e;
            int count = 0;
            String strip = null;
            while ((e = z.getNextEntry()) != null) {
                String path = e.getName();
                if (path.indexOf("..") >= 0 || path.startsWith("/")) { z.closeEntry(); continue; }
                if (strip == null) {
                    int slash = path.indexOf('/');
                    strip = slash > 0 ? path.substring(0, slash + 1) : "";
                }
                String rel = strip.isEmpty() ? path : (path.startsWith(strip) ? path.substring(strip.length()) : path);
                if (rel.isEmpty()) { z.closeEntry(); continue; }
                File out = new File(dir, rel);
                if (e.isDirectory()) {
                    if (!out.exists() && !out.mkdirs()) return err("建目录失败 " + rel);
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) return err("建父目录失败");
                    if (e.getSize() > MAX_ENTRY) { z.closeEntry(); continue; }
                    FileOutputStream fos = new FileOutputStream(out);
                    byte[] one = new byte[8192];
                    long wrote = 0;
                    while ((n = z.read(one)) > 0) {
                        wrote += n;
                        if (wrote > MAX_ENTRY) break;
                        fos.write(one, 0, n);
                    }
                    fos.close();
                }
                z.closeEntry();
                if (++count > MAX_ENTRIES) break;
            }
            z.close();

            // 给 AI 的说明书：告诉它这个目录是什么、怎么查 PR/Issue，以及令牌不在它手里
            File guide = new File(dir, "GITHUP.md");
            FileOutputStream g = new FileOutputStream(guide);
            g.write(("# githup 工作区\n\n"
                    + "本目录是 GitHub 仓库 `" + fullName + "` 的**只读镜像**，由 githup 客户端展开。\n\n"
                    + "- 用你现有的 file / bash 工具直接读这里的代码即可。\n"
                    + "- 需要 Issue、Pull Request、分支、提交等仓库元数据时，用 curl 访问：\n"
                    + "  `http://127.0.0.1:3081/gh/api/repos/" + fullName + "/issues`\n"
                    + "  `http://127.0.0.1:3081/gh/api/repos/" + fullName + "/pulls`\n"
                    + "  路径就是 GitHub REST API 去掉 `https://api.github.com` 之后的部分。\n"
                    + "- 这条通道**只读**：GET 之外的请求会被拒绝。\n"
                    + "- GitHub 令牌由 githup 客户端保管在设备 Keystore 里，**不在本目录、"
                    + "也不在你的环境变量中**，不要尝试寻找或输出它。\n"
                    + "- 镜像是快照，不会自动同步；要刷新请在设置里重新选择工作区。\n"
            ).getBytes("UTF-8"));
            g.close();

            setCurrent(c, fullName);
            return new JSONObject().put("ok", true)
                    .put("repo", fullName).put("path", dir.getAbsolutePath())
                    .put("entries", count)
                    .put("msg", "已把 " + fullName + " 展开为 AI 只读工作区（" + count + " 项）").toString();
        } catch (Throwable t) {
            return err("展开工作区失败：" + t.getMessage());
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /** 手动跟随 302：只在 GitHub 官方域继续，且只在 api.github.com 上附令牌。 */
    private static HttpURLConnection openWithOfficialRedirect(Context c, String url) throws Exception {
        for (int i = 0; i < 4; i++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            String host = new URL(url).getHost().toLowerCase(java.util.Locale.US);
            boolean official = host.equals("api.github.com") || host.endsWith(".github.com")
                    || host.equals("codeload.github.com");
            if (!official) throw new Exception("拒绝跳转到非 GitHub 域：" + host);
            if (host.equals("api.github.com")) {
                for (Map.Entry<String, String> e : headers(c).entrySet())
                    conn.setRequestProperty(e.getKey(), e.getValue());
            }
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null) throw new Exception("重定向没有 Location");
                url = loc;
                continue;
            }
            return conn;
        }
        throw new Exception("重定向次数过多");
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) { if (x.isDirectory()) deleteTree(x); else x.delete(); }
        f.delete();
    }

    private static String err(String msg) {
        try { return new JSONObject().put("ok", false).put("error", msg).toString(); }
        catch (Throwable t) { return "{\"ok\":false,\"error\":\"" + msg + "\"}"; }
    }
}
