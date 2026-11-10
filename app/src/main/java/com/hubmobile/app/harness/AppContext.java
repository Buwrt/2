package com.hubmobile.app.harness;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * 阶段 V：让 AI 在**不申请任何 Android 系统权限**的前提下，准确知道
 * githup 当前在哪个页面、正在看哪个仓库；并在用户明确同意后，把修改后的
 * 内容以「同名仓库」上传到当前登录账号。
 *
 * 关键认知：这不是「读屏幕」。githup 自己就是个 WebView SPA，
 * 它的路由 / 当前仓库 / 当前文件，本来就是 App 自己进程内写下的状态。
 * 前端 Router.render() 把它上报到原生层，本机桥 3081 再交给 DSH。
 * 全程不需要无障碍服务、悬浮窗、截屏、存储权限 —— 零权限。
 *
 * 但两件事仍然需要用户知情同意（设置页弹窗）：
 *   ① 让 AI「总结 / 修改」代码，内容必然离开设备发到 DeepSeek 云端；
 *   ② 「建立同名仓库」要用当前登录令牌调用 GitHub 写接口。
 * 所以 /app/clone-repo 与 /app/context 都以 consent() 为闸门。
 */
public final class AppContext {

    private static final String PREF = "ai_repo_ctx";
    private static final String K_CONSENT = "consent";
    private static final String K_PAGE = "page_json";

    private AppContext() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 用户是否在设置页点了「同意」；默认 false。 */
    public static boolean consent(Context c) { return p(c).getBoolean(K_CONSENT, false); }

    public static void setConsent(Context c, boolean on) {
        p(c).edit().putBoolean(K_CONSENT, on).commit();
    }

    /** 设置页副标题。 */
    public static String status(Context c) {
        return consent(c)
                ? "已同意 · AI 可零权限感知页面、读屏幕内容、替你操作软件并建同名库"
                : "未开启 · 首次展开 AI 面板也会弹确认；同意后才有感知与操作能力";
    }

    /**
     * 前端 Router.render() 每次渲染都会调一次。
     *
     * @param hash      形如 #/owner/repo/issues/12
     * @param title     顶栏标题（前端已算好）
     * @param routeJson parseHash 的结果 {name, ctx:{owner,repo,tab,ref,path,...}}
     */
    public static void report(Context c, String hash, String title, String routeJson) {
        try {
            String raw = hash == null ? "" : hash;
            String route = raw.startsWith("#") ? raw.substring(1) : raw;
            JSONObject o = new JSONObject();
            o.put("hash", raw);
            o.put("route", route);
            o.put("title", title == null ? "" : title);
            o.put("ts", System.currentTimeMillis());

            String owner = "", name = "", tab = "", path = "", pageName = "";
            try {
                JSONObject r = new JSONObject(routeJson == null ? "{}" : routeJson);
                pageName = r.optString("name", "");
                JSONObject cx = r.optJSONObject("ctx");
                if (cx != null) {
                    owner = cx.optString("owner", "");
                    name = cx.optString("repo", "");
                    tab = cx.optString("tab", "");
                    path = cx.optString("path", "");
                    o.put("ref", cx.optString("ref", ""));
                    o.put("number", cx.optString("number", ""));
                    o.put("sha", cx.optString("sha", ""));
                    o.put("tag", cx.optString("tag", ""));
                    JSONObject q = cx.optJSONObject("query");
                    if (q != null) o.put("query", q.toString());
                }
            } catch (Throwable ignored) {}

            /* 兜底：万一前端没给出结构化 ctx，直接从 hash 里认 owner/repo。
               一级路由（notifications / explore / search / profile / settings ...）
               不是仓库，别误认。 */
            if (owner.isEmpty() || name.isEmpty()) {
                String clean = route.split("\\?")[0];
                String[] segs = clean.split("/");
                int k = 0;
                StringBuilder sb = new StringBuilder();
                java.util.List<String> list = new java.util.ArrayList<String>();
                for (String x : segs) if (!x.isEmpty()) list.add(x);
                if (list.size() >= 2 && !RESERVED.contains(list.get(0))) {
                    owner = list.get(0);
                    name = list.get(1);
                    if (list.size() >= 3) tab = list.get(2);
                }
            }
            o.put("page_name", pageName);
            o.put("owner", owner);
            o.put("repo_name", name);
            o.put("repo", (!owner.isEmpty() && !name.isEmpty()) ? owner + "/" + name : "");
            o.put("tab", tab);
            o.put("path", path);
            p(c).edit().putString(K_PAGE, o.toString()).commit();

            /* 阶段 X：页面一变就在后台预抓「当前仓库速览」（GitHub REST，零 LLM）。
               这样用户展开 AI 面板时卡片是热的，/app/context 也一次就能把
               精简事实交给模型，不用它自己跑好几轮探测去反推用户看的是哪个库。 */
            final String full = o.optString("repo", "");
            if (!full.isEmpty() && consent(c)) {
                RepoBrief.fetch(c, full, new RepoBrief.Callback() {
                    @Override public void done(String repo, String card, String brief) {
                        /* 结果已进缓存；面板由 MainActivity.onPageChanged 直接刷新 */
                    }
                });
            }
        } catch (Throwable ignored) {}
    }

