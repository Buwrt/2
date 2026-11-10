package com.hubmobile.app.harness;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 阶段 Y：让 DeepSeek agent **真正辅助 githup 这个软件本体**，而不只是
 * 「旁边一个能聊 GitHub API 的窗口」。
 *
 * 之前所有能力都是单向的：前端把路由上报给 3081，AI 拿到「你在看哪个库」，
 * 然后自己调 GitHub REST。但它：
 *   · 读不到 githup WebView 里**当前已经渲染出来的内容**（Issue 正文、README、
 *     文件列表、按钮、顶栏）——这些是 App 自己的 DOM，零系统权限就能读；
 *   · **不能替用户操作软件**：不能导航、不能 Star、不能切 tab、不能翻译、不能返回。
 * 用户说「根本没彻底辅助我的软件」，缺的就是这条反向通道。
 *
 * 本类提供两个端点（经 LocalBridgeServer 的 /app/ui/* 暴露）：
 *   GET  /app/ui/state → 当前屏幕渲染内容摘要（≤2KB，不花 LLM token 探测）
 *   POST /app/ui/act   → 白名单动作，在底座 WebView 里执行，并给用户可见回执
 *
 * 所有动作都走底座已有的 window.Router / window.API / window.UI 与页面按钮，
 * 不绕过登录令牌、不注入任意 JS 字符串、不申请任何 Android 系统权限。
 * 闸门仍是 AppContext.consent() —— 用户没点「同意」一律拒绝。
 */
public final class AppUi {

    private AppUi() {}

    /* ---------------- 读取：当前屏幕真正渲染了什么 ---------------- */

    private static final String STATE_JS =
            "(function(){try{"
            + "var hash=location.hash||'#/';"
            + "var t=document.querySelector('#appbar-title,.appbar-title,[data-title]');"
            + "var title=t?((t.innerText||t.textContent||'').trim()):(document.title||'');"
            + "var host=document.getElementById('view')||document.body;"
            + "var txt=(host.innerText||'').replace(/\\n{3,}/g,'\\n\\n').slice(0,1400);"
            + "var bs=[],ns=document.querySelectorAll('#view button,#view a,#appbar-actions button,#appbar-actions a');"
            + "for(var i=0;i<ns.length&&bs.length<18;i++){"
            + "  var x=(ns[i].innerText||'').trim().replace(/\\s+/g,' ').slice(0,36);"
            + "  if(x&&bs.indexOf(x)<0)bs.push(x);}"
            + "return JSON.stringify({hash:String(hash).slice(0,160),"
            + " title:String(title).slice(0,80),text:txt,buttons:bs,"
            + " scroll:(host.scrollTop||0),url:location.href.slice(0,200)});"
            + "}catch(e){return JSON.stringify({error:String(e)})}})()";

    /** GET /app/ui/state —— 读的是 App 自己 WebView 的 DOM，不是截图/无障碍。 */
    public static String state(Context c) {
        if (!AppContext.consent(c)) return err("用户尚未同意：请先在 AI 面板的风险弹窗点「同意」");
        String raw = UiBridge.eval(STATE_JS);
        if (raw == null || raw.isEmpty()) return err("底座界面尚未就绪（请打开 githup 主界面后再试）");
        try {
            JSONObject o = new JSONObject(raw);
            o.put("ok", true);
            o.put("zero_permission", true);
            o.put("note", "这是 githup 自己 WebView 当前渲染的 DOM 摘要（文本/按钮/路由），"
                    + "不是截图、不是控件树；要操作请用 POST /app/ui/act。");
            return o.toString();
        } catch (Throwable t) {
            return okText("raw", clip(raw, 1600));
        }
    }

    /* ---------------- 动作：替用户操作软件 ---------------- */

    /** 动作清单，同时给 /app/ui/help 用。 */
    public static String help() {
        JSONArray a = new JSONArray()
                .put("navigate  {\"arg\":\"/owner/repo/issues\"}  在 githup 内跳到指定页面")
                .put("open_repo {\"arg\":\"owner/repo\"}          打开仓库页")
                .put("search    {\"arg\":\"关键词\"}                搜索")
                .put("click     {\"arg\":\"按钮文字\"}              点当前页可见按钮/链接")
                .put("star / watch / fork                      切换 Star / 关注 / Fork")
                .put("translate                                切换整页翻译")
                .put("scroll    {\"arg\":\"600\"}                   滚动（默认向下 600）")
                .put("refresh / back                            刷新 / 返回")
                .put("theme     {\"arg\":\"dark\"}                   切换主题")
                .put("copy      {\"arg\":\"文本\"}                   写入系统剪贴板")
                .put("theme、star 等写操作都走底座原本的 UI 与登录令牌，令牌不交给 AI");
        try {
            return new JSONObject().put("ok", true)
                    .put("state", "GET /app/ui/state")
                    .put("act", "POST /app/ui/act  {\"action\":\"...\",\"arg\":\"...\"}")
                    .put("actions", a)
                    .put("consent_required", true).toString();
        } catch (Throwable t) { return err("帮助生成失败"); }
    }

