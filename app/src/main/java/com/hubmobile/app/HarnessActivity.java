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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Build;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * githup AI —— 阶段 K 起不再是独立桌面入口，而是从底座客户端内点开的一页。
 * 桌面只留一个 githup 图标；这里共用同一个返回栈，按返回或点左上角「‹ githup」回去。
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
    private com.hubmobile.app.harness.DraftInputBridge draftInput;
    private volatile boolean trustedDoc = false;
    private NfcAdapter nfcAdapter;
    private static final int REQ_MIC = 202;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /* 阶段 L：这里不再是桌面入口，也不再整屏展示 DSH。
         *
         * 之前两个图标的根因就是本 Activity 带 MAIN/LAUNCHER 且独立 taskAffinity：
         * 点「githup AI」= 进入 DSH 装载页，按返回 = 退回 githup，割裂成两个 APP。
         * 现在桌面只留 MainActivity，AI 是它里面的常驻面板，两个能力同屏共存。
         *
         * 但旧版桌面快捷方式、系统「分享到 githup AI」仍可能指向这里 ——
         * 直接删组件会让它们变成点不开的死图标。所以统一转回唯一主界面，
         * 并把分享内容交给面板，用户点开就直接看到 AI 展开、草稿已就位。 */
        Intent incoming = getIntent();
        if (incoming != null) {
            String a = incoming.getAction();
            if (Intent.ACTION_SEND.equals(a) || Intent.ACTION_SEND_MULTIPLE.equals(a)) {
                AiPanel.setPendingShare(new Intent(incoming));
            }
        }
        Intent main = new Intent(this, MainActivity.class);
        main.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        main.putExtra("open_ai", true);
        startActivity(main);
        finish();
        return;

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
        // 阶段 K：合并成单图标后，AI 是从 githup 里进来的，给一条明确的回程
        TextView back = new TextView(this);
        back.setText("‹ githup");
        back.setTextColor(ACCENT);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        back.setPadding(0, 0, dp(14), 0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(back);
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
                com.hubmobile.app.harness.EngineService.keepAlive(getApplicationContext());
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
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        w.setInitialScale(100);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, true);
        // 控制台页靠 window.dshshell 判断「是不是在安卓 App 内」，不注入整页功能都灰掉
        w.addJavascriptInterface(
                new com.hubmobile.app.harness.ShellBridge(this), "dshshell");
        // 聊天输入框的分享/语音草稿桥：只在可信的回环引擎文档里放行
        draftInput = new com.hubmobile.app.harness.DraftInputBridge(this,
                new com.hubmobile.app.harness.DraftInputBridge.Guard() {
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
                if (draftInput != null && trustedDoc && Build.VERSION.SDK_INT >= 19) {
                    v.evaluateJavascript(draftInput.bootstrap(), null);
                }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                trustedDoc = isEngineUrl(r.getUrl().toString());
                return false;
            }
        });
        w.setWebChromeClient(new WebChromeClient());
    }

    private boolean isEngineUrl(String url) {
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

    private void enableNfc() {
        if (nfcAdapter == null || Build.VERSION.SDK_INT < 19) return;
        try {
            nfcAdapter.enableReaderMode(this, new NfcAdapter.ReaderCallback() {
                @Override public void onTagDiscovered(Tag tag) {
                    com.hubmobile.app.harness.NfcStore.recordTag(getApplicationContext(), tag);
                }
            }, NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                    | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V
                    | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null);
            com.hubmobile.app.harness.NfcStore.setReaderActive(true);
        } catch (Throwable ignored) {}
    }

    private void disableNfc() {
        if (nfcAdapter == null || Build.VERSION.SDK_INT < 19) return;
        try { nfcAdapter.disableReaderMode(this); } catch (Throwable ignored) {}
        com.hubmobile.app.harness.NfcStore.setReaderActive(false);
    }

    private void acceptShare(Intent i) {
        if (draftInput != null && i != null) draftInput.accept(i);
    }

    @Override protected void onResume() {
        super.onResume();
        if (draftInput != null) draftInput.resume();
        if (com.hubmobile.app.harness.NfcStore.isArmed()) enableNfc();
    }

    @Override protected void onPause() {
        if (draftInput != null) draftInput.pause();
        disableNfc();
        super.onPause();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        acceptShare(i);
    }

    @Override protected void onDestroy() {
        if (draftInput != null) draftInput.destroy();
        disableNfc();
        com.hubmobile.app.harness.NfcStore.setArmListener(null);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_MIC && draftInput != null) draftInput.permissionResult(results);
    }

    @Override
    public void onBackPressed() {
        if (mContent != null && mContent.getVisibility() == View.VISIBLE && web.canGoBack())
            web.goBack();
        else super.onBackPressed();
    }
}