    private static final java.util.Set<String> RESERVED = new java.util.HashSet<String>(
            java.util.Arrays.asList("", "feed", "notifications", "explore", "search",
                    "profile", "settings", "downloads", "gists", "starred", "issues",
                    "pulls", "trending", "login", "about", "user", "repo", "changelog"));

    /** 当前页面里的仓库（owner/name），没有则空串。 */
    public static String currentRepo(Context c) {
        return page(c).optString("repo", "");
    }

    private static JSONObject page(Context c) {
        try { return new JSONObject(p(c).getString(K_PAGE, "{}")); }
        catch (Throwable t) { return new JSONObject(); }
    }

    /** GET /app/context —— AI 一 curl 就能准确回答「我现在在哪个页面、看哪个库」。 */
    public static String contextJson(Context c) {
        JSONObject o = new JSONObject();
        try {
            boolean ok = consent(c);
            o.put("ok", true);
            o.put("consent", ok);
            o.put("zero_permission", true);
            o.put("screen_pixels_available", false);
            o.put("accessibility_tree_available", false);
            o.put("note", "页面状态来自 githup 自己写下的路由记录，不是截图、不是控件树；"
                    + "因此不需要无障碍/悬浮窗/截屏/存储等任何 Android 系统权限。");
            if (!ok) {
                o.put("page", JSONObject.NULL);
                o.put("hint", "用户尚未在 githup「我的 → 设置」里开启「AI 感知与克隆」并同意风险弹窗。"
                        + "请如实告知尚未开启，禁止编造当前界面、仓库名或配置值。");
            } else {
                JSONObject pg = page(c);
                o.put("page", pg);
                String full = RepoBrief.cachedBrief(c, pg.optString("repo", ""));
                o.put("brief_cached", !full.isEmpty());
                /* 阶段 X+：brief 是**每轮注入**用的短版（≤600 字符）。
                   完整版（含 README 1200 字）只由 /app/summary 按需取，
                   不塞进每一轮上下文 —— 这是「省 token」的关键。 */
                o.put("brief", shortBrief(c, pg, full));
                o.put("summary_url", "http://127.0.0.1:3081/app/summary");
                o.put("note", o.optString("note") + " `brief` 是 App 已查好的当前页面事实，"
                        + "请直接采用，不要为了确认页面再跑探测工具；需要更多细节才 curl `summary_url`。");
                o.put("endpoints", new JSONArray()
                        .put("GET  /app/context     当前页面 / 当前仓库（零权限）")
                        .put("POST /app/clone-repo  把修改后的文件以同名仓库上传到当前登录账号")
                        .put("GET  /app/summary     当前仓库完整速览（含 README 摘要），按需取")
                        .put("GET  /app/ui/state    读 githup 当前屏幕渲染的内容（零权限）")
                        .put("POST /app/ui/act     直接替用户操作软件：导航/Star/点击/翻译/滚动…")
                        .put("GET  /app/ui/help     全部可用动作清单")
                        .put("ANY  /gh/api/<path>   GitHub REST 全方法透传（读代码做总结）"));
                o.put("warning", "总结或修改代码会把内容发到 DeepSeek 云端；"
                        + "建立同名仓库会用当前登录令牌调用 GitHub 写接口。此操作与作者无关。");
            }
        } catch (Throwable t) {
            return err("读取页面状态失败：" + t.getMessage());
        }
        return o.toString();
    }

    /* ==================== 阶段 X+：短版注入 + 按需完整速览 ==================== */

