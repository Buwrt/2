package com.hubmobile.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Context;
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
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.ValueCallback;
import android.provider.MediaStore;
import android.content.pm.PackageManager;
import java.io.File;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

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
    /** 阶段 AB：上游 v1.40 的 <input type="file"> / 拍照附件通道。 */
    private static final int REQ_FILE_CHOOSER = 510;
    private static final int REQ_CAMERA_CAPTURE = 511;
    private static final String FALLBACK_URL = "file:///android_asset/fallback.html";

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
    private TextView retryTag;
    private LinearLayout bar;
    private TextView fullBtn;
    private DraftInputBridge draftInput;
    private NfcAdapter nfcAdapter;
    private volatile boolean trustedDoc = false;
    private boolean expanded = false;
    private boolean fullHeight = false;
    /** 阶段 R：设置页开关控制的可移动悬浮球显隐，**默认隐藏**。 */
    private boolean ballEnabled = false;
    private boolean webReady = false;
    private boolean destroyed = false;
    private long totalEntries = 41426L, lastEntries = 0, lastWritten = 0;
    private String readyUrl = null;
    private Intent pendingDraft = null;
    /** 阶段 Y：本次进程内是否已弹过风险确认，避免拒绝后反复骚扰。 */
    private static boolean sConsentAsked = false;

    /* 阶段 AB：上游 MainActivity 有、本包之前没有的壳级状态。 */
    private ValueCallback<Uri[]> fileChooserCallback;
    private Uri cameraOutputUri;
    private volatile boolean fallbackActive = false;
    private volatile String fallbackWhy = "";
    private long fallbackSince = 0L;
    private long seenReviveEpoch = 0L;
    /** 看门狗重拉引擎后 epoch 自增；这里每 4s 检查一次，自动回主界面。 */
    private final Runnable reviveWatcher = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            maybeReloadAfterRevive();
            ui.postDelayed(this, 4000);
        }
    };
    private final Runnable fallbackProbe = new Runnable() {
        @Override public void run() {
            if (destroyed || !fallbackActive) return;
            try {
                int p = com.hubmobile.app.harness.EnginePorts.get(act);
                java.net.Socket s = new java.net.Socket();
                try {
                    s.connect(new java.net.InetSocketAddress("127.0.0.1", p), 300);
                    s.close();
                    /* 引擎真的答话了 → 回主界面（cookie 已下发，走根路径不带一次性 token） */
                    fallbackActive = false;
                    if (web != null) web.loadUrl(com.hubmobile.app.HarnessBridge.homeUrl(act));
                    return;
                } catch (Throwable ignored) {
                    try { s.close(); } catch (Throwable ignored2) {}
                }
            } catch (Throwable ignored) {}
            ui.postDelayed(fallbackProbe, 2000);
        }
    };

    /* 阶段 AA：键盘与输入法。上游 DSH 是独立全屏 Activity（默认 adjustResize），
       键盘弹出时 window 变矮，DSH 的 fixed 底栏自然贴在键盘上方（图 1）。
       我们把 DSH 塞进 MainActivity 的叠加面板后，底座用的是
       adjustPan|stateHidden：整个 window 平移但 WebView 高度不变，
       DSH 的输入框被键盘盖住/推到屏幕外（图 2 只剩工具栏 + 键盘）。
       这里在面板展开期间切 adjustResize，并手动把面板压到「键盘上沿」，
       让 WebView 真的重新测量。 */
    private int savedSoftInputMode = -1;
    private int lastKeyboardHeight = -1;
    private android.view.ViewTreeObserver.OnGlobalLayoutListener kbWatcher;

    /* 阶段 X：当前仓库卡。原生层渲染，零 LLM token。 */
    private LinearLayout repoStrip;
    private TextView repoCard;
    private TextView repoAction;
    private String curRepo = "";
    private String curBrief = "";

    /**
     * 阶段 U：把前端「开新窗口」的三条路全部直接汇到原生外发桥。
     *
     * 为什么不只靠 onCreateWindow：真机上部分 WebView 内核即使设了
     * setSupportMultipleWindows(true)，window.open("about:blank") 也返回 null，
     * 前端 BrowserLogin 就拿不到 popup，退化成「复制登录链接」→ 不跳浏览器。
     * 上游壳的 probe 方案在同一台机器上也可能丢掉 location.replace 回调。
     *
     * 这里在页面里覆盖 window.open：
     *   · about:blank 预占页 → 返回一个假 popup，它的 location.replace()
     *     直接把真正的授权地址交给 githupExternal.open()（立刻 ACTION_VIEW）
     *   · 真实 http(s) → 直接外发
     *   · blob:/data:/回环 → 交回 WebView 原生，不破坏文档预览与 DSH 自身页面
     * 再补两条兜底：复制授权链接（navigator.clipboard.writeText）也自动外发；
     * <a target="_blank"> 在捕获阶段直接外发，不等 onCreateWindow。
     */
    private static final String EXTERNAL_BRIDGE_JS = "(function(){try{\nif(window.__ghExt)return;window.__ghExt=1;\nvar no=window.open;\nfunction mk(u){var o={closed:false,opener:null};o.location={replace:function(x){window.githupExternal.open(String(x||''));},assign:function(x){window.githupExternal.open(String(x||''));}};o.close=function(){o.closed=true;};if(u)window.githupExternal.open(u);return o;}\nwindow.open=function(u){var s=(u==null?'':String(u));if(s===''||s==='about:blank')return mk('');if(/^(blob:|data:|file:|content:)/i.test(s)){try{return no.apply(window,arguments);}catch(e){return null;}}if(/^https?:\\/\\/(127\\.0\\.0\\.1|localhost|\\[::1\\])(:\\d+)?\\//i.test(s)){try{return no.apply(window,arguments);}catch(e){return null;}}return mk(s);};\ntry{if(navigator.clipboard&&navigator.clipboard.writeText){var ow=navigator.clipboard.writeText.bind(navigator.clipboard);navigator.clipboard.writeText=function(t){try{var s=String(t||'');if(/^https?:\\/\\//i.test(s)&&/(deepseek|oauth|authorize|login|signin)/i.test(s))window.githupExternal.open(s);}catch(e){}return ow(t);};}}catch(e){}\ntry{document.addEventListener('click',function(ev){try{var a=ev.target&&ev.target.closest?ev.target.closest('a[target=\"_blank\"]'):null;if(!a)return;if(a.hasAttribute('download'))return;var h=a.getAttribute('href')||'';if(/\\.apk(\\?|$)/i.test(h))return;if(!/^https?:\\/\\//i.test(h))return;if(/^https?:\\/\\/(127\\.0\\.0\\.1|localhost|\\[::1\\])(:\\d+)?\\//i.test(h))return;ev.preventDefault();ev.stopPropagation();window.githupExternal.open(h);}catch(e){}},true);}catch(e){}\n}catch(e){}})()";

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

        /* 1) 本地桥 + 完整引擎：点开 App 后**无条件**后台启动。
           这一行与悬浮球开关完全无关 —— 球默认隐藏时引擎照样解压、起 node、
           建 WebView、加载 DSH 首页；开关只管「有没有那个可拖动的入口」。 */
        // 本地桥 + 引擎：后台静默启动，绝不阻塞 GitHub 首屏
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

        // 3) 右下角可移动悬浮球（显隐由设置页开关决定，**默认隐藏**）
        bubble = buildBubble();
        root.addView(bubble, bubbleLp());
        makeDraggable(bubble);
        installKeyboardWatcher();
        ballEnabled = com.hubmobile.app.harness.GhBridge.ballOn(act);
        bubble.setVisibility(ballEnabled ? View.VISIBLE : View.GONE);

        // 全屏时品牌条要避开状态栏，否则「githup AI」会和系统时间/图标叠成一团
        applyBarInsets();

        // 旋转 / 分屏时重算面板高度
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int or, int ob) {
                if (expanded && panel != null)
                    panel.setLayoutParams(panelLp(lastKeyboardHeight > 0 ? lastKeyboardHeight : 0));
            }
        });

    }

    // ==================== 视图 ====================

    /** 状态栏高度：edge-to-edge 下全屏面板会顶到 y=0，品牌条必须让出这段。 */
    private int statusBarHeight() {
        try {
            int id = act.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return act.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {}
        return dp(24);
    }

    /**
     * 阶段 R：全屏时整个面板从「状态栏下方」开始，而不是只给品牌条加 padding。
     * 之前只抬 bar，底座 SPA 顶栏 / 系统时间 / AI 品牌条会挤在同一行叠成一团，
     * 再加上面板顶到 y=0 的 edge-to-edge，看起来就是「一排糊掉的字 + 整屏白」。
     * 现在 panelLp() 在全屏时把高度扣掉状态栏，bar 的 padding 归 0，不双重留白。
     */
    private void applyBarInsets() {
        if (bar == null) return;
        bar.setPadding(dp(12), 0, dp(8), 0);
        try {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bar.getLayoutParams();
            lp.height = dp(38);
            bar.setLayoutParams(lp);
        } catch (Throwable ignored) {}
    }

    /** 一键恢复：比例回 100%，并重新载入当前引擎页。 */
    /**
     * 阶段 T：复位不只是「比例回 100%」。以前用 reload()，如果当前已经是
     * about:blank 白屏页，reload 还是白 —— 用户点了复位也出不来。
     * 现在直接回到引擎首页 readyUrl，并把白屏兜底亮出来的装载卡收回去。
     */
    private void resetView() {
        if (web == null) { Toast.makeText(act, "AI 界面还没就绪", Toast.LENGTH_SHORT).show(); return; }
        try { web.setInitialScale(100); } catch (Throwable ignored) {}
        /* 优先走引擎根路径（cookie 已由首次加载下发），别拿带一次性 token 的
           readyUrl 重放 —— 那串 token 可能已经失效，反而 401 又白一次。 */
        String home = null;
        try { home = HarnessBridge.existingUrl(); } catch (Throwable ignored) {}
        if (home == null || home.isEmpty()) home = readyUrl;
        try {
            if (home != null && !home.isEmpty()) web.loadUrl(home);
            else web.reload();
        } catch (Throwable ignored) {}
        try {
            FrameLayout holder = (FrameLayout) panel.getTag();
            if (holder != null) holder.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {}
        if (loading != null) loading.setVisibility(View.GONE);
        if (err != null) err.setVisibility(View.GONE);
        if (retryTag != null) retryTag.setVisibility(View.GONE);
        Toast.makeText(act, "已回到 AI 首页并恢复界面大小", Toast.LENGTH_SHORT).show();
    }

    private FrameLayout.LayoutParams panelLp() {
        return panelLp(lastKeyboardHeight > 0 ? lastKeyboardHeight : 0);
    }

    /**
     * keyboardHeight > 0 时：面板占满「可视区域」，顶部让出状态栏、
     * 底部正好到键盘上沿。WebView 随之变矮并触发 resize，
     * DSH 的 position:fixed / 100dvh 输入栏才能重新贴到键盘上方。
     */
    private FrameLayout.LayoutParams panelLp(int keyboardHeight) {
        int avail = root.getHeight();
        int h;
        if (keyboardHeight > 0) {
            h = avail > 0 ? Math.max(dp(200), avail - statusBarHeight() - keyboardHeight)
                          : ViewGroup.LayoutParams.MATCH_PARENT;
        } else if (fullHeight) {
            /* 全屏但**不顶到状态栏**：底部对齐，顶部让出状态栏高度。 */
            h = avail > 0 ? Math.max(dp(200), avail - statusBarHeight())
                          : ViewGroup.LayoutParams.MATCH_PARENT;
        } else {
            h = avail > 0 ? (int) (avail * 0.68f) : ViewGroup.LayoutParams.MATCH_PARENT;
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h, Gravity.BOTTOM);
        return lp;
    }

    /** 键盘高度监听：decor 可视区域变小 = 软键盘弹起。 */
    private void installKeyboardWatcher() {
        try {
            final View decor = act.getWindow().getDecorView();
            final android.graphics.Rect r = new android.graphics.Rect();
            kbWatcher = new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    if (destroyed) return;
                    try {
                        decor.getWindowVisibleDisplayFrame(r);
                        int kb = decor.getHeight() - r.bottom;
                        if (kb < dp(90)) kb = 0;          // 导航栏/状态栏差异不算键盘
                        if (kb == lastKeyboardHeight) return;
                        lastKeyboardHeight = kb;
                        if (expanded && panel != null) {
                            panel.setLayoutParams(panelLp(kb));
                            if (web != null) web.requestLayout();
                            /* 布局落定后再请求一次，确保 WebView 收到尺寸变化 */
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    if (!destroyed && web != null) web.requestLayout();
                                }
                            });
                        }
                    } catch (Throwable ignored) {}
                }
            };
            decor.getViewTreeObserver().addOnGlobalLayoutListener(kbWatcher);
        } catch (Throwable ignored) {}
    }

    /** 面板展开期间改用 adjustResize；收起还原底座原本的 adjustPan。 */
    private void applySoftInput(boolean aiMode) {
        try {
            android.view.Window w = act.getWindow();
            if (savedSoftInputMode < 0) savedSoftInputMode = w.getAttributes().softInputMode;
            if (aiMode) {
                w.setSoftInputMode(
                        android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED);
            } else {
                w.setSoftInputMode(savedSoftInputMode);
            }
        } catch (Throwable ignored) {}
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
        /* 「⤾ 复位视图」：以前页面被捏合放大后就再也缩不回去，白屏一片。
           现在原生层禁掉手势缩放（见 configure()），这里再给一个明确入口，
           把初始比例和页面一起复位，用户随时能一键回到正常大小。 */
        TextView resetBtn = new TextView(act);
        resetBtn.setText("复位");
        resetBtn.setTextColor(MUTED);
        resetBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        resetBtn.setTypeface(Typeface.DEFAULT_BOLD);
        resetBtn.setPadding(dp(8), dp(4), dp(6), dp(4));
        resetBtn.setContentDescription("恢复界面大小");
        resetBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { resetView(); }
        });
        bar.addView(resetBtn);
        fullBtn = new TextView(act);
        fullBtn.setText("⛶");
        fullBtn.setTextColor(ACCENT);
        fullBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        fullBtn.setPadding(dp(10), dp(4), dp(6), dp(4));
        fullBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                fullHeight = !fullHeight;
                panel.setLayoutParams(panelLp());
                applyBarInsets();
                fullBtn.setText(fullHeight ? "⤡" : "⛶");
                /* 切换全屏时也顺手把比例复位，避免带着上一次的放大状态过去 */
                if (web != null) try { web.setInitialScale(100); } catch (Throwable ignored) {}
            }
        });
        bar.addView(fullBtn);
        box.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        /* ---- 阶段 X：当前仓库卡 ----
           用户逛到某个库，AI 面板立刻认出来（App 自己写下的路由 + GitHub REST
           速览，不调 LLM）。以前 AI 是被动的：不主动 curl /app/context，
           就答「看不到」，或跑好几轮 gh CLI / 列环境 / 翻文件去反推 ——
           那几轮就是「特别费 token」的来源。 */
        repoStrip = new LinearLayout(act);
        repoStrip.setOrientation(LinearLayout.HORIZONTAL);
        repoStrip.setGravity(Gravity.CENTER_VERTICAL);
        repoStrip.setBackgroundColor(Color.parseColor("#1b2336"));
        repoStrip.setPadding(dp(12), dp(5), dp(8), dp(5));
        repoStrip.setVisibility(View.GONE);
        repoCard = new TextView(act);
        repoCard.setTextColor(TXT);
        repoCard.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        repoCard.setSingleLine(true);
        repoCard.setEllipsize(android.text.TextUtils.TruncateAt.END);
        repoStrip.addView(repoCard, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        repoAction = new TextView(act);
        repoAction.setTextColor(ACCENT);
        repoAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        repoAction.setTypeface(Typeface.DEFAULT_BOLD);
        repoAction.setPadding(dp(10), dp(3), dp(2), dp(3));
        repoAction.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onRepoAction(); }
        });
        repoStrip.addView(repoAction);
        box.addView(repoStrip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
        /* 阶段 R：白屏兜底。引擎起来了但 WebView 加载失败时，不能只剩一片白 ——
           给一个明确的重试入口，把 loading 卡重新亮出来。 */
        final TextView retry = new TextView(act);
        retry.setText("重新加载 AI 界面");
        retry.setTextColor(ACCENT);
        retry.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        retry.setTypeface(Typeface.DEFAULT_BOLD);
        retry.setPadding(0, dp(14), 0, 0);
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                retry.setVisibility(View.GONE);
                err.setVisibility(View.GONE);
                if (web != null) {
                    try { web.setInitialScale(100); } catch (Throwable ignored) {}
                    try { if (readyUrl != null) web.loadUrl(readyUrl); else web.reload(); }
                    catch (Throwable ignored) {}
                } else {
                    startEngine();
                }
            }
        });
        lc.addView(retry);
        retryTag = retry;
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

    /* ==================== 阶段 X：当前仓库感知（零 LLM token） ==================== */

    /**
     * 底座换仓库 / 进仓库页时由 MainActivity 转发。
     * 只走 GitHub REST 拿元数据 + README，压成一条精简事实；
     * 卡片常驻显示，用户不说话 AI 面板也「认得」当前库。
     */
    public void onRepoChanged(final String repo) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                curRepo = repo == null ? "" : repo;
                if (curRepo.isEmpty()) {
                    curBrief = "";
                    if (repoStrip != null) repoStrip.setVisibility(View.GONE);
                    return;
                }
                boolean consent;
                try { consent = com.hubmobile.app.harness.AppContext.consent(act); }
                catch (Throwable t) { consent = false; }
                if (repoStrip != null) {
                    repoStrip.setVisibility(View.VISIBLE);
                    repoCard.setText(consent
                            ? "当前：" + curRepo + " · 读取速览…"
                            : "当前：" + curRepo + " · 点右侧「开启感知」后 AI 才能辅助");
                }
                refreshRepoAction();
                /* 未同意时不预抓、不调 LLM —— 尊重上一轮用户定的闸门，
                   卡片只给一个显眼的一键开启入口，不再是「点了没反应」。 */
                if (!consent) { curBrief = ""; return; }
                com.hubmobile.app.harness.RepoBrief.fetch(act, curRepo,
                        new com.hubmobile.app.harness.RepoBrief.Callback() {
                    @Override public void done(String r, final String card, final String brief) {
                        ui.post(new Runnable() {
                            @Override public void run() {
                                if (destroyed || !curRepo.equals(r)) return;
                                curBrief = brief == null ? "" : brief;
                                if (repoCard != null)
                                    repoCard.setText(card.isEmpty()
                                            ? "当前：" + curRepo + " · 速览不可用（检查登录/网络）"
                                            : "当前：" + card);
                                refreshRepoAction();
                            }
                        });
                    }
                });
            }
        });
    }

    /** consent 未开时按钮是「开启感知」，开了变成「带入对话」。 */
    private void refreshRepoAction() {
        if (repoAction == null) return;
        boolean consent;
        try { consent = com.hubmobile.app.harness.AppContext.consent(act); }
        catch (Throwable t) { consent = false; }
        repoAction.setText(consent ? "带入对话" : "开启感知");
    }

    private void onRepoAction() {
        boolean consent;
        try { consent = com.hubmobile.app.harness.AppContext.consent(act); }
        catch (Throwable t) { consent = false; }
        if (!consent) { showConsentDialog(); return; }
        if (curBrief.isEmpty()) {
            toast("当前仓库速览还没准备好，稍等一下或检查 GitHub 登录");
            return;
        }
        injectBrief(curBrief);
    }

    /** 卡片上的一键开启：复用设置页同一份风险文案，不绕过用户上轮定的闸门。 */
    private void showConsentDialog() {
        try {
            String msg = "开启后，AI 无需任何 Android 系统权限（不用无障碍、不用截图、"
                    + "不用悬浮窗），就能准确知道 githup 当前所在的页面、正在阅读的仓库，"
                    + "并可总结、修改代码，再把修改后的内容以「相同名称」的仓库上传到"
                    + "你当前登录的 GitHub 账号。\n\n"
                    + "注意：此过程有可能将 token 给予第三方软件或 DeepSeek，"
                    + "仓库内容也会离开设备。\n"
                    + "是否同意？此操作与作者无关。";
            new AlertDialog.Builder(act)
                    .setTitle("AI 感知与克隆 · 风险确认")
                    .setMessage(msg)
                    .setNegativeButton("拒绝", null)
                    .setPositiveButton("同意", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            try {
                                com.hubmobile.app.harness.AppContext.setConsent(act, true);
                                refreshRepoAction();
                                toast("已开启感知，正在准备当前仓库速览；点「带入对话」即可，不会自动消耗 AI 调用");
                                if (!curRepo.isEmpty()) onRepoChanged(curRepo);
                            } catch (Throwable ignored) {}
                        }
                    })
                    .show();
        } catch (Throwable ignored) {}
    }

    /**
     * 把「已知事实」预填进 DSH 输入框 —— 不自动发送。
     *
     * 这是省 token 的关键：模型不用再跑 N 轮探测去反推用户看的是哪个库，
     * 用户按一下发送，一轮就能答。React 受控组件要用 prototype 的 native
     * setter + input 事件，直接赋值会被下一次渲染冲掉。
     */
    private void injectBrief(final String text) {
        if (web == null || text == null || text.isEmpty()) { fallbackCopy(text); return; }
        final String js = "(function(t){try{"
                + "window.__GITHUP_NOW__=t;"
                + "var ns=document.querySelectorAll('textarea,[contenteditable=\"true\"],[role=\"textbox\"]');"
                + "var el=null;for(var i=0;i<ns.length;i++){var n=ns[i];"
                + "if(n.offsetParent!==null||(n.getClientRects&&n.getClientRects().length)){el=n;break;}}"
                + "if(!el)return 'NO_INPUT';"
                + "if(el.tagName==='TEXTAREA'||el.tagName==='INPUT'){"
                + "var pr=el.tagName==='TEXTAREA'?window.HTMLTextAreaElement.prototype:window.HTMLInputElement.prototype;"
                + "var d=Object.getOwnPropertyDescriptor(pr,'value');"
                + "if(d&&d.set)d.set.call(el,t);else el.value=t;"
                + "}else{el.textContent=t;}"
                + "el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "el.dispatchEvent(new Event('change',{bubbles:true}));"
                + "try{el.focus();}catch(e){}"
                + "return 'OK';}catch(e){return 'ERR:'+e.message}})(" + JSONObject.quote(text) + ")";
        ui.post(new Runnable() {
            @Override public void run() {
                if (destroyed || web == null) { fallbackCopy(text); return; }
                try {
                    if (Build.VERSION.SDK_INT >= 19) {
                        web.evaluateJavascript(js, new ValueCallback<String>() {
                            @Override public void onReceiveValue(String v) {
                                if (v == null || !v.contains("OK")) fallbackCopy(text);
                                else toast("已带入当前仓库速览，按发送即可（无需 AI 再自己探测）");
                            }
                        });
                    } else fallbackCopy(text);
                } catch (Throwable t) { fallbackCopy(text); }
            }
        });
    }

    /** 找不到输入框时退化：复制到剪贴板，用户粘贴即可，不静默失败。 */
    private void fallbackCopy(String text) {
        if (text == null || text.isEmpty()) return;
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("githup 当前仓库", text));
            toast("已复制当前仓库速览，粘贴到 AI 输入框即可");
        } catch (Throwable ignored) {}
    }

    /** 设置页开关切换时即时生效：关掉就把球藏起来，并收起已展开的面板。 */
    public void setBubbleEnabled(boolean on) {
        ballEnabled = on;
        if (bubble == null) return;
        if (!on && expanded) collapse();
        else bubble.setVisibility(expanded ? View.GONE : (on ? View.VISIBLE : View.GONE));
    }

    /**
     * 阶段 Y：把「同意」放到用户**真正会点的地方**。
     *
     * 上一轮把 consent 藏在「我的 → 设置 → AI 感知与克隆」这个新开关里，
     * 默认关。用户早已打开的是「AI 辅助 githup」（悬浮球开关），以为开了
     * 就有 AI 辅助 —— 结果 /app/context 返回 page:null、插件不装、卡片只写
     * 「点右侧开启感知」，看起来就是「AI 根本没辅助」。
     *
     * 现在第一次展开 AI 面板就弹一次风险确认（同意 / 拒绝，文案按用户原话）。
     * 插件已在引擎启动时常驻，所以点「同意」**不需要重启引擎**：下一轮
     * 自动注入、/app/ui/* 立刻可用。拒绝则保持只读聊天，不写任何数据。
     */
    private void ensureConsent() {
        try {
            if (com.hubmobile.app.harness.AppContext.consent(act) || sConsentAsked) return;
            sConsentAsked = true;
            String msg = "开启后，AI 无需任何 Android 系统权限（不用无障碍、不用截图、不用悬浮窗），"
                    + "就能知道 githup 当前所在的页面、正在阅读的仓库与屏幕上已渲染的内容，"
                    + "并可替你操作本软件（导航、Star、切换翻译、打开 Issue…）、总结与修改代码，"
                    + "再把修改后的内容以「相同名称」的仓库上传到你当前登录的 GitHub 账号。\n\n"
                    + "注意：此过程有可能将 token 给予第三方软件或 DeepSeek，仓库内容也会离开设备。\n\n"
                    + "是否同意？此操作与作者无关。";
            new AlertDialog.Builder(act)
                    .setTitle("AI 辅助 githup · 风险确认")
                    .setMessage(msg)
                    .setCancelable(true)
                    .setNegativeButton("拒绝", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            Toast.makeText(act, "已拒绝：AI 保持普通对话，不感知页面、不操作软件",
                                    Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setPositiveButton("同意", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            com.hubmobile.app.harness.AppContext.setConsent(act, true);
                            /* consent 之前不预抓 RepoBrief，同意后立刻补一次当前仓库速览 */
                            try {
                                String r = com.hubmobile.app.harness.AppContext.currentRepo(act);
                                if (r != null && !r.isEmpty()) onRepoChanged(r);
                            } catch (Throwable ignored) {}
                            Toast.makeText(act,
                                    "已开启：AI 现在能看懂当前页面，并可替你操作 githup",
                                    Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
        } catch (Throwable ignored) {}
    }

    public void expand() {
        if (destroyed) return;
        applySoftInput(true);          // 阶段 AA：与上游一样让 WebView 随键盘 resize
        ensureConsent();

        /* 每次打开都把视口比例拉回 100%，避免上一次被放大/全屏的状态带过来，
           用户再也不会「放大之后缩不小」。configure() 里也已禁掉手势缩放。 */
        if (web != null) try {
            /* 阶段 W：展开时面板改变尺寸，WebView 要重新测量视口。
               把 setInitialScale 落在动画帧里会被内核吞掉或延后，表现就是
               「点了放大没反应、过一会儿才变」。动画前挂硬件层减少掉帧，
               布局落定后再复位一次比例并摘掉硬件层，跟手且不残留。 */
            web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            web.setInitialScale(100);
        } catch (Throwable ignored) {}
        expanded = true;
        panel.setVisibility(View.VISIBLE);
        panel.setLayoutParams(panelLp());
        bubble.setVisibility(View.GONE);
        /* 冷启动时底座可能还没上报过页面：主动补一次，卡片不空着。 */
        if (curRepo.isEmpty()) {
            try {
                String r = com.hubmobile.app.harness.AppContext.currentRepo(act);
                if (r != null && !r.isEmpty()) onRepoChanged(r);
            } catch (Throwable ignored) {}
        }
        refreshRepoAction();
        panel.post(new Runnable() {
            @Override public void run() {
                if (destroyed || web == null) return;
                try {
                    web.setInitialScale(100);
                    web.setLayerType(View.LAYER_TYPE_NONE, null);
                } catch (Throwable ignored) {}
            }
        });
        if (webReady && web != null) {
            loading.setVisibility(View.GONE);
            ((FrameLayout) panel.getTag()).setVisibility(View.VISIBLE);
        } else {
            loading.setVisibility(View.VISIBLE);
            ((FrameLayout) panel.getTag()).setVisibility(View.GONE);
            if (readyUrl != null) showWeb(readyUrl);
        }
        if (nfcAdapter != null && NfcStore.isArmed()) enableNfc();
        /* 阶段 AB：上游 checkBatteryOptimization 的等价温和提示，每 24h 最多一次，
           不阻塞、不强行要权限。延后 1.5s 避开风险确认弹窗。 */
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (destroyed || !expanded) return;
                if (!com.hubmobile.app.harness.UpstreamParity.shouldHintBattery(act)) return;
                com.hubmobile.app.harness.UpstreamParity.markBatteryHinted(act);
                try {
                    new AlertDialog.Builder(act)
                            .setTitle("建议允许后台运行")
                            .setMessage("当前应用被系统限制后台活动，AI 长时间任务挂后台可能被杀掉。"
                                    + "把本应用设为「不限制」电池优化可避免。")
                            .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    com.hubmobile.app.harness.UpstreamParity.requestIgnoreBattery(act);
                                }})
                            .setNegativeButton("暂不", null)
                            .show();
                } catch (Throwable ignored) {}
            }
        }, 1500);
    }

    public void collapse() {
        expanded = false;
        lastKeyboardHeight = -1;
        applySoftInput(false);         // 还原底座的 adjustPan，别影响 GitHub 搜索框
        panel.setVisibility(View.GONE);
        bubble.setVisibility(ballEnabled ? View.VISIBLE : View.GONE);
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
            @Override public void onReady(String url) {
                /* 阶段 AB：启动成功 → 清失败计数、登记「曾启动」（看门狗只在
                   这之后才自愈，避免冷启动 700ms 被误判）、拉起看门狗。 */
                com.hubmobile.app.harness.UpstreamParity.clearBootFailures(act);
                com.hubmobile.app.harness.UpstreamParity.noteEngineStarted(act);
                com.hubmobile.app.harness.UpstreamParity.setUserStopped(act, false);
                com.hubmobile.app.harness.UpstreamParity.startWatchdog(act);
                seenReviveEpoch = com.hubmobile.app.harness.UpstreamParity.reviveEpoch();
                showWeb(url);
            }
            @Override public void onError(String msg) {
                String brief = msg == null ? "" :
                        (msg.length() > 600 ? "…" + msg.substring(msg.length() - 600) : msg);
                /* 阶段 Y+：把最常见的 EADDRINUSE 翻成用户能照着做的话，
                   别再甩一屏英文堆栈。自动重试已在 HarnessBridge.prepare 里做过。 */
                boolean clash = brief.contains("EADDRINUSE")
                        || brief.contains("address already in use");
                String shown = clash
                        ? "引擎端口被占用（残留进程 / 后台定时任务 / 上游原版同时运行）。\n"
                          + "已自动清理并重试；若仍失败：请完全退出本 App 再打开，"
                          + "或卸载手机上另一个 DeepSeek Harness。\n\n"
                          + "技术细节：" + brief
                        : "启动失败：" + brief;
                err.setVisibility(View.VISIBLE);
                err.setText(shown);
                updateMeta(clash ? "端口冲突 · 装载中断" : "装载中断");
                /* 阶段 AB：连续失败达到阈值时，把「安全模式」入口直接给出来 ——
                   上游这步用来解「插件写坏 YAML → 引擎起不来 → 又只能靠引擎去修」的死结。 */
                com.hubmobile.app.harness.UpstreamParity.noteBootFailure(act, brief);
                int fails = com.hubmobile.app.harness.UpstreamParity.bootFailures(act);
                if (fails >= com.hubmobile.app.harness.UpstreamParity.BOOT_FAIL_HINT_AT) {
                    err.setText(shown + "\n\n已连续失败 " + fails
                            + " 次。若是插件把配置写坏，可在 AI 面板点「复位」后"
                            + "用兜底页的「进入安全模式」旁置用户层配置（会话与凭据保留）。");
                }
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
                /* 看门狗重拉引擎后 epoch 会变，周期轮询发现即自动回主界面。 */
                ui.removeCallbacks(reviveWatcher);
                ui.postDelayed(reviveWatcher, 4000);
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

    /* ==================== 阶段 T：WebView 落地，逐条对齐上游 MainActivity ====================
     *
     * 真机复现：点 DSH Web UI 的「登录」→ 整屏白。
     * 查 dsh-client-ui-settings-account/lib/client.js 的 BrowserLogin：
     *     begin(){ this.popup = window.open("about:blank", "_blank"); }
     *     update(){ ... this.popup.location.replace(attempt.authorizeUrl); }
     * 即「先预开空白标签页，再把它导航到 DeepSeek 授权地址」。
     *
     * 上游壳（只看源码）为此做了两件事，我们的精简桥两件都缺：
     *   ① setSupportMultipleWindows(true) + WebChromeClient.onCreateWindow
     *      造一个 probe WebView 接住 window.open，忽略 about:blank 预占页，
     *      等真正的授权地址来了再落地（否则 popup 为 null，前端走「复制链接」，
     *      或空白页把主 WebView 顶掉 → 整屏白）。
     *   ② 非本机回环的链接一律外发系统浏览器，且用**选择器**让用户挑浏览器。
     *      账户登录回调是 http://127.0.0.1:<port>/oauth/callback，引擎本地 HTTP 服务接收；
     *      UC / 夸克 / QQ浏览器会把 127.0.0.1 当危险地址拦掉或当搜索词，
     *      导致「等待中」永远不结束。Chrome / 系统浏览器能正常跳回环。
     *
     * 顺带补齐：主框架 about:/data:/blob: 一律拦（防白屏）、子框架 blob:/data: 放行
     * （文档预览用 createObjectURL）、下载走 DownloadManager、JS 错误进日志。
     */
    private void configure(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        /* 阶段 Q：禁掉手势缩放，避免放大后缩不回去、整屏白掉。 */
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        /* 阶段 AA：与上游 MainActivity 完全一致。缺这一行时 WebView 会跟随
           系统「字体大小/显示大小」设置把 DSH 页面整体放大，输入框更容易被挤出视口。 */
        s.setTextZoom(100);
        /* 阶段 T：接住 window.open("about:blank")，配合下面的 onCreateWindow。
           阶段 U：连非手势触发的 window.open 也允许，避免内核差异把它静默吞掉。 */
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        w.setInitialScale(100);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, true);
        w.addJavascriptInterface(new ShellBridge(act), "dshshell");
        draftInput = new DraftInputBridge(act, new DraftInputBridge.Guard() {
            @Override public boolean allowed() { return trustedDoc; }
        });
        w.addJavascriptInterface(draftInput, "dshinput");
        /* 阶段 U：原生外发桥。window.open / 复制登录链接 / <a target=_blank>
           三条路都汇到 openExternalFast()，点登录立刻跳浏览器。 */
        w.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void open(String url) { openExternalFast(url); }
        }, "githupExternal");

        /* 阶段 AB：白屏兜底页的原生桥。只在 file:///android_asset/fallback.html
           里调用；里面每个方法都自带 try/catch，不影响引擎页。 */
        w.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void tick() {
                if (!fallbackActive) return;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (destroyed || web == null || !fallbackActive) return;
                        String st = "正在探测本地引擎…"
                                + (fallbackWhy.isEmpty() ? "" : "（" + fallbackWhy + "）");
                        int p = com.hubmobile.app.harness.EnginePorts.get(act);
                        int sec = (int) ((System.currentTimeMillis() - fallbackSince) / 1000);
                        String s = com.hubmobile.app.harness.UpstreamParity.safeModeActive(act)
                                ? "安全模式已开启" : st;
                        web.evaluateJavascript(
                                "try{var d=document;if(d.getElementById('state'))d.getElementById('state').textContent="
                                + org.json.JSONObject.quote(s)
                                + ";if(d.getElementById('port'))d.getElementById('port').textContent='"
                                + p + "';if(d.getElementById('logpath'))d.getElementById('logpath').textContent="
                                + org.json.JSONObject.quote(com.hubmobile.app.harness.UpstreamParity.diagPath(act))
                                + ";if(d.getElementById('why'))d.getElementById('why').textContent="
                                + org.json.JSONObject.quote(fallbackWhy.isEmpty() ? "—" : fallbackWhy)
                                + ";if(d.getElementById('logs'))d.getElementById('logs').textContent="
                                + org.json.JSONObject.quote(com.hubmobile.app.harness.UpstreamParity.diagTail(act, 1600))
                                + ";}catch(e){}", null);
                    }
                });
            }
            @android.webkit.JavascriptInterface public void retry() {
                if (!fallbackActive) return;
                ui.post(new Runnable() { @Override public void run() {
                    com.hubmobile.app.harness.UpstreamParity.diagLog(act, "FALLBACK", "用户点立即重试");
                    fallbackActive = false;
                    ui.removeCallbacks(fallbackProbe);
                    if (web != null) web.loadUrl(com.hubmobile.app.HarnessBridge.homeUrl(act));
                }});
            }
            @android.webkit.JavascriptInterface public void reload() {
                if (!fallbackActive) return;
                ui.post(new Runnable() { @Override public void run() {
                    fallbackActive = false;
                    if (web != null) web.loadUrl(com.hubmobile.app.HarnessBridge.homeUrl(act));
                }});
            }
            @android.webkit.JavascriptInterface public void enterSafeMode() {
                if (!fallbackActive) return;
                final String r = com.hubmobile.app.harness.UpstreamParity.enterSafeMode(act);
                ui.post(new Runnable() { @Override public void run() {
                    Toast.makeText(act, r, Toast.LENGTH_LONG).show();
                }});
            }
            @android.webkit.JavascriptInterface public void exitSafeMode() {
                if (!fallbackActive) return;
                final String r = com.hubmobile.app.harness.UpstreamParity.exitSafeMode(act);
                ui.post(new Runnable() { @Override public void run() {
                    Toast.makeText(act, r, Toast.LENGTH_LONG).show();
                }});
            }
            @android.webkit.JavascriptInterface public void battery() {
                if (!fallbackActive) return;
                com.hubmobile.app.harness.UpstreamParity.requestIgnoreBattery(act);
                com.hubmobile.app.harness.UpstreamParity.markBatteryHinted(act);
            }
            @android.webkit.JavascriptInterface public void openLogs() {
                if (!fallbackActive) return;
                ui.post(new Runnable() { @Override public void run() {
                    Toast.makeText(act,
                            com.hubmobile.app.harness.UpstreamParity.diagTail(act, 500),
                            Toast.LENGTH_LONG).show();
                }});
            }
        }, "githupFallback");

        /* 文件卡片 / APK 链接：WebView 默认会把下载交回系统「文件预览」，
           这里交给 DownloadManager，通知栏点开即装。 */
        w.setDownloadListener(new android.webkit.DownloadListener() {
            @Override public void onDownloadStart(String url, String userAgent,
                                                  String contentDisposition, String mimetype,
                                                  long contentLength) {
                openDownload(url);
            }
        });

        w.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                /* 某些内核仍会把 window.open 的空白页塞进主框架 —— 立刻拉回引擎首页，
                   不让用户对着白屏。 */
                if ("about:blank".equals(url) && readyUrl != null) {
                    try { v.stopLoading(); v.loadUrl(readyUrl); } catch (Throwable ignored) {}
                    return;
                }
                injectExternalBridge(v);
                trustedDoc = isEngineUrl(url);
                if (draftInput != null) draftInput.navigation();
            }
            @Override public void onPageFinished(WebView v, String url) {
                if ("about:blank".equals(url) && readyUrl != null) {
                    try { v.loadUrl(readyUrl); } catch (Throwable ignored) {}
                    return;
                }
                injectExternalBridge(v);
                trustedDoc = isEngineUrl(url);
                if (draftInput != null && trustedDoc && Build.VERSION.SDK_INT >= 19)
                    v.evaluateJavascript(draftInput.bootstrap(), null);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (r == null) return true;
                String url = r.getUrl().toString();
                if (!r.isForMainFrame()) {
                    /* 子框架拿不到主文档的原生桥：blob:/data: 预览与远程 iframe 放行，
                       只拦 file:// / content:// 这类能碰本地文件的协议。 */
                    return !resourceAllowed(url);
                }
                return shouldOverrideMain(url);
            }
            @Override @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return shouldOverrideMain(url);
            }
            /* 阶段 R：引擎起来了但页面没出来时，把装载卡亮出来 + 重试入口。 */
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r != null && r.isForMainFrame()) {
                    String d = e == null ? "" : String.valueOf(e.getDescription());
                    if (isEngineUrl(v.getUrl()) || readyUrl != null) enterFallback("页面加载失败：" + d);
                    else showPageError("页面加载失败：" + d);
                }
            }
            @Override public void onReceivedHttpError(WebView v, WebResourceRequest r,
                                                      WebResourceResponse er) {
                if (r == null || !r.isForMainFrame() || er == null) return;
                int code = er.getStatusCode();
                /* 阶段 AB：401 不再只亮一张错误卡。cookie 失效时回到引擎根路径
                   重新走一次带 token 的握手（token 一次性，不能重放旧 URL）。 */
                if (code == 401 && web != null && readyUrl != null) {
                    com.hubmobile.app.harness.UpstreamParity.diagLog(act, "HTTP", "主框架 401，重新握手");
                    try { web.loadUrl(com.hubmobile.app.HarnessBridge.homeUrl(act)); }
                    catch (Throwable ignored) {}
                    return;
                }
                if (isEngineUrl(v.getUrl()) || readyUrl != null)
                    enterFallback("引擎返回 HTTP " + code);
                else
                    showPageError("引擎返回 HTTP " + code);
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            /** window.open / target=_blank 的落地点。照上游 v1.39.6 的 probe 写法。 */
            @Override public boolean onCreateWindow(WebView view, boolean isDialog,
                                                    boolean isUserGesture,
                                                    android.os.Message resultMsg) {
                try {
                    final WebView probe = new WebView(act);
                    final boolean[] routed = {false};
                    probe.setWebViewClient(new WebViewClient() {
                        private boolean routeOnce(String u) {
                            /* 登录对话框先 window.open("about:blank") 预占标签页，
                               再 location.replace(授权地址)。空页不能算「已落地」，
                               否则真正的授权地址会被这道闸挡掉（上游 2026-10-18 复现）。 */
                            if (u == null || u.isEmpty() || "about:blank".equals(u)) return false;
                            if (routed[0]) return true;
                            routed[0] = true;
                            /* 别在 probe 自己的回调里 destroy —— 有些内核会
                               吞掉紧随其后的导航回调。延迟到下一帧再销毁。 */
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    try { probe.destroy(); } catch (Throwable ignored) {}
                                }
                            });
                            return routeNewWindow(u);
                        }
                        @Override public boolean shouldOverrideUrlLoading(WebView v,
                                                                          WebResourceRequest r) {
                            return routeOnce(r == null ? null : r.getUrl().toString());
                        }
                        @Override @SuppressWarnings("deprecation")
                        public boolean shouldOverrideUrlLoading(WebView v, String url) {
                            return routeOnce(url);
                        }
                    });
                    WebView.WebViewTransport tr = (WebView.WebViewTransport) resultMsg.obj;
                    tr.setWebView(probe);
                    resultMsg.sendToTarget();
                    return true;
                } catch (Throwable t) {
                    return false;
                }
            }
            /** 阶段 AB：前端 JS 异常上游会落到 diagLog 环形缓冲（DSHJS），
                本版之前只进 logcat，导出日志时啥也看不到。 */
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                try {
                    if (m == null) return true;
                    String lvl = String.valueOf(m.messageLevel());
                    String msg = String.valueOf(m.message());
                    boolean bad = "ERROR".equals(lvl) || "WARNING".equals(lvl);
                    String low = msg.toLowerCase(java.util.Locale.US);
                    if (bad || low.contains("plugin") || low.contains("console")
                            || low.contains("slot") || low.contains("inject")) {
                        com.hubmobile.app.harness.UpstreamParity.diagLog(
                                act.getApplicationContext(), "DSHJS",
                                lvl + " " + m.sourceId() + ":" + m.lineNumber() + " " + msg);
                        android.util.Log.w("GithupAI",
                                lvl + " " + m.sourceId() + ":" + m.lineNumber() + " " + msg);
                    }
                } catch (Throwable ignored) {}
                return true;
            }

            /** 阶段 AB：上游 v1.40 —— 前端「添加附件 / @文件」是 <input type="file">，
                Android WebView 不实现这个回调就完全没反应。要图片时额外给「拍照」。 */
            @Override public boolean onShowFileChooser(WebView view,
                    ValueCallback<Uri[]> callback,
                    WebChromeClient.FileChooserParams params) {
                try {
                    if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                    fileChooserCallback = callback;
                    if (attachWantsImage(params)) {
                        new AlertDialog.Builder(act)
                                .setTitle("添加图片")
                                .setItems(new String[]{"拍照", "从相册/文件选择"},
                                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                if (which == 0) launchCameraCapture();
                                else launchFilePicker(params);
                            }})
                                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override public void onCancel(DialogInterface d) { cancelFileChooser(); }
                        }).show();
                        return true;
                    }
                    return launchFilePicker(params);
                } catch (Throwable t) {
                    com.hubmobile.app.harness.UpstreamParity.diagLog(act, "ATTACH", "onShowFileChooser 失败 " + t);
                    cancelFileChooser();
                    return false;
                }
            }
        });
    }

    /** 主框架导航判据：回环放行，空白页拦掉，其余外发浏览器。 */
    private boolean shouldOverrideMain(String url) {
        if (url == null || url.isEmpty()) return true;
        try {
            Uri u = Uri.parse(url);
            String scheme = u.getScheme();
            if (scheme == null) return true;
            String low = scheme.toLowerCase(java.util.Locale.US);
            /* 阶段 AB：白屏兜底页是本地静态页，必须交回 WebView 自己加载，
               否则会被下面的 openExternally 判成「非 http」直接拦掉。 */
            if (FALLBACK_URL.equals(url)) return false;
            /* 主框架的 about:/data:/blob:/javascript: 一律拦，绝不让它把 GUI 顶成白屏。 */
            if (low.equals("about") || low.equals("data") || low.equals("blob")
                    || low.equals("javascript")) return true;
            if (isLoopback(url)) return false;          // DSH 自己，交回 WebView
            return openExternally(u);
        } catch (Throwable t) {
            return true;
        }
    }

    /** 子资源判据：只拦能碰本地文件的协议，blob:/data:/远程 iframe 放行。 */
    private boolean resourceAllowed(String url) {
        if (url == null || url.isEmpty()) return false;
        if (FALLBACK_URL.equals(url)) return true;
        String low = url.toLowerCase(java.util.Locale.US);
        if (low.startsWith("file://") || low.startsWith("content://")
                || low.startsWith("javascript:")) return false;
        return true;
    }

    /** 回环地址（含 IPv6），不只看引擎端口。 */
    private static boolean isLoopback(String url) {
        try {
            Uri u = Uri.parse(url);
            String h = u.getHost();
            if (h == null) return false;
            h = h.toLowerCase(java.util.Locale.US);
            return h.equals("127.0.0.1") || h.equals("localhost")
                    || h.equals("::1") || h.equals("[::1]");
        } catch (Throwable t) { return false; }
    }

    /**
     * 非回环链接一律外发系统浏览器，并用**选择器**。
     * 账户登录回调是回环地址，UC/夸克/QQ浏览器常把它拦掉或当搜索词，
     * 让用户能选 Chrome / 系统浏览器重试，否则会永远卡「等待中」。
     */
    private boolean openExternally(android.net.Uri u) {
        if (u == null) return true;
        String scheme = u.getScheme();
        boolean mqq = "mqqapi".equalsIgnoreCase(scheme);
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)
                && !"mailto".equalsIgnoreCase(scheme) && !"tel".equalsIgnoreCase(scheme)
                && !mqq) return true;
        try {
            Intent ext = new Intent(Intent.ACTION_VIEW, u);
            ext.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (mqq) {
                act.startActivity(ext);
            } else {
                Intent chooser = Intent.createChooser(ext, "选择浏览器完成登录");
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(chooser);
            }
            toast("登录链接已在浏览器打开；完成后切回本 App（回环回调由引擎接收）");
            return true;
        } catch (Throwable t) {
            toast("无法打开外部链接");
            return true;
        }
    }

    /** 弹窗 URL 落地：回环回主 WebView；下载走 DownloadManager；其余外发浏览器。 */
    private boolean routeNewWindow(String u) {
        if (u == null || u.isEmpty()) return true;
        if (u.startsWith("file://") || u.contains(".apk")) {
            openDownload(u);
            return true;
        }
        try {
            if (isLoopback(u)) {
                final String dest = u;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (!destroyed && web != null) web.loadUrl(dest);
                    }
                });
                return true;
            }
            openExternally(android.net.Uri.parse(u));
        } catch (Throwable ignored) {}
        return true;
    }

    /** 下载：远程交给 DownloadManager；本地 file:// 直接外发。 */
    private void openDownload(String url) {
        if (url == null || url.isEmpty()) return;
        try {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                /* 本地 file:// 用 LogShareProvider 暴露 content:// 再交给系统，
                   避免 Android 7+ 的 FileUriExposedException。 */
                try {
                    String path = android.net.Uri.decode(url.substring("file://".length()));
                    android.net.Uri cu = com.hubmobile.app.harness.LogShareProvider
                            .uriForAbs(act.getApplicationContext(), path);
                    if (cu != null) {
                        boolean apk = path.toLowerCase(java.util.Locale.US).endsWith(".apk");
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(cu, apk ? "application/vnd.android.package-archive" : "*/*");
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_ACTIVITY_NEW_TASK);
                        act.startActivity(i);
                        return;
                    }
                } catch (Throwable ignored) {}
                openExternally(android.net.Uri.parse(url));
                return;
            }
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    act.getSystemService(android.content.Context.DOWNLOAD_SERVICE);
            if (dm == null) throw new IllegalStateException("系统下载服务不可用");
            android.app.DownloadManager.Request req =
                    new android.app.DownloadManager.Request(android.net.Uri.parse(url));
            req.setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS,
                    "githup-" + System.currentTimeMillis());
            dm.enqueue(req);
            toast("已开始下载，通知栏可查看");
        } catch (Throwable t) {
            openExternally(android.net.Uri.parse(url));
        }
    }

    private void toast(final String msg) {
        ui.post(new Runnable() {
            @Override public void run() {
                try { Toast.makeText(act, msg, Toast.LENGTH_LONG).show(); }
                catch (Throwable ignored) {}
            }
        });
    }

    /** 每次页面开始/结束时注入一次；幂等标记防止重复覆盖。 */
    private void injectExternalBridge(WebView v) {
        if (v == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 19) v.evaluateJavascript(EXTERNAL_BRIDGE_JS, null);
            else v.loadUrl("javascript:" + android.net.Uri.encode(EXTERNAL_BRIDGE_JS));
        } catch (Throwable ignored) {}
    }

    /**
     * 阶段 U：**快速**外发。对齐上游 ctlAction("open.url") —— 直接
     * startActivity(ACTION_VIEW)，不等选择器，所以点登录立刻跳浏览器。
     * 只有直发失败才回落到 openExternally() 的选择器。
     */
    private void openExternalFast(final String url) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (destroyed || url == null || url.isEmpty()) return;
                try {
                    if (isLoopback(url)) {
                        if (web != null) web.loadUrl(url);
                        return;
                    }
                    Intent ext = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    ext.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    act.startActivity(ext);
                    toast("已跳转到浏览器；完成授权后切回本 App");
                } catch (Throwable t) {
                    try { openExternally(Uri.parse(url)); } catch (Throwable ignored) {}
                }
            }
        });
    }

    /** 白屏兜底：把装载卡亮出来、Web 容器收起、显示错误与重试入口。 */
    // ==================== 阶段 AB：附件通道（上游 v1.40） ====================

    private boolean attachWantsImage(WebChromeClient.FileChooserParams params) {
        try {
            String[] acc = params == null ? null : params.getAcceptTypes();
            if (acc == null || acc.length == 0) return true;
            for (String s : acc) {
                if (s == null) continue;
                String t = s.toLowerCase(java.util.Locale.US);
                if (t.contains("image") || t.contains("*/*") || t.trim().isEmpty()) return true;
            }
            return false;
        } catch (Throwable t) { return true; }
    }

    private boolean launchFilePicker(WebChromeClient.FileChooserParams params) {
        try {
            Intent i = params != null && Build.VERSION.SDK_INT >= 21
                    ? params.createIntent() : new Intent(Intent.ACTION_GET_CONTENT);
            if (i == null) i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            act.startActivityForResult(Intent.createChooser(i, "选择附件"), REQ_FILE_CHOOSER);
            return true;
        } catch (Throwable t) {
            com.hubmobile.app.harness.UpstreamParity.diagLog(act, "ATTACH", "文件选择器启动失败 " + t);
            cancelFileChooser();
            return false;
        }
    }

    private void launchCameraCapture() {
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "githup_" + System.currentTimeMillis() + ".jpg");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cameraOutputUri = act.getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            act.startActivityForResult(i, REQ_CAMERA_CAPTURE);
        } catch (Throwable t) {
            com.hubmobile.app.harness.UpstreamParity.diagLog(act, "ATTACH", "拍照启动失败 " + t);
            cameraOutputUri = null;
            launchFilePicker(null);       // 相机不可用时退回文件选择，绝不「点了没反应」
        }
    }

    private void cancelFileChooser() {
        if (fileChooserCallback != null) {
            try { fileChooserCallback.onReceiveValue(null); } catch (Throwable ignored) {}
            fileChooserCallback = null;
        }
    }

    /** 由 MainActivity.onActivityResult 转发（AiPanel 不是 Activity，自己收不到）。 */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            Uri[] uris = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                try { uris = WebChromeClient.FileChooserParams.parseResult(resultCode, data); }
                catch (Throwable ignored) {}
                if (uris == null && data.getData() != null) uris = new Uri[]{data.getData()};
            }
            if (fileChooserCallback != null) {
                try { fileChooserCallback.onReceiveValue(uris); } catch (Throwable ignored) {}
                fileChooserCallback = null;
            }
            return true;
        }
        if (requestCode == REQ_CAMERA_CAPTURE) {
            Uri[] uris = null;
            if (resultCode == Activity.RESULT_OK && cameraOutputUri != null)
                uris = new Uri[]{cameraOutputUri};
            if (fileChooserCallback != null) {
                try { fileChooserCallback.onReceiveValue(uris); } catch (Throwable ignored) {}
                fileChooserCallback = null;
            }
            cameraOutputUri = null;
            return true;
        }
        return false;
    }

    // ==================== 阶段 AB：白屏兜底页（上游 v1.52） ====================

    /** 主框架加载失败 → 切到不经过引擎的静态页，后台每 2s 探测，通了自动回主界面。 */
    private void enterFallback(final String why) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (destroyed || web == null) return;
                if (fallbackActive) return;
                fallbackActive = true;
                fallbackWhy = why == null ? "" : why;
                fallbackSince = System.currentTimeMillis();
                com.hubmobile.app.harness.UpstreamParity.diagLog(act, "FALLBACK", "进入兜底页：" + fallbackWhy);
                try {
                    FrameLayout holder = (FrameLayout) panel.getTag();
                    if (holder != null) holder.setVisibility(View.VISIBLE);
                    loading.setVisibility(View.GONE);
                    web.loadUrl(FALLBACK_URL);
                } catch (Throwable ignored) {}
                ui.removeCallbacks(fallbackProbe);
                ui.postDelayed(fallbackProbe, 2000);
            }
        });
    }

    /** 引擎被看门狗重拉后，AiPanel 主动回主界面（不白屏等用户手点）。 */
    private void maybeReloadAfterRevive() {
        try {
            long e = com.hubmobile.app.harness.UpstreamParity.reviveEpoch();
            if (e != seenReviveEpoch) {
                seenReviveEpoch = e;
                if (seenReviveEpoch > 0 && web != null) {
                    fallbackActive = false;
                    ui.removeCallbacks(fallbackProbe);
                    fallbackWhy = "";
                    web.loadUrl(com.hubmobile.app.HarnessBridge.homeUrl(act));
                }
            }
        } catch (Throwable ignored) {}
    }

    private void showPageError(final String msg) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                try {
                    FrameLayout holder = (FrameLayout) panel.getTag();
                    if (holder != null) holder.setVisibility(View.GONE);
                } catch (Throwable ignored) {}
                if (loading != null) loading.setVisibility(View.VISIBLE);
                if (err != null) { err.setText(msg); err.setVisibility(View.VISIBLE); }
                if (retryTag != null) retryTag.setVisibility(View.VISIBLE);
                if (meta != null) meta.setText("界面未就绪 · 引擎仍在后台运行");
            }
        });
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
            return p >= com.hubmobile.app.harness.EnginePorts.DEFAULT
                    && p < com.hubmobile.app.harness.EnginePorts.MAX;
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
        applySoftInput(false);
        try {
            if (kbWatcher != null)
                act.getWindow().getDecorView().getViewTreeObserver()
                   .removeOnGlobalLayoutListener(kbWatcher);
        } catch (Throwable ignored) {}
        disableNfc();
        try { NfcStore.setArmListener(null); } catch (Throwable ignored) {}
        if (draftInput != null) draftInput.destroy();
        if (web != null) {
            try { web.destroy(); } catch (Throwable ignored) {}
            web = null;
        }
        ui.removeCallbacksAndMessages(null);
        ui.removeCallbacks(fallbackProbe);
        ui.removeCallbacks(reviveWatcher);
        cancelFileChooser();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics());
    }
}
