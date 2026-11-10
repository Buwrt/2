package com.hubmobile.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.hubmobile.app.harness.DraftInputBridge;
import com.hubmobile.app.harness.EngineService;
/* HarnessBridge 在 com.hubmobile.app 根包，与本类同包，不需要 import */
import com.hubmobile.app.harness.LocalBridgeServer;
import com.hubmobile.app.harness.NfcStore;
import com.hubmobile.app.harness.ShellBridge;

/**
 * 阶段 L：把「两个 APP」真正合成一个。
 *
 * 之前是两个带 MAIN/LAUNCHER 的 Activity，启动器画两个图标；点 githup AI 会
 * 整屏跳进 DSH 的装载页，按返回又「退回 githup」——用户要的不是这种切换。
 *
 * 现在：
 *   · 桌面只留 .MainActivity 一个图标（HarnessActivity 的 LAUNCHER 已摘掉，
 *     并用 enabled=false 的 activity-alias 做墓碑让旧图标消失）
 *   · 冷启动**直接就是 GitHub 客户端**，没有任何整屏启动页
 *   · AI 是叠在同一个 contentRoot 上的**常驻面板**：右下角悬浮球点开，
 *     引擎在后台静默装载；两个 WebView 同进程同界面同时活着
 *   · 面板展开时 GitHub 仍在上层可见可点；收起即回纯客户端，不跳 Activity、
 *     不换返回栈、没有「进入 / 退回」
 *
 * 引擎与 3081 本地桥在 attach() 时就后台拉起，所以点开面板基本是秒开。
 */
public final class AiPanel {

    private static final int BG = Color.parseColor("#0d1117");
    private static final int CARD_BG = Color.parseColor("#151b2b");
    private static final int LINE = Color.parseColor("#3a4257");
    private static final int TXT = Color.parseColor("#e6edf3");
    private static final int MUTED = Color.parseColor("#8b949e");
    private static final int ACCENT = Color.parseColor("#4D6BFE");

    /** 用户指定的开机对话，保留为面板装载时的紧凑卡片，不占整屏。 */
    private static final String[] SPEAKER = {"githup", "DeepSeek", "githup", "DeepSeek"};
    private static final String[] LINES = {"你好，DeepSeek", "很高兴为您服务", "让我们共同进步", "好"};
    private static final boolean[] RIGHT = {true, false, true, false};

    private static final int REQ_MIC = 203;

    /** 旧桌面图标 / 系统分享转发过来的 intent，MainActivity 起来后交给面板。 */
    private static volatile Intent pendingShare;

    private final Activity act;
    private final FrameLayout root;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private View bubble;
    private FrameLayout panel;
    private FrameLayout loading;
    private WebView web;
    private TextView dialogue;
    private TextView meta;
    private TextView err;
    private LinearLayout bar;
    private TextView fullBtn;
    private DraftInputBridge draftInput;
    private NfcAdapter nfcAdapter;
    private volatile boolean trustedDoc = false;
    private boolean expanded = false;
    private boolean fullHeight = false;
    private boolean webReady = false;
    private boolean destroyed = false;
    private long totalEntries = 41426L, lastEntries = 0, lastWritten = 0;
    private String readyUrl = null;
    private Intent pendingDraft = null;

    public static void setPendingShare(Intent i) { pendingShare = i; }

    /** 取走一次（取完即清），避免 MainActivity 复用时重复展开。 */
    public static Intent takePendingShare() {
        Intent i = pendingShare;
        pendingShare = null;
        return i;
    }

    public static AiPanel attach(Activity a, FrameLayout contentRoot) {
        return new AiPanel(a, contentRoot);
    }

