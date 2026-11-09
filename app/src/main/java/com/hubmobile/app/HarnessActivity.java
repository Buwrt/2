package com.hubmobile.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * githup AI —— 第二个桌面入口。
 *
 * 不动底座 SPA：单独注册一个 LAUNCHER Activity，点「githup AI」直接进
 * DeepSeek Harness 的本地 Web UI；点「githup」仍是原来的 GitHub 客户端。
 * 两套 UI 同属一个包、同一份官方签名，互不干扰五环防护链。
 */
public class HarnessActivity extends Activity {

    private WebView web;
    private TextView status;
    private ProgressBar bar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0d1117"));

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#c9d1d9"));
        status.setTextSize(14f);
        status.setPadding(28, 56, 28, 20);
        status.setText("正在准备 AI 引擎…");
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        web = new WebView(this);
        web.setVisibility(View.GONE);
        root.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        configure(web);
        boot();
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
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return false;
            }
        });
        w.setWebChromeClient(new WebChromeClient());
    }

    private void boot() {
        String reuse = HarnessBridge.existingUrl();
        if (reuse != null) {
            showWeb(reuse);
            return;
        }
        HarnessBridge.prepare(getApplicationContext(), new HarnessBridge.Callback() {
            @Override public void onStage(String text) {
                status.setText(text);
            }
            @Override public void onReady(String url) {
                showWeb(url);
            }
            @Override public void onError(String msg) {
                bar.setVisibility(View.GONE);
                status.setText("启动失败：" + msg
                        + "\n\n可尝试：清掉本应用数据后重进，或查看 关于 → 日志。");
            }
        });
    }

    private void showWeb(String url) {
        status.setVisibility(View.GONE);
        bar.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        web.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (web.getVisibility() == View.VISIBLE && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
