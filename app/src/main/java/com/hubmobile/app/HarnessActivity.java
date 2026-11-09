package com.hubmobile.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * githup AI —— 第二个桌面入口。
 *
 * 启动页沿用底座 githup 的品牌版式（深色底 / 圆形 logo / githup / GitHub 客户端），
 * 并在其上按「开机对话」的形式呈现引擎装载：主体是 githup，DeepSeek 是被请进来的
 * 引擎。对话念完、payload 展开完、本地 node 起来后，才淡出进入 AI Web UI。
 *
 * 进入 Web UI 后顶部保留一条 githup 品牌条，避免整屏只剩 DeepSeek 的字样。
 */
public class HarnessActivity extends Activity {

    private static final int BG = Color.parseColor("#0d1117");
    private static final int CARD_BG = Color.parseColor("#151b2b");
    private static final int LINE = Color.parseColor("#3a4257");
    private static final int TXT = Color.parseColor("#e6edf3");
    private static final int MUTED = Color.parseColor("#8b949e");
    private static final int ACCENT = Color.parseColor("#6d8bff");

    /** 开机对话：左=我的软件，右=DeepSeek。文案按产品要求固定，不随上游变化。 */
    private static final String[] SPEAKER = {"githup", "DeepSeek", "githup", "DeepSeek"};
    private static final String[] LINES = {
            "你好，DeepSeek",
            "很高兴为您服务",
            "让我们共同进步",
            "好"
    };
    private static final boolean[] RIGHT = {true, false, true, false};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private FrameLayout boot;
    private TextView dialogue;
    private TextView bootMeta;
    private TextView bootErr;
    private View brandBar;
    private boolean finished = false;
    private long totalEntries = 41426L;
    private long payloadBytes = 159794879L;
    private long lastEntries = 0, lastWritten = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        // ---------- 1) 品牌启动页（图 2 的版式 + 图 3 的开机对话设计） ----------
        boot = buildBootPage();
        root.addView(boot, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // ---------- 2) AI Web UI + githup 品牌条 ----------
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setVisibility(View.GONE);
        brandBar = buildBrandBar();
        content.addView(brandBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));
        web = new WebView(this);
        configure(web);
        content.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        mContent = content;
        setContentView(root);

        // 第二层：本地桥要在 WebView 加载前就监听，否则设置页一进来就 fetch 3081 失败
        com.hubmobile.app.harness.LocalBridgeServer.start(getApplicationContext());