    /** POST /app/ui/act —— 白名单执行，拒绝一切任意 JS。 */
    public static String act(Context c, JSONObject req) {
        if (!AppContext.consent(c)) return err("用户尚未同意：请先在 AI 面板的风险弹窗点「同意」");
        String action = req.optString("action", "");
        String arg = req.optString("arg", "");
        if (action.isEmpty()) return err("需要 action");
        if (arg.length() > 200) return err("arg 过长（上限 200 字符）");
        if (looksDangerous(arg)) return err("arg 含被禁止的内容");

        String q = JSONObject.quote(arg);   // 安全 JS 字符串字面量
        String js = null, label = action;

        if (action.equals("navigate")) {
            if (!arg.startsWith("/")) return err("navigate 的 arg 必须是以 / 开头的内部路由");
            js = "(function(){try{window.Router.go(" + q + ");return 'navigated '+location.hash;}catch(e){return 'ERR '+e}})()";
            label = "已打开 " + arg;
        } else if (action.equals("open_repo")) {
            if (!arg.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")) return err("repo 格式应为 owner/name");
            js = "(function(){try{window.Router.go(" + JSONObject.quote("/" + arg)
                    + ");return 'opened '+location.hash;}catch(e){return 'ERR '+e}})()";
            label = "已打开仓库 " + arg;
        } else if (action.equals("search")) {
            js = "(function(){try{window.Router.go('/search?q='+encodeURIComponent(" + q
                    + "));return 'searched';}catch(e){return 'ERR '+e}})()";
            label = "已搜索 " + arg;
        } else if (action.equals("refresh")) {
            js = "(function(){try{window.Router.reload();return 'reloaded';}catch(e){return 'ERR '+e}})()";
            label = "已刷新页面";
        } else if (action.equals("back")) {
            js = "(function(){try{return (window.App&&App.handleBack&&App.handleBack())?'back':'at root';}"
                    + "catch(e){return 'ERR '+e}})()";
            label = "已返回上一级";
        } else if (action.equals("click")) {
            if (arg.isEmpty()) return err("click 需要按钮文字");
            js = "(function(){var t=" + q + ";"
                    + "var ns=document.querySelectorAll('#view button,#view a,#appbar-actions button,#appbar-actions a');"
                    + "for(var i=0;i<ns.length;i++){var x=(ns[i].innerText||'').trim();"
                    + "if(x&&(x.indexOf(t)>=0||t.indexOf(x)>=0)){ns[i].click();return 'clicked: '+x.slice(0,60);}}"
                    + "return 'not found: '+t;})()";
            label = "已点击「" + arg + "」";
        } else if (action.equals("star") || action.equals("watch") || action.equals("fork")) {
            String id = action.equals("star") ? "btn-star" : action.equals("watch") ? "btn-watch" : "btn-fork";
            js = "(function(){var b=document.getElementById(" + JSONObject.quote(id) + ");"
                    + "if(!b)return 'not on repo page';b.click();return '"+id+" clicked';})()";
            label = "已" + (action.equals("star") ? "切换 Star" : action.equals("watch") ? "切换关注" : "触发 Fork");
        } else if (action.equals("translate")) {
            js = "(function(){var b=document.querySelector('#btn-translate,[data-translate-toggle],.translate-toggle');"
                    + "if(!b){var all=document.querySelectorAll('#appbar-actions button');"
                    + "for(var i=0;i<all.length;i++){var s=(all[i].getAttribute('aria-label')||'')+(all[i].title||'');"
                    + "if(/翻译|translate|globe|地球/i.test(s)){b=all[i];break;}}}"
                    + "if(!b)return 'translate button not found';b.click();return 'translate toggled';})()";
            label = "已切换整页翻译";
        } else if (action.equals("scroll")) {
            int dy = 600;
            try { dy = Integer.parseInt(arg.isEmpty() ? "600" : arg); } catch (Throwable ignored) {}
            if (dy < -4000 || dy > 4000) return err("滚动范围 ±4000");
            js = "(function(){var h=document.getElementById('view')||document.scrollingElement;"
                    + "h.scrollTop+= " + dy + ";return 'scroll '+h.scrollTop;})()";
            label = "已滚动";
        } else if (action.equals("theme")) {
            js = "(function(){try{var A=window.App;if(!A)return 'no App';"
                    + "if(A.setTheme)A.setTheme(" + q + ");else if(A.setThemeMode)A.setThemeMode(" + q + ");"
                    + "else return 'theme api unavailable';return 'theme set';}catch(e){return 'ERR '+e}})()";
            label = "已切换主题";
        } else if (action.equals("copy")) {
            if (arg.isEmpty()) return err("copy 需要文本");
            try {
                ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("githup AI", arg));
                UiBridge.toast("AI 助手：已复制到剪贴板");
                return new JSONObject().put("ok", true).put("action", "copy")
                        .put("result", "copied").toString();
            } catch (Throwable t) { return err("复制失败：" + t.getMessage()); }
        } else {
            return err("不支持的动作：" + action + "；可用动作见 GET /app/ui/help");
        }

        String result = UiBridge.eval(js);
        if (result == null) result = "底座界面尚未就绪";
        /* 关键：给一条用户肉眼可见的回执，证明 AI 真的在替他操作软件 */
        UiBridge.toast("AI 助手：" + label);
        try {
            return new JSONObject().put("ok", true).put("action", action)
                    .put("arg", arg).put("result", clip(result, 300))
                    .put("toast", label).toString();
        } catch (Throwable t) { return err("执行结果解析失败"); }
    }

    private static boolean looksDangerous(String s) {
        String v = s.toLowerCase(java.util.Locale.US);
        return v.contains("javascript:") || v.contains("data:") || v.contains("<script")
                || v.contains("\n") || v.contains("\r") || v.contains("://")
                || v.contains("eval(") || v.contains("function(");
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static String okText(String k, String v) {
        try { return new JSONObject().put("ok", true).put(k, v).toString(); }
        catch (Throwable t) { return "{\"ok\":true}"; }
    }

    private static String err(String m) {
        try { return new JSONObject().put("ok", false).put("error", m).toString(); }
        catch (Throwable t) { return "{\"ok\":false}"; }
    }
}
