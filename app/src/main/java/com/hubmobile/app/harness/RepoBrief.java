package com.hubmobile.app.harness;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 阶段 X：把「当前仓库」在**原生层**变成一条已知事实。
 *
 * 为什么需要它：上一轮只给了 AI 一个 `GET /app/context` 端点 + 工作区说明，
 * 但 LLM 是被动的 —— 用户打开 gbataille/gitHUD，它不会自己去 curl，
 * 于是要么答「我看不到」，要么开始跑 gh CLI / 列环境变量 / 逐个翻文件来
 * 反推「用户到底在看哪个库」。那几轮无用工具调用就是「特别费 token」的来源。
 *
 * 这里改成：App 自己用当前登录令牌打 **GitHub REST**（不花 DeepSeek 一分钱），
 * 拿到仓库元数据 + README，压成 ≤500 字的精简事实，缓存 10 分钟。
 * 然后：
 *   · AI 面板顶部常驻「当前仓库卡」——用户一眼可见，零 LLM token
 *   · 「带入对话」把这条事实预填进 DSH 输入框 —— 模型一轮就能答，
 *     不用再探测，N 轮压缩成 1 轮
 *   · /app/context 顺带返回 brief —— AI 真要 curl 也一次拿全
 *
 * 全程不走 LLM，只是 GitHub 的普通 GET。
 */
public final class RepoBrief {

    private static final String PREF = "repo_brief";
    private static final long TTL = 10 * 60 * 1000L;
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    public interface Callback { void done(String repo, String card, String brief); }

    private RepoBrief() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static String k(String repo) { return "b_" + repo.replace('/', '_'); }
    private static String kt(String repo) { return "t_" + repo.replace('/', '_'); }

    /** 只读缓存，不联网。给 /app/context 用，避免阻塞本地桥线程。 */
    public static String cachedBrief(Context c, String repo) {
        if (repo == null || repo.isEmpty()) return "";
        long ts = p(c).getLong(kt(repo), 0L);
        if (System.currentTimeMillis() - ts > TTL) return "";
        return p(c).getString(k(repo), "");
    }

    public static String cachedCard(Context c, String repo) {
        String b = cachedBrief(c, repo);
        if (b.isEmpty()) return "";
        try { return new JSONObject(b).optString("card", ""); }
        catch (Throwable t) { return ""; }
    }

    /** 页面变化时调；命中缓存直接回调，否则异步抓一次。 */
    public static void fetch(final Context c, final String repo, final Callback cb) {
        if (repo == null || repo.isEmpty()) { cb.done(repo, "", ""); return; }
        final String cached = cachedBrief(c, repo);
        if (!cached.isEmpty()) {
            try {
                JSONObject o = new JSONObject(cached);
                cb.done(repo, o.optString("card", ""), o.optString("brief", ""));
            } catch (Throwable ignored) {}
            return;
        }
        POOL.execute(new Runnable() {
            @Override public void run() {
                String result = build(c, repo);
                if (!result.isEmpty())
                    p(c).edit().putString(k(repo), result)
                            .putLong(kt(repo), System.currentTimeMillis()).commit();
                try {
                    JSONObject o = new JSONObject(result.isEmpty() ? "{}" : result);
                    cb.done(repo, o.optString("card", ""), o.optString("brief", ""));
                } catch (Throwable t) { cb.done(repo, "", ""); }
            }
        });
    }

    private static String build(Context c, String repo) {
        if (!GhBridge.enabled(c)) return "";
        try {
            JSONObject meta = data(GhBridge.proxy(c, "GET", "/repos/" + repo, null));
            String full = meta.optString("full_name", repo);
            String desc = meta.optString("description", "");
            String lang = meta.optString("language", "");
            String branch = meta.optString("default_branch", "");
            int stars = meta.optInt("stargazers_count", 0);
            int forks = meta.optInt("forks_count", 0);
            boolean archived = meta.optBoolean("archived", false);
            String license = "";
            try { license = meta.optJSONObject("license").optString("spdx_id", ""); }
            catch (Throwable ignored) {}

            /* README：GitHub 返回 base64。只取前 1200 字，避免把整篇塞进上下文。 */
            String readme = "";
            try {
                JSONObject rd = data(GhBridge.proxy(c, "GET",
                        "/repos/" + repo + "/readme", null));
                String b64 = rd.optString("content", "");
                if (!b64.isEmpty()) {
                    byte[] raw = Base64.decode(b64.replace("\n", ""), Base64.DEFAULT);
                    String txt = new String(raw, StandardCharsets.UTF_8);
                    txt = txt.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "")   // 图片
                             .replaceAll("<[^>]+>", "")                       // HTML
                             .replaceAll("(?m)^\\s*#+\\s*", "")
                             .replaceAll("\\n{3,}", "\n\n").trim();
                    readme = txt.length() > 1200 ? txt.substring(0, 1200) + "…" : txt;
                }
            } catch (Throwable ignored) {}

            /* 卡片：一行，用户扫一眼就知道 AI 认没认出当前库（零 LLM token）。 */
            StringBuilder card = new StringBuilder(full);
            if (!lang.isEmpty()) card.append(" · ").append(lang);
            card.append(" · ★").append(stars);
            if (!branch.isEmpty()) card.append(" · ").append(branch);
            if (archived) card.append(" · 已归档");

            /* 精简事实：给模型当「已知事实」，明确禁止再去探测。 */
            StringBuilder brief = new StringBuilder();
            brief.append("【githup 当前页面已知事实 · 不要重复探测】\n");
            brief.append("用户正在 githup 客户端里查看仓库 `").append(full).append("`。\n");
            if (!desc.isEmpty()) brief.append("简介：").append(desc).append("\n");
            brief.append("主语言：").append(lang.isEmpty() ? "(未标注)" : lang)
                 .append("｜star ").append(stars).append("｜fork ").append(forks)
                 .append("｜默认分支 ").append(branch.isEmpty() ? "(未知)" : branch);
            if (archived) brief.append("｜已归档");
            if (!license.isEmpty() && !"NOASSERTION".equals(license))
                brief.append("｜许可证 ").append(license);
            brief.append("\n");
            if (!readme.isEmpty()) brief.append("README 开头：\n").append(readme).append("\n");
            brief.append("\n这是 App 已经查好的事实。请**直接**基于它回答，")
                 .append("不要执行 `gh` CLI、不要检查环境变量、不要为了确认页面去列全库或逐个读文件")
                 .append("（那些轮次会白白消耗 token）。需要更多细节时才用 ")
                 .append("`/gh/api/repos/").append(full).append("/contents/<path>` 精确取单个文件。\n");

            return new JSONObject()
                    .put("card", card.toString())
                    .put("brief", brief.toString())
                    .put("repo", full)
                    .put("ts", System.currentTimeMillis())
                    .toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static JSONObject data(String proxyJson) {
        try {
            JSONObject o = new JSONObject(proxyJson);
            if (!o.optBoolean("ok", false)) return new JSONObject();
            JSONObject d = o.optJSONObject("data");
            return d == null ? new JSONObject() : d;
        } catch (Throwable t) { return new JSONObject(); }
    }
}