    /** 每轮注入文本的硬上限。宁可少给，也不让模型为「确认页面」多烧几轮。 */
    private static final int BRIEF_MAX = 600;

    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    /**
     * 短版上下文：路由 + 仓库 + 一行元数据 + 「别探测」的指令。
     * 只读本地缓存，绝不联网；插件每一轮拉的就是这段。
     */
    private static String shortBrief(Context c, JSONObject pg, String full) {
        String repo = pg.optString("repo", "");
        String hash = pg.optString("hash", "#/");
        String title = pg.optString("title", "");
        String tab = pg.optString("tab", "");
        String path = pg.optString("path", "");
        String card = "";
        if (!full.isEmpty()) {
            try { card = new JSONObject(full).optString("card", ""); } catch (Throwable ignored) {}
        }
        StringBuilder b = new StringBuilder();
        b.append("[githup 当前页面 · App 直接提供，不是截图/控件树，无需任何系统权限]\n");
        b.append("页面：").append(clip(hash, 90));
        if (!title.isEmpty()) b.append("（").append(clip(title, 50)).append("）");
        b.append("\n");
        if (!repo.isEmpty()) {
            b.append("仓库：").append(repo).append("\n");
            if (!card.isEmpty()) b.append("速览：").append(clip(card, 160)).append("\n");
            if (!tab.isEmpty() || !path.isEmpty())
                b.append("位置：").append(clip(tab + "/" + path, 90)).append("\n");
        } else {
            b.append("当前不在仓库页。\n");
        }
        b.append("这是 App 已查好的事实，**直接采用**。不要执行 gh CLI、不要检查环境变量、")
         .append("不要列全库或逐文件读（那些轮次会白烧 token）。\n")
         .append("辅助软件本体：GET /app/ui/state 读当前屏幕渲染内容；")
         .append("POST /app/ui/act 直接替用户导航/Star/切换翻译（清单 /app/ui/help）。\n")
         .append("需要总结/修改：先 curl 'http://127.0.0.1:3081/app/summary")
         .append(repo.isEmpty() ? "" : "?repo=" + repo)
         .append("' 拿完整速览；只有确实需要细节才读单个文件。");
        return clip(b.toString(), BRIEF_MAX);
    }

    /**
     * GET /app/summary?repo=owner/name —— 按需给完整速览（RepoBrief 已算好的
     * 元数据 + README 前 1200 字，≤2KB）。没缓存时给出明确提示而不是让模型
     * 自己跑去翻整个仓库。全程不调 LLM。
     */
    public static String summary(Context c, String repo) {
        if (!consent(c)) return err("用户未同意：请先在 我的 → 设置 打开「AI 感知与克隆」");
        if (repo == null || repo.isEmpty()) repo = page(c).optString("repo", "");
        if (repo.isEmpty()) return err("当前不在仓库页；请用 ?repo=owner/name 指定");
        String full = RepoBrief.cachedBrief(c, repo);
        if (full.isEmpty()) {
            /* org.json.JSONException 在 Android 上是 checked exception，
               不能像上面 cloneRepo 那样裸链式 put（CI 编译报
               "unreported exception JSONException"）。这里包一层再返回。 */
            try {
                return new JSONObject().put("ok", true).put("repo", repo)
                        .put("cached", false)
                        .put("text", "该仓库速览尚未生成（通常会在你打开仓库页几秒内自动完成）。"
                                + "请稍后再试一次本端点；不要改为逐文件读取整个仓库。")
                        .toString();
            } catch (Throwable t) { return err("速览暂不可用"); }
        }
        try {
            JSONObject o = new JSONObject(full);
            return new JSONObject().put("ok", true).put("repo", repo)
                    .put("cached", true).put("card", o.optString("card", ""))
                    .put("text", clip(o.optString("brief", ""), 2200)).toString();
        } catch (Throwable t) { return err("速览解析失败：" + t.getMessage()); }
    }