        playDialogue();
        startEngine();
    }

    private LinearLayout mContent;

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /** 点阵背景 + 四角取景框，复刻开机画面的质感。 */
    private static final class DecorView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        DecorView(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            p.setColor(Color.parseColor("#1b2337"));
            int step = 26;
            for (int y = step / 2; y < getHeight(); y += step)
                for (int x = step / 2; x < getWidth(); x += step)
                    cv.drawCircle(x, y, 1.1f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(LINE);
            int m = 24, len = 46;
            int w = getWidth(), h = getHeight();
            // 左上
            cv.drawLine(m, m + len, m, m, p);
            cv.drawLine(m, m, m + len, m, p);
            // 右上
            cv.drawLine(w - m - len, m, w - m, m, p);
            cv.drawLine(w - m, m, w - m, m + len, p);
            // 左下
            cv.drawLine(m, h - m - len, m, h - m, p);
            cv.drawLine(m, h - m, m + len, h - m, p);
            // 右下
            cv.drawLine(w - m - len, h - m, w - m, h - m, p);
            cv.drawLine(w - m, h - m, w - m, h - m - len, p);
        }
    }

    private FrameLayout buildBootPage() {
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(BG);
        f.addView(new DecorView(this), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(28), dp(70), dp(28), dp(28));

        // githup 品牌：与底座启动页同一套视觉
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        int ls = dp(84);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ls, ls);
        lp.topMargin = dp(18);
        col.addView(logo, lp);

        TextView title = new TextView(this);
        title.setText("githup");
        title.setTextColor(TXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        col.addView(title, lp);

        TextView sub = new TextView(this);
        sub.setText("GitHub 客户端 · AI 引擎装载中");
        sub.setTextColor(MUTED);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        col.addView(sub, lp);

        // 开机对话卡片
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(18), dp(22), dp(18));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(CARD_BG);
        gd.setCornerRadius(dp(18));
        gd.setStroke(1, LINE);
        card.setBackground(gd);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(52);
        col.addView(card, cardLp);

        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView head = new TextView(this);
        head.setText("G I T H U P   ×   D E E P S E E K");
        head.setTextColor(TXT);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        head.setTypeface(Typeface.MONOSPACE);
        headRow.addView(head, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView ver = new TextView(this);
        ver.setText("V 1 . 2 . 2 0   ·   B O O T");
        ver.setTextColor(MUTED);
        ver.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        ver.setTypeface(Typeface.MONOSPACE);
        ver.setGravity(Gravity.END);
        headRow.addView(ver);
        card.addView(headRow);

        View sep = new View(this);
        sep.setBackgroundColor(LINE);
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        sepLp.topMargin = dp(14); sepLp.bottomMargin = dp(16);
        card.addView(sep, sepLp);

        dialogue = new TextView(this);
        dialogue.setTextColor(TXT);
        dialogue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        dialogue.setLineSpacing(dp(9), 1f);
        dialogue.setMinHeight(dp(150));
        dialogue.setTypeface(Typeface.MONOSPACE);
        card.addView(dialogue);

        bootErr = new TextView(this);
        bootErr.setTextColor(Color.parseColor("#ff8a80"));
        bootErr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        bootErr.setTypeface(Typeface.MONOSPACE);
        bootErr.setLineSpacing(dp(3), 1f);
        bootErr.setVisibility(View.GONE);
        card.addView(bootErr);

        // 底部 BOOT SEQUENCE 角标：真实进度写进这里
        bootMeta = new TextView(this);
        bootMeta.setTextColor(MUTED);
        bootMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        bootMeta.setTypeface(Typeface.MONOSPACE);
        bootMeta.setLineSpacing(dp(5), 1f);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = dp(34);
        col.addView(bootMeta, metaLp);
        updateMeta("正在准备 AI 引擎文件…");

        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        flp.gravity = Gravity.CENTER;
        f.addView(col, flp);
        return f;
    }

    private LinearLayout buildBrandBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#10151f"));
        bar.setPadding(dp(14), 0, dp(14), 0);
        TextView l = new TextView(this);
        l.setText("githup AI");
        l.setTextColor(TXT);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        l.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(l, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView r = new TextView(this);
        r.setText("由 DeepSeek Harness 引擎驱动");
        r.setTextColor(MUTED);
        r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        bar.addView(r);
        return bar;
    }

    /** 打字机逐句播放四句问候；念完后停在最后一屏等引擎就绪。 */
    private void playDialogue() {
        final int[] idx = {0}, pos = {0};
        final StringBuilder sb = new StringBuilder();
        final Runnable step = new Runnable() {
            @Override public void run() {
                if (finished || idx[0] >= LINES.length) return;
                String name = SPEAKER[idx[0]];
                String line = LINES[idx[0]];
                if (pos[0] == 0) sb.append(RIGHT[idx[0]] ? "        " : "·  ")
                        .append(name).append("  »  ");
                if (pos[0] < line.length()) {
                    sb.append(line.charAt(pos[0]));
                    pos[0]++;
                    dialogue.setText(sb.toString() + " |");
                    ui.postDelayed(this, 52);
                } else {
                    sb.append("\n");
                    dialogue.setText(sb.toString());
                    idx[0]++; pos[0] = 0;
                    ui.postDelayed(this, 480);
                }
            }
        };
        ui.postDelayed(step, 450);
    }

    private void updateMeta(String stage) {
        if (bootMeta == null) return;
        String mb = String.format(java.util.Locale.US, "%.0f", payloadBytes / 1048576.0);
        bootMeta.setText("B O O T   S E Q U E N C E\n"
                + "P A Y L O A D   " + mb + "   M B\n"
                + "C O L D   S T A R T\n"
                + "已 校 验   " + String.format("%,d", lastEntries) + " / "
                + String.format("%,d", totalEntries) + "   ·   本 次 新 增 "
                + String.format("%,d", lastWritten) + "\n"
                + stage);
    }

    private void startEngine() {
        String reuse = HarnessBridge.existingUrl();
        if (reuse != null) {
            showWeb(reuse);
            return;
        }
        HarnessBridge.prepare(getApplicationContext(), new HarnessBridge.Callback() {
            @Override public void onStage(String text) {
                if (!finished) updateMeta(text);
            }
            @Override public void onProgress(long entries, long written, long total) {
                if (finished) return;
                lastEntries = entries;
                lastWritten = written;
                if (total > 0) totalEntries = total;
                updateMeta("正在展开 AI 引擎文件…");
            }
            @Override public void onReady(String url) {
                showWeb(url);
            }
            @Override public void onError(String msg) {
                if (finished) return;
                String brief = msg.length() > 900 ? "…" + msg.substring(msg.length() - 900) : msg;
                bootErr.setVisibility(View.VISIBLE);
                bootErr.setText("启动失败：" + brief + "\n\n（可重试：退出后重进；若仍失败请清应用数据）");
                updateMeta("装载中断");
            }
        });
    }

    private void showWeb(String url) {
        finished = true;
        // 给打字机留一点收尾时间，再淡出
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                AlphaAnimation out = new AlphaAnimation(1f, 0f);
                out.setDuration(320);
                boot.startAnimation(out);
                boot.setVisibility(View.GONE);
                mContent.setVisibility(View.VISIBLE);
                web.loadUrl(url);
            }
        }, 900);
    }

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
        // 控制台页靠 window.dshshell 判断「是不是在安卓 App 内」，不注入整页功能都灰掉
        w.addJavascriptInterface(
                new com.hubmobile.app.harness.ShellBridge(this), "dshshell");
        w.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return false;
            }
        });
        w.setWebChromeClient(new WebChromeClient());
    }

    @Override
    public void onBackPressed() {
        if (mContent != null && mContent.getVisibility() == View.VISIBLE && web.canGoBack())
            web.goBack();
        else super.onBackPressed();
    }
}