    private AiPanel(Activity a, FrameLayout contentRoot) {
        act = a;
        root = contentRoot;

        // 1) 本地桥 + 引擎：后台静默启动，绝不阻塞 GitHub 首屏
        try { LocalBridgeServer.start(a.getApplicationContext()); } catch (Throwable ignored) {}
        try { NfcStore.init(a.getApplicationContext()); } catch (Throwable ignored) {}
        nfcAdapter = NfcAdapter.getDefaultAdapter(a);
        if (Build.VERSION.SDK_INT >= 23
                && a.checkSelfPermission("android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED) {
            try { a.requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, REQ_MIC); }
            catch (Throwable ignored) {}
        }
        ui.postDelayed(new Runnable() {
            @Override public void run() { startEngine(); }
        }, 700);

        // 2) 面板（默认收起，懒创建 WebView）
        panel = buildPanel();
        root.addView(panel, panelLp());

        // 3) 右下角悬浮球
        bubble = buildBubble();
        root.addView(bubble, bubbleLp());
        makeDraggable(bubble);

        // 旋转 / 分屏时重算面板高度
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int or, int ob) {
                if (expanded && panel != null) panel.setLayoutParams(panelLp());
            }
        });

    }

    // ==================== 视图 ====================

    private FrameLayout.LayoutParams panelLp() {
        int h = fullHeight ? ViewGroup.LayoutParams.MATCH_PARENT
                : (int) (root.getHeight() * 0.68f);
        if (h <= 0) h = ViewGroup.LayoutParams.MATCH_PARENT;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h, Gravity.BOTTOM);
        return lp;
    }

    private FrameLayout.LayoutParams bubbleLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(54), dp(54),
                Gravity.BOTTOM | Gravity.END);
        lp.rightMargin = dp(16);
        lp.bottomMargin = dp(78);   // 避开底座 SPA 的底部导航
        return lp;
    }

    private FrameLayout buildPanel() {
        FrameLayout f = new FrameLayout(act);
        f.setBackgroundColor(BG);
        f.setVisibility(View.GONE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setCornerRadii(new float[]{dp(18), dp(18), dp(18), dp(18), 0, 0, 0, 0});
        bg.setStroke(1, LINE);
        f.setBackground(bg);
        f.setElevation(dp(14));

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);

        // ---- 品牌条 ----
        bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(CARD_BG);
        bar.setPadding(dp(12), 0, dp(8), 0);
        TextView collapse = new TextView(act);
        collapse.setText("⌄");
        collapse.setTextColor(MUTED);
        collapse.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        collapse.setPadding(0, 0, dp(12), 0);
        collapse.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { collapse(); }
        });
        bar.addView(collapse);
        TextView title = new TextView(act);
        title.setText("githup AI");
        title.setTextColor(TXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView sub = new TextView(act);
        sub.setText("DeepSeek Harness");
        sub.setTextColor(MUTED);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        sub.setPadding(0, 0, dp(10), 0);
        bar.addView(sub);
        fullBtn = new TextView(act);
        fullBtn.setText("⛶");
        fullBtn.setTextColor(ACCENT);
        fullBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        fullBtn.setPadding(dp(10), dp(4), dp(6), dp(4));
        fullBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                fullHeight = !fullHeight;
                panel.setLayoutParams(panelLp());
                fullBtn.setText(fullHeight ? "⤡" : "⛶");
            }
        });
        bar.addView(fullBtn);
        box.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        // ---- 装载卡（引擎没好时才显示）----
        loading = new FrameLayout(act);
        loading.setBackgroundColor(BG);
        LinearLayout lc = new LinearLayout(act);
        lc.setOrientation(LinearLayout.VERTICAL);
        lc.setGravity(Gravity.CENTER_HORIZONTAL);
        lc.setPadding(dp(20), dp(22), dp(20), dp(20));
        TextView logo = new TextView(act);
        logo.setText("githup");
        logo.setTextColor(TXT);
        logo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setGravity(Gravity.CENTER);
        lc.addView(logo);
        TextView tag = new TextView(act);
        tag.setText("GitHub 客户端 · AI 引擎装载中");
        tag.setTextColor(MUTED);
        tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tag.setGravity(Gravity.CENTER);
        tag.setPadding(0, dp(4), 0, dp(16));
        lc.addView(tag);
        dialogue = new TextView(act);
        dialogue.setTextColor(TXT);
        dialogue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        dialogue.setLineSpacing(dp(4), 1f);
        dialogue.setTypeface(Typeface.MONOSPACE);
        lc.addView(dialogue);
        meta = new TextView(act);
        meta.setTextColor(MUTED);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        meta.setTypeface(Typeface.MONOSPACE);
        meta.setPadding(0, dp(18), 0, 0);
        lc.addView(meta);
        err = new TextView(act);
        err.setTextColor(Color.parseColor("#ff6b6b"));
        err.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        err.setPadding(0, dp(12), 0, 0);
        err.setVisibility(View.GONE);
        lc.addView(err);
        loading.addView(lc, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.TOP));

        // ---- AI WebView 容器（懒创建，留着不销毁以保住会话）----
        final FrameLayout webHolder = new FrameLayout(act);
        webHolder.setVisibility(View.GONE);

        // 两层叠放：装载卡在底、WebView 在上；谁可见谁铺满，不用 LinearLayout weight
        FrameLayout body = new FrameLayout(act);
        body.addView(loading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(webHolder, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        box.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        f.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        f.setTag(webHolder);
        playDialogue();
        updateMeta("正在准备 AI 引擎…");
        return f;
    }

    private View buildBubble() {
        TextView b = new TextView(act);
        b.setText("AI");
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#6d8bff"), ACCENT});
        g.setShape(GradientDrawable.OVAL);
        g.setStroke(dp(2), Color.parseColor("#c9d6ff"));
        b.setBackground(g);
        b.setElevation(dp(10));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (expanded) collapse(); else expand(); }
        });
        return b;
    }

    /** 悬浮球可拖动，避免挡住 SPA 里的按钮；点按仍走 onClick。 */
    private void makeDraggable(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            int sx = 0, sy = 0, ml = 0, mt = 0, mr = 0, mb = 0;
            boolean moved = false;
            @Override public boolean onTouch(View view, MotionEvent e) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = (int) e.getRawX(); sy = (int) e.getRawY();
                        ml = lp.leftMargin; mt = lp.topMargin;
                        mr = lp.rightMargin; mb = lp.bottomMargin;
                        moved = false;
                        return false;   // 交给 onClick
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) e.getRawX() - sx, dy = (int) e.getRawY() - sy;
                        if (Math.abs(dx) + Math.abs(dy) < dp(8)) return false;
                        moved = true;
                        lp.gravity = Gravity.BOTTOM | Gravity.END;
                        lp.rightMargin = Math.max(0, mr - dx);
                        lp.bottomMargin = Math.max(0, mb - dy);
                        view.setLayoutParams(lp);
                        return true;
                    case MotionEvent.ACTION_UP:
                        return moved;
                    default:
                        return false;
                }
            }
        });
    }

    // ==================== 面板状态 ====================

    public void expand() {
        if (destroyed) return;
        expanded = true;
        panel.setVisibility(View.VISIBLE);
        panel.setLayoutParams(panelLp());
        bubble.setVisibility(View.GONE);
        if (webReady && web != null) {
            loading.setVisibility(View.GONE);
            ((FrameLayout) panel.getTag()).setVisibility(View.VISIBLE);
        } else {
            loading.setVisibility(View.VISIBLE);
            ((FrameLayout) panel.getTag()).setVisibility(View.GONE);
            if (readyUrl != null) showWeb(readyUrl);
        }
        if (nfcAdapter != null && NfcStore.isArmed()) enableNfc();
    }

    public void collapse() {
        expanded = false;
        panel.setVisibility(View.GONE);
        bubble.setVisibility(View.VISIBLE);
        disableNfc();
    }

    /** 返回键：面板开着时先收起，不退出 App，也不「退回 githup」。 */
    public boolean onBackPressed() {
        if (expanded) { collapse(); return true; }
        return false;
    }

    public void acceptShare(Intent i) {
        if (i == null) return;
        if (draftInput != null) draftInput.accept(i);
        else pendingDraft = i;
    }

    // ==================== 引擎 ====================

    private void startEngine() {
        if (destroyed) return;
        String reuse = HarnessBridge.existingUrl();
        if (reuse != null) { showWeb(reuse); return; }
        HarnessBridge.prepare(act.getApplicationContext(), new HarnessBridge.Callback() {
            @Override public void onStage(String text) { updateMeta(text); }
            @Override public void onProgress(long entries, long written, long total) {
                lastEntries = entries; lastWritten = written;
                if (total > 0) totalEntries = total;
                updateMeta("正在展开 AI 引擎文件…");
            }
            @Override public void onReady(String url) { showWeb(url); }
            @Override public void onError(String msg) {
                String brief = msg == null ? "" :
                        (msg.length() > 600 ? "…" + msg.substring(msg.length() - 600) : msg);
                err.setVisibility(View.VISIBLE);
                err.setText("启动失败：" + brief);
                updateMeta("装载中断");
            }
        });
    }

    private void showWeb(final String url) {
        if (destroyed || url == null) return;
        readyUrl = url;
        ui.post(new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                FrameLayout holder = (FrameLayout) panel.getTag();
                if (web == null) {
                    web = new WebView(act);
                    configure(web);
                    holder.addView(web, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
                    if (pendingDraft != null) {
                        draftInput.accept(pendingDraft);
                        pendingDraft = null;
                    }
                }
                webReady = true;
                holder.setVisibility(View.VISIBLE);
                loading.setVisibility(View.GONE);
                try { EngineService.keepAlive(act.getApplicationContext()); }
                catch (Throwable ignored) {}
                web.loadUrl(url);
            }
        });
    }

    private void updateMeta(String stage) {
        if (meta == null) return;
        meta.setText("已校验 " + String.format("%,d", lastEntries) + " / "
                + String.format("%,d", totalEntries)
                + "   ·   本次新增 " + String.format("%,d", lastWritten) + "\n" + stage);
    }

    private void playDialogue() {
        final int[] idx = {0}, pos = {0};
        final StringBuilder sb = new StringBuilder();
        final Runnable step = new Runnable() {
            @Override public void run() {
                if (destroyed || webReady || idx[0] >= LINES.length) return;
                String line = LINES[idx[0]];
                if (pos[0] == 0) sb.append(RIGHT[idx[0]] ? "        " : "·  ")
                        .append(SPEAKER[idx[0]]).append("  »  ");
                if (pos[0] < line.length()) {
                    sb.append(line.charAt(pos[0])); pos[0]++;
                    dialogue.setText(sb.toString() + " |");
                    ui.postDelayed(this, 46);
                } else {
                    sb.append("\n");
                    dialogue.setText(sb.toString());
                    idx[0]++; pos[0] = 0;
                    ui.postDelayed(this, 380);
                }
            }
        };
        ui.postDelayed(step, 350);
    }

    // ==================== WebView ====================

    private void configure(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, true);
        w.addJavascriptInterface(new ShellBridge(act), "dshshell");
        draftInput = new DraftInputBridge(act, new DraftInputBridge.Guard() {
            @Override public boolean allowed() { return trustedDoc; }
        });
        w.addJavascriptInterface(draftInput, "dshinput");
        w.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                trustedDoc = isEngineUrl(url);
                if (draftInput != null) draftInput.navigation();
            }
            @Override public void onPageFinished(WebView v, String url) {
                trustedDoc = isEngineUrl(url);
                if (draftInput != null && trustedDoc && Build.VERSION.SDK_INT >= 19)
                    v.evaluateJavascript(draftInput.bootstrap(), null);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                trustedDoc = isEngineUrl(r.getUrl().toString());
                return false;
            }
        });
        w.setWebChromeClient(new WebChromeClient());
    }

    private static boolean isEngineUrl(String url) {
        if (url == null) return false;
        try {
            Uri u = Uri.parse(url);
            String h = u.getHost();
            if (h == null) return false;
            h = h.toLowerCase(java.util.Locale.US);
            if (!"127.0.0.1".equals(h) && !"localhost".equals(h)) return false;
            int p = u.getPort();
            return p >= 3080 && p < 3092;
        } catch (Throwable t) { return false; }
    }

    // ==================== NFC / 生命周期 ====================

    private void enableNfc() {
        if (nfcAdapter == null || Build.VERSION.SDK_INT < 19) return;
        try {
            nfcAdapter.enableReaderMode(act, new NfcAdapter.ReaderCallback() {
                @Override public void onTagDiscovered(Tag tag) {
                    NfcStore.recordTag(act.getApplicationContext(), tag);
                }
            }, NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                    | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V
                    | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null);
            NfcStore.setReaderActive(true);
        } catch (Throwable ignored) {}
    }

    private void disableNfc() {
        if (nfcAdapter == null || Build.VERSION.SDK_INT < 19) return;
        try { nfcAdapter.disableReaderMode(act); } catch (Throwable ignored) {}
        try { NfcStore.setReaderActive(false); } catch (Throwable ignored) {}
    }

    public void onResume() {
        if (draftInput != null) draftInput.resume();
        if (expanded && NfcStore.isArmed()) enableNfc();
    }

    public void onPause() {
        if (draftInput != null) draftInput.pause();
        disableNfc();
    }

    public void onDestroy() {
        destroyed = true;
        disableNfc();
        try { NfcStore.setArmListener(null); } catch (Throwable ignored) {}
        if (draftInput != null) draftInput.destroy();
        if (web != null) {
            try { web.destroy(); } catch (Throwable ignored) {}
            web = null;
        }
        ui.removeCallbacksAndMessages(null);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics());
    }
}
