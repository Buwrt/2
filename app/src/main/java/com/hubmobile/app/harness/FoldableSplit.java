package com.hubmobile.app.harness;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;

import com.hubmobile.app.AiPanel;

/**
 * 阶段 AG：折叠屏展开 / 大屏横持 —— 右半 githup 本体，左半 AI 全屏。
 *
 * 判定口径沿用底座 1.2.20 已建立的「最小宽度」权威判据
 * （app.js applyDeviceClass：smallestScreenWidthDp >= 600 视为平板/折叠展开）：
 *   · smallestScreenWidthDp >= 600  → 大屏（折叠内屏 / 平板），手机横屏不算
 *   · screenWidthDp >= 720          → 当前宽度足够左右各放一栏
 * 合上折叠屏时 smallestScreenWidthDp 回落 320–400，自动退出分栏。
 *
 * 为什么不用 Jetpack WindowManager：本工程 dependencies 为空，
 * 且 Manifest 已声明 configChanges 包含 orientation|screenSize|screenLayout|
 * smallestScreenSize|density，屏幕开合/旋转不重建 Activity，
 * onConfigurationChanged 直接重排即可，WebView 登录态与滚动位置零丢失。
 *
 * 布局做法（不换窗口、不新建 Activity，保住单图标同屏）：
 *   · 底座 WebView → FrameLayout 右半（Gravity.END，宽 = rootW/2）
 *   · AiPanel 面板 → FrameLayout 左半（Gravity.START，宽 = rootW/2，全高）
 *     由 AiPanel.setSplitMode() 把 panelLp() 固定成左半，并强制展开、
 *     隐藏悬浮球、禁止收起
 *   · 中间一条 1dp 竖分隔线
 * 退出分栏：两边恢复 MATCH_PARENT 叠加抽屉，AI 收起、悬浮球按设置显隐。
 */
public final class FoldableSplit {

    private static final String TAG_DIVIDER = "githup_split_divider";
    private static FoldableSplit sInstance;

    private final Activity act;
    private final FrameLayout root;
    private final WebView web;
    private final AiPanel ai;
    private boolean on = false;
    private int paneW = 0;
    private View divider;
    private boolean attached = true;

    /** 布局变化监听：自由窗口 / 分屏拖拽改变宽度时重算半屏宽。 */
    private final View.OnLayoutChangeListener layoutListener =
            new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                    int ol, int ot, int or, int ob) {
                    if (!attached || !on) return;
                    int w = (r - l) / 2;
                    if (w > 0 && w != paneW) apply(true);
                }
            };

    private FoldableSplit(Activity a, FrameLayout r, WebView w, AiPanel p) {
        act = a; root = r; web = w; ai = p;
    }

    public static void attach(Activity a, FrameLayout r, WebView w, AiPanel p) {
        if (a == null || r == null || w == null || p == null) return;
        if (sInstance != null) sInstance.detach();
        FoldableSplit s = new FoldableSplit(a, r, w, p);
        sInstance = s;
        try { r.addOnLayoutChangeListener(s.layoutListener); } catch (Throwable ignored) {}
        /* 首帧 root 还没测量，post 一帧再算半屏宽。 */
        r.post(new Runnable() {
            @Override public void run() {
                FoldableSplit cur = sInstance;
                if (cur != null && cur.attached) cur.refresh();
            }
        });
    }

    /** 屏幕开合 / 旋转 / 回到前台：重新判定并按需切换。 */
    public static void refresh() {
        FoldableSplit s = sInstance;
        if (s == null || !s.attached) return;
        s.root.post(new Runnable() {
            @Override public void run() {
                FoldableSplit cur = sInstance;
                if (cur != null && cur.attached) cur.apply(false);
            }
        });
    }

    public static boolean isSplit() { return sInstance != null && sInstance.on; }

    public static void detach() {
        FoldableSplit s = sInstance;
        if (s == null) return;
        s.attached = false;
        try { s.root.removeOnLayoutChangeListener(s.layoutListener); } catch (Throwable ignored) {}
        sInstance = null;
    }

    private static boolean bigScreen(Configuration c) {
        if (c == null) return false;
        int sw = c.smallestScreenWidthDp;
        int w = c.screenWidthDp;
        return sw >= 600 && w >= 720;
    }

    /** @param fromLayout true=仅宽度变了（已确定仍是分栏），false=需要重新判定。 */
    private void apply(boolean fromLayout) {
        if (!attached || act.isFinishing()) return;
        Configuration c = act.getResources().getConfiguration();
        boolean want = fromLayout ? on : bigScreen(c);
        int totalW = root.getWidth() > 0
                ? root.getWidth()
                : act.getResources().getDisplayMetrics().widthPixels;
        int half = totalW / 2;
        if (half <= 0) return;

        if (want == on && half == paneW) return;
        boolean wasOn = on;
        on = want;
        paneW = half;

        if (on) {
            enterSplit();
        } else if (wasOn) {
            /* 只在「原本是分栏、现在合上」时还原；
               手机首帧本来就是叠加态，别白跑一次 collapse()。 */
            exitSplit();
        }
        UpstreamParity.diagLog(act.getApplicationContext(), "SPLIT",
                "on=" + on + " paneW=" + paneW + " totalW=" + totalW
                + " sw=" + c.smallestScreenWidthDp + " w=" + c.screenWidthDp);
    }

    private void enterSplit() {
        /* 1) 底座：右半 */
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(
                paneW, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        web.setLayoutParams(wlp);
        web.requestLayout();

        /* 2) AI：左半 + 全高 + 常驻展开（悬浮球入口失去意义，隐藏） */
        ai.setSplitMode(true, paneW);

        /* 3) 中间分隔线 */
        if (divider == null) {
            divider = new View(act);
            divider.setTag(TAG_DIVIDER);
            divider.setBackgroundColor(Color.parseColor("#E5E7EB"));
        }
        if (divider.getParent() == null) {
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                    Math.max(1, dp(1)), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
            dlp.leftMargin = paneW;
            root.addView(divider, dlp);
        } else {
            FrameLayout.LayoutParams dlp = (FrameLayout.LayoutParams) divider.getLayoutParams();
            dlp.leftMargin = paneW;
            divider.setLayoutParams(dlp);
        }
        try { divider.bringToFront(); } catch (Throwable ignored) {}

        /* 4) 前端：半屏宽度下别再按「平板」放大 UI 字号。
              applyDeviceClass() 里读这个标志，强制手机档；退出时清掉。 */
        web.evaluateJavascript(
                "(function(){try{window.__githupSplitPane=true;"
                + "if(window.App&&App.applyDeviceClass)App.applyDeviceClass();"
                + "window.dispatchEvent(new Event('resize'));}catch(e){}})()", null);
    }

    private void exitSplit() {
        /* 1) 底座恢复整屏 */
        web.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        web.requestLayout();

        /* 2) AI 退出分栏 → 回叠加抽屉（内部会收起面板、还原键盘模式） */
        ai.setSplitMode(false, 0);

        /* 3) 移除分隔线 */
        if (divider != null) {
            try { if (divider.getParent() != null) ((ViewGroup) divider.getParent()).removeView(divider); }
            catch (Throwable ignored) {}
            divider = null;
        }

        /* 4) 前端恢复设备判定 */
        web.evaluateJavascript(
                "(function(){try{window.__githupSplitPane=false;"
                + "if(window.App&&App.applyDeviceClass)App.applyDeviceClass();"
                + "window.dispatchEvent(new Event('resize'));}catch(e){}})()", null);
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }
}
