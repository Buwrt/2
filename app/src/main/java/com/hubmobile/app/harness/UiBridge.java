package com.hubmobile.app.harness;

import android.app.Activity;
import android.os.Looper;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 阶段 Y：AI 与 githup 软件本体之间的**双向通道**。
 *
 * 之前十轮做的都是「把仓库数据 / 路由喂给 AI」，AI 始终只是旁边一个聊天窗：
 * 它读不到 githup 当前 WebView 里已经渲染出来的内容，也**不能替用户操作这个
 * 软件**（导航、Star、切 tab、翻译…）。LocalBridgeServer 里原先既没有
 * MainActivity 引用，也没有任何 evaluateJavascript 调用 —— 这就是用户说的
 * 「根本没真正彻底辅助我的软件」。
 *
 * 这里让 3081 桥可以：
 *   · eval(js)  —— 在 githup 主 WebView 里同步执行受限 JS 并取回结果
 *                  （读 DOM 摘要 = 当前屏幕真正渲染的内容，零系统权限）
 *   · toast(msg) —— 动作落到界面上时给用户一条可见回执
 *
 * WebView 的 evaluateJavascript 必须跑在主线程，且回调是异步的；桥线程用
 * CountDownLatch 等 2.5 秒，超时返回 null，绝不把 3081 拖死。
 */
public final class UiBridge {

    private static volatile WeakReference<Activity> sAct = new WeakReference<Activity>(null);

    private UiBridge() {}

    public static void bind(Activity a) { sAct = new WeakReference<Activity>(a); }
    public static void unbind(Activity a) {
        Activity cur = sAct.get();
        if (cur == a) sAct = new WeakReference<Activity>(null);
    }

    private interface Eval { String run(WebView w, ValueCallback<String> cb); }

    /** 在主 WebView 里执行 js，返回回调的字符串（已去掉 WebView 外层 JSON 引号）。 */
    public static String eval(final String js) {
        final Activity a = sAct.get();
        if (a == null || js == null || js.isEmpty()) return null;
        if (Looper.myLooper() == Looper.getMainLooper()) return null;  // 防死锁
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> out = new AtomicReference<String>(null);
        try {
            a.runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        WebView w = findWeb(a);
                        if (w == null) { latch.countDown(); return; }
                        w.evaluateJavascript(js, new ValueCallback<String>() {
                            @Override public void onReceiveValue(String value) {
                                out.set(unquote(value));
                                latch.countDown();
                            }
                        });
                    } catch (Throwable t) { latch.countDown(); }
                }
            });
            if (!latch.await(2500, TimeUnit.MILLISECONDS)) return null;
        } catch (Throwable ignored) { return null; }
        return out.get();
    }

    /** 动作回执，让用户亲眼看到「AI 真的在替我操作 githup」。 */
    public static void toast(final String msg) {
        final Activity a = sAct.get();
        if (a == null || msg == null) return;
        try {
            a.runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        Toast.makeText(a.getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
                        /* 同时进底座页面：AI 面板展开时用户看的是 AI 那一层，
                           系统 toast 容易被忽略；UI.toast 在底座 DOM 里也留一条。 */
                        WebView w = findWeb(a);
                        if (w != null) w.evaluateJavascript(
                                "(function(){try{if(window.UI&&UI.toast)UI.toast("
                                + org.json.JSONObject.quote(msg) + ",2600);}catch(e){}})()", null);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    /** 从 Activity 的 contentRoot 里找主 WebView；不依赖具体字段名。 */
    private static WebView findWeb(Activity a) {
        try {
            /* 精确取底座 WebView：AI 面板也有一个 WebView，遍历会取错。 */
            if (a instanceof com.hubmobile.app.MainActivity) {
                WebView w = ((com.hubmobile.app.MainActivity) a).hostWebView();
                if (w != null) return w;
            }
            android.view.View root = a.findViewById(android.R.id.content);
            return findWebRec(root, 0);
        } catch (Throwable t) { return null; }
    }

    private static WebView findWebRec(android.view.View v, int depth) {
        if (v == null || depth > 12) return null;
        if (v instanceof WebView) return (WebView) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                WebView w = findWebRec(g.getChildAt(i), depth + 1);
                if (w != null) return w;
            }
        }
        return null;
    }

    /**
     * WebView 对「返回字符串」的表达式会把结果再做一次 JSON 字符串转义，
     * 例如 JS 返回 '{"a":1}'，回调收到的是 "\"{\\\"a\\\":1}\""。
     * 这里剥掉最外层引号还原成可直接解析的 JSON 文本。
     */
    private static String unquote(String v) {
        if (v == null || "null".equals(v)) return null;
        String s = v.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            try { return new JSONArray("[" + s + "]").getString(0); }
            catch (Throwable ignored) { return s.substring(1, s.length() - 1); }
        }
        return s;
    }
}