    /**
     * POST /app/clone-repo —— 把 AI 改好的文件，以**相同名称**建库上传到
     * 「当前登录账号」名下；**不改动原仓库**。
     *
     * body: {"repo":"owner/name", "files":[{"path":"a.md","content":"..."}],
     *        "description":"...", "private":false}
     */
    public static String cloneRepo(Context c, JSONObject req) {
        if (!consent(c)) return err("用户未同意：请先到 我的 → 设置 打开「AI 感知与克隆」并确认风险弹窗");
        if (!GhBridge.enabled(c)) return err("还没有 GitHub 令牌：请先在 githup 里登录");
        String full = req.optString("repo", "");
        JSONArray files = req.optJSONArray("files");
        if (full.isEmpty() || files == null || files.length() == 0)
            return err("需要 repo（owner/name）和非空 files 数组");
        String[] pp = full.split("/");
        if (pp.length < 2) return err("repo 格式应为 owner/name");
        final String name = pp[1];
        if (!name.matches("[A-Za-z0-9._-]{1,100}")) return err("同名仓库名不合法");
        if (files.length() > 50) return err("单次最多 50 个文件");

        try {
            /* ① 当前登录账号 —— 权限完全跟随登录令牌，不写死任何身份 */
            JSONObject me = new JSONObject(GhBridge.proxy(c, "GET", "/user", null));
            if (!me.optBoolean("ok", false)) return err("读取当前登录账号失败：" + describe(me));
            JSONObject d = me.optJSONObject("data");
            String login = d == null ? "" : d.optString("login", "");
            if (login.isEmpty()) return err("当前未登录 GitHub");

            /* ② 在自己的账号下建立同名仓库。已存在同名时不覆盖、直接往里写。 */
            JSONObject create = new JSONObject();
            create.put("name", name);
            create.put("description", req.optString("description",
                    "由 githup AI 创建，与 " + full + " 同名"));
            create.put("private", req.optBoolean("private", false));
            create.put("auto_init", false);
            JSONObject cr = new JSONObject(GhBridge.proxy(c, "POST", "/user/repos", create.toString()));
            boolean created = cr.optBoolean("ok", false);
            if (!created && cr.optInt("status", 0) != 422)
                return err("建立同名仓库失败（HTTP " + cr.optInt("status", 0) + "）：" + describe(cr));

            /* ③ 逐个写文件。文件已存在时补 sha 再写，避免 422 覆盖失败。 */
            int written = 0;
            JSONArray errs = new JSONArray();
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                if (f == null) continue;
                String path = f.optString("path", "");
                String content = f.optString("content", "");
                if (path.isEmpty() || path.contains("..") || path.startsWith("/")) {
                    errs.put(path + "：非法路径"); continue;
                }
                if (content.getBytes(StandardCharsets.UTF_8).length > 900_000) {
                    errs.put(path + "：超过 900KB，跳过"); continue;
                }
                String b64 = Base64.encodeToString(
                        content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                JSONObject put = new JSONObject();
                put.put("message", "githup AI: " + path);
                put.put("content", b64);
                String api = "/repos/" + login + "/" + name + "/contents/" + encPath(path);
                JSONObject r = new JSONObject(GhBridge.proxy(c, "PUT", api, put.toString()));
                if (!r.optBoolean("ok", false)) {
                    /* 已存在同名文件：拿 sha 再写一次 */
                    JSONObject get = new JSONObject(GhBridge.proxy(c, "GET", api, null));
                    JSONObject gd = get.optJSONObject("data");
                    String sha = gd == null ? "" : gd.optString("sha", "");
                    if (!sha.isEmpty()) {
                        put.put("sha", sha);
                        r = new JSONObject(GhBridge.proxy(c, "PUT", api, put.toString()));
                    }
                }
                if (r.optBoolean("ok", false)) written++;
                else errs.put(path + "：" + describe(r));
            }

            JSONObject out = new JSONObject();
            out.put("ok", written > 0 || errs.length() == 0);
            out.put("created_new_repo", created);
            out.put("login", login);
            out.put("repo", login + "/" + name);
            out.put("source_repo", full);
            out.put("html_url", "https://github.com/" + login + "/" + name);
            out.put("files_written", written);
            out.put("errors", errs);
            out.put("note", "只写入当前登录账号下的同名仓库，原仓库 " + full + " 未被修改。");
            return out.toString();
        } catch (Throwable t) {
            return err("克隆失败：" + t.getMessage());
        }
    }

    private static String encPath(String s) {
        StringBuilder b = new StringBuilder();
        for (String part : s.split("/", -1)) {
            if (b.length() > 0) b.append('/');
            try { b.append(java.net.URLEncoder.encode(part, "UTF-8").replace("+", "%20")); }
            catch (Throwable t) { b.append(part); }
        }
        return b.toString();
    }

    private static String describe(JSONObject o) {
        if (o == null) return "";
        JSONObject d = o.optJSONObject("data");
        String m = d == null ? "" : d.optString("message", "");
        if (m.isEmpty()) m = o.optString("error", "");
        if (m.isEmpty()) m = o.optString("data", "");
        return m.length() > 240 ? m.substring(0, 240) : m;
    }

    private static String err(String msg) {
        try { return new JSONObject().put("ok", false).put("error", msg).toString(); }
        catch (Throwable t) { return "{\"ok\":false,\"error\":\"" + msg + "\"}"; }
    }
}
