package com.hubmobile.app.harness;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * 首次使用 · 配置手机权限 —— 与上游 DSH 一致的 10 步翻页式权限向导。
 *
 * 上游实现在 com.deepseek.harness.MainActivity 的 2488–2898 行
 * （v1.13.12 重做的翻页式引导：9 个权限页 + 1 个完成页 = 第 1/10 步 …
 * 第 10/10 步），每页一权限、讲清用途/不给的后果/当前状态，可单页跳过，
 * 走完写 setup_done 再进引擎。
 *
 * 本包是「单图标同屏」：MainActivity 的 contentRoot 里同时有底座 WebView
 * 和 AiPanel，不能照搬上游的 setContentView(root) 把整个窗口换掉，
 * 否则 GitHub 客户端就没了。这里改成**往 contentRoot 顶层盖一层全屏 View**，
 * 走完移除；底层 AiPanel.attach() 的引擎照常后台启动，不因向导暂停。
 *
 * 配色、文案、字号、按钮、翻页逻辑逐项对齐上游源码 / colors.xml，
 * 差异只有四处，全部在下面注释里标明。
 */
public final class PermissionWizard {

    private static final String PREFS = "perm_wizard";
    private static final String KEY_DONE = "setup_done";
    private static final int REQ_STORAGE = 9001;
    private static final int REQ_NOTIFICATION = 9002;

    /* 上游 res/values/colors.xml 原值（浅色主题）。不引 R.color 是为了避免
       与本包已有资源同名冲突；深/浅由系统字体与背景常量一起给出。 */
    private static final int C_ACCENT = 0xFF4D6BFE;
    private static final int C_BG = 0xFFF7F8FB;
    private static final int C_TEXT = 0xFF1F2328;
    private static final int C_SUB = 0xFF6B7280;
    private static final int C_GREEN = 0xFF1F9D6B;
    private static final int C_RED = 0xFFD9503F;

    private static PermissionWizard sActive;

    private final Activity act;
    private final FrameLayout host;
    private final ArrayList<Page> pages = new ArrayList<Page>();
    private LinearLayout root, body;
    private TextView dots, skip, statusView;
    private Button prev, next, actionBtn;
    private int index = 0;
    private int savedSysUi = -1;
    private volatile boolean rootOk = false;
    private volatile boolean shizukuInstalled = false;
    private boolean finished = false;

    /** 一页 = 一个权限。provider 判状态，action 是「去授权」。 */
    private static class Page {
        String title, desc, actionLabel = "去授权";
        Provider provider;
        Runnable action;
    }
    private interface Provider { boolean granted(); }

    private PermissionWizard(Activity a, FrameLayout h) {
        act = a; host = h;
    }

    // ==================== 对外入口 ====================

    public static boolean isDone(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DONE, false);
    }

    /** 首次启动：没走过就挂上去。返回是否真的显示了。 */
    public static boolean showIfNeeded(Activity a, FrameLayout h) {
        if (a == null || h == null || sActive != null) return false;
        if (isDone(a)) return false;
        build(a, h);
        return true;
    }

    /** 阶段 AH：隐藏入口（长按设置页「设备类型」行）重新打开 10 步向导。
     *  设置页不再显示可见入口，功能保留；强制打开，不改 done 标记。 */
    public static void reopen(Activity a, FrameLayout h) {
        if (a == null || h == null) return;
        if (sActive != null) return;
        build(a, h);
    }

    public static boolean isActive() { return sActive != null && !sActive.finished; }

    /** 从系统设置页授权回来，刷新当前页状态与按钮。 */
    public static void refreshActive() {
        final PermissionWizard w = sActive;
        if (w == null || w.finished) return;
        w.act.runOnUiThread(new Runnable() {
            @Override public void run() { w.syncStatus(); }
        });
    }

    public static void onPermissionsResult(int req, String[] perms, int[] results) {
        if (req != REQ_STORAGE && req != REQ_NOTIFICATION) return;
        refreshActive();
    }

    /** 返回键：向导期间消费掉，等价于「跳过这页」，不退出 App。 */
    public static boolean onBack() {
        PermissionWizard w = sActive;
        if (w == null || w.finished) return false;
        w.go(+1);
        return true;
    }

    /** 首次向导显示期间先别让底座自己弹通知权限框，避免两个权限框叠一起。 */
    public static boolean willShow(Context c) {
        return sActive == null && !isDone(c);
    }

    private static void build(Activity a, FrameLayout h) {
        PermissionWizard w = new PermissionWizard(a, h);
        sActive = w;
        w.buildPages();
        w.mount();
        w.probePrivilegedAsync();
    }

    // ==================== 10 页（顺序、文案与上游一致） ====================

    private void buildPages() {
        pages.clear();

        Page p1 = new Page();
        p1.title = "存储权限";
        p1.desc = "读写手机上的文件：导入/导出内容、把 AI 生成的文件存到手机、写日志与运行数据。\n\n不给的话，AI 无法保存任何文件。";
        p1.provider = new Provider() { @Override public boolean granted() {
            return act.checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED
                    && act.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED;
        }};
        p1.action = new Runnable() { @Override public void run() {
            try {
                act.requestPermissions(new String[]{
                        "android.permission.READ_EXTERNAL_STORAGE",
                        "android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_STORAGE);
            } catch (Throwable ignored) {}
        }};
        pages.add(p1);

        Page p2 = new Page();
        p2.title = "所有文件访问";
        p2.desc = "访问手机上所有文件（Android 11 及以上需要单独授权，11 以下由存储权限覆盖）。\n\nAI 读写你的项目文件、整理文档都靠它。";
        p2.provider = new Provider() { @Override public boolean granted() {
            if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
            return act.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED;
        }};
        p2.action = new Runnable() { @Override public void run() {
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + act.getPackageName()));
                    act.startActivity(i);
                } catch (Throwable e) {
                    openSystemSetting(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                }
            } else {
                try {
                    act.requestPermissions(new String[]{
                            "android.permission.READ_EXTERNAL_STORAGE",
                            "android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_STORAGE);
                } catch (Throwable ignored) {}
            }
        }};
        pages.add(p2);

        Page p3 = new Page();
        p3.title = "悬浮窗";
        p3.desc = "在其它应用之上显示内容：桌面小鲸鱼状态窗、流体云卡片都靠它。\n\n不给的话没有小鲸鱼桌宠，也看不到悬浮预览。";
        p3.provider = new Provider() { @Override public boolean granted() {
            return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(act);
        }};
        p3.action = new Runnable() { @Override public void run() {
            openSystemSetting(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
        }};
        pages.add(p3);

        Page p4 = new Page();
        p4.title = "修改系统设置";
        p4.desc = "允许读写系统设置，比如调亮度、音量、保持屏幕常亮。\n\nAI 帮你改设置时需要；不给只是这类操作不可用。";
        p4.provider = new Provider() { @Override public boolean granted() {
            return Settings.System.canWrite(act);
        }};
        p4.action = new Runnable() { @Override public void run() {
            openSystemSetting(Settings.ACTION_MANAGE_WRITE_SETTINGS);
        }};
        pages.add(p4);

        Page p5 = new Page();
        p5.title = "使用情况访问";
        p5.desc = "查看应用使用时长与统计信息，AI 才能回答「我今天用了多久微信」这类问题。\n\n不给的话应用使用统计相关功能不可用。";
        p5.provider = new Provider() { @Override public boolean granted() {
            try {
                AppOpsManager ops = (AppOpsManager) act.getSystemService(Context.APP_OPS_SERVICE);
                int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(), act.getPackageName());
                return mode == AppOpsManager.MODE_ALLOWED;
            } catch (Throwable t) { return false; }
        }};
        p5.action = new Runnable() { @Override public void run() {
            openSystemSetting(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        }};
        pages.add(p5);

        Page p6 = new Page();
        p6.title = "安装未知来源应用";
        p6.desc = "允许安装 APK：侧载应用、AI 帮你下载并安装应用时需要。\n\n不给的话 AI 无法替你安装应用。";
        p6.provider = new Provider() { @Override public boolean granted() {
            if (Build.VERSION.SDK_INT < 26) return true;
            try { return act.getPackageManager().canRequestPackageInstalls(); }
            catch (Throwable t) { return false; }
        }};
        p6.action = new Runnable() { @Override public void run() {
            openSystemSetting(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
        }};
        pages.add(p6);

        Page p7 = new Page();
        p7.title = "忽略电池优化";
        p7.desc = "让本应用在后台常驻、不被系统提前杀掉 —— 引擎要一直在跑，这是稳定在线的前提。\n\n强烈建议授权；不给的话切后台后引擎可能被杀。";
        p7.provider = new Provider() { @Override public boolean granted() {
            try {
                PowerManager pm = (PowerManager) act.getSystemService(Context.POWER_SERVICE);
                return pm != null && pm.isIgnoringBatteryOptimizations(act.getPackageName());
            } catch (Throwable t) { return false; }
        }};
        p7.action = new Runnable() { @Override public void run() {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + act.getPackageName()));
                act.startActivity(i);
            } catch (Throwable e) {
                openSystemSetting(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            }
        }};
        pages.add(p7);

        Page p8 = new Page();
        p8.title = "通知权限";
        p8.desc = "接收 AI 完成、定时提醒等通知，人不在应用里也能知道任务跑完了。\n\n不给的话收不到这些提醒（其它功能不受影响）。";
        p8.provider = new Provider() { @Override public boolean granted() {
            if (Build.VERSION.SDK_INT < 33) return true;
            return act.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
        }};
        p8.action = new Runnable() { @Override public void run() {
            if (Build.VERSION.SDK_INT >= 33) {
                if (act.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
                    try {
                        act.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},
                                REQ_NOTIFICATION);
                    } catch (Throwable ignored) {}
                } else {
                    openNotificationSettings();
                }
            } else {
                openNotificationSettings();
            }
        }};
        pages.add(p8);

        /* 差异 ①：本包未接入上游的 Shizuku hidden AIDL 服务端，
           这一页保留上游「可选」定位，状态改为 root 探测 / Shizuku 是否已装，
           动作弹说明而不是上游的 showShizukuDialog()。 */
        Page p9 = new Page();
        p9.title = "Shizuku / Root 特权（可选）";
        p9.actionLabel = "去配置";
        p9.desc = "授权后 AI 可以执行系统级操作：安装/卸载应用、改系统设置、模拟点击等。\n\n不给也完全能用 —— 文件读写、桌宠、AI 对话只需要上面的「所有文件访问」。";
        p9.provider = new Provider() { @Override public boolean granted() {
            return rootOk || shizukuInstalled;
        }};
        p9.action = new Runnable() { @Override public void run() {
            showPrivilegedDialog();
        }};
        pages.add(p9);
    }

    // ==================== 视图（对齐上游 showPermissionScreen） ====================

    private void mount() {
        try {
            View decor = act.getWindow().getDecorView();
            savedSysUi = decor.getSystemUiVisibility();
            if (Build.VERSION.SDK_INT >= 23) {
                decor.setSystemUiVisibility((savedSysUi & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
                        | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        } catch (Throwable ignored) {}

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setClickable(true);              // 吃掉下层 WebView / AI 球的触摸
        root.setPadding(0, statusBarHeight(), 0, 0);  // 底座是 edge-to-edge，手动让出状态栏
        root.setElevation(dp(24));

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(24), dp(20), dp(24), dp(8));
        dots = new TextView(act);
        dots.setTextColor(C_SUB);
        dots.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        head.addView(dots);
        TextView title = new TextView(act);
        title.setText("首次使用 · 配置手机权限");
        title.setTextColor(C_TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, dp(6), 0, 0);
        head.addView(title);
        root.addView(head);

        ScrollView scroll = new ScrollView(act);
        scroll.setFillViewport(true);
        body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(8), dp(24), dp(12));
        scroll.addView(body, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout nav = new LinearLayout(act);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(dp(20), dp(10), dp(20), dp(16));

        skip = new TextView(act);
        skip.setText("跳过这页");
        skip.setTextColor(C_SUB);
        skip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        skip.setPadding(dp(6), dp(10), dp(6), dp(10));
        skip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { go(+1); }
        });
        nav.addView(skip);

        prev = cButton("上一步", false);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        plp.leftMargin = dp(8);
        prev.setLayoutParams(plp);
        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { go(-1); }
        });
        nav.addView(prev);

        next = cButton("下一步", true);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = dp(8);
        next.setLayoutParams(nlp);
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (index >= pages.size()) finish();
                else go(+1);
            }
        });
        nav.addView(next);
        root.addView(nav);

        host.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        render();
    }

    private void render() {
        if (body == null || finished) return;
        body.removeAllViews();
        statusView = null;
        actionBtn = null;
        boolean finishPage = index >= pages.size();
        int total = pages.size() + 1;   // 9 权限 + 1 完成 = 10 步

        dots.setText(finishPage ? "准备完成 · 最后一步" : "第 " + (index + 1) + " / " + total + " 步");
        prev.setEnabled(index > 0);
        next.setText(finishPage ? "开始使用" : "下一步");
        skip.setVisibility(finishPage ? View.GONE : View.VISIBLE);
        skip.setText(index == pages.size() - 1 ? "跳过" : "跳过这页");

        if (finishPage) {
            TextView t = cText("配置完成", 22, C_TEXT, true);
            body.addView(t, cTop(dp(18)));
            body.addView(cText("权限可以随时在「我的 → 设置 → 长按『设备类型』」里重新逐项配置。\n\n现在可以进入 githup 了，AI 引擎已在后台启动。",
                    13.5f, C_SUB, false), cTop(dp(10)));
            return;
        }

        final Page pg = pages.get(index);
        body.addView(cText(pg.title, 22, C_TEXT, true), cTop(dp(14)));

        TextView d = cText(pg.desc, 14, C_SUB, false);
        d.setLineSpacing(dp(3), 1f);
        body.addView(d, cTop(dp(10)));

        LinearLayout stRow = new LinearLayout(act);
        stRow.setOrientation(LinearLayout.HORIZONTAL);
        stRow.setGravity(Gravity.CENTER_VERTICAL);
        stRow.setPadding(0, dp(18), 0, 0);
        stRow.addView(cText("当前状态：", 13, C_SUB, false));
        statusView = cText("", 13, C_SUB, false);
        stRow.addView(statusView);
        body.addView(stRow);

        actionBtn = cButton(pg.actionLabel, true);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        alp.topMargin = dp(14);
        actionBtn.setLayoutParams(alp);
        actionBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { pg.action.run(); } catch (Throwable ignored) {}
            }
        });
        body.addView(actionBtn);
        syncStatus();
    }

    /** 从系统设置授权回来时刷新：状态行 + 授权按钮（上游 refreshAllStatuses）。 */
    private void syncStatus() {
        if (finished || body == null) return;
        if (index < 0 || index >= pages.size()) return;
        Page pg = pages.get(index);
        boolean g = false;
        try { g = pg.provider.granted(); } catch (Throwable ignored) {}
        if (statusView != null) {
            statusView.setText(g ? "已授权" : "未授权");
            statusView.setTextColor(g ? C_GREEN : C_RED);
        }
        if (actionBtn != null) {
            actionBtn.setText(g ? "已授权 ✓" : pg.actionLabel);
            actionBtn.setEnabled(!g);   // 自绘背景 + 固定白字：看起来仍亮蓝，与截图一致
        }
    }

    private void go(int dir) {
        int n = index + dir;
        if (n < 0) n = 0;
        if (n > pages.size()) n = pages.size();
        if (n == index) return;
        index = n;
        render();
    }

    private void finish() {
        if (finished) return;
        finished = true;
        act.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DONE, true).apply();
        try {
            if (savedSysUi >= 0)
                act.getWindow().getDecorView().setSystemUiVisibility(savedSysUi);
        } catch (Throwable ignored) {}
        try { if (root != null && root.getParent() != null) ((ViewGroup) root.getParent()).removeView(root); }
        catch (Throwable ignored) {}
        try {
            UpstreamParity.diagLog(act.getApplicationContext(), "WIZARD",
                    "首次使用权限向导完成，setup_done=true");
        } catch (Throwable ignored) {}
        sActive = null;
    }

    // ==================== 辅助（对齐上游 cXxx / openSystemSetting） ====================

    private void openSystemSetting(String action) {
        try {
            Intent i = new Intent(action);
            i.setData(Uri.parse("package:" + act.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Throwable e) {
            try {
                Intent i = new Intent(action);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
            } catch (Throwable e2) {
                UpstreamParity.diagLog(act.getApplicationContext(), "WIZARD",
                        "打开设置失败 " + action + ": " + e2);
            }
        }
    }

    private void openNotificationSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, act.getPackageName());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Throwable e) {
            openSystemSetting(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        }
    }

    /** 差异 ②：上游 showShizukuDialog() 有完整授权链；本包如实说明并给入口。 */
    private void showPrivilegedDialog() {
        try {
            AlertDialog.Builder b = new AlertDialog.Builder(act);
            b.setTitle("Shizuku / Root 特权（可选）");
            b.setMessage("本版未接入 Shizuku 特权服务端，系统级自动化（虚拟屏实时画面、特权 shell）暂不可用。\n\n"
                    + "文件读写、桌宠、AI 对话、GitHub 辅助都不依赖它，直接点「跳过」即可。\n\n"
                    + (shizukuInstalled ? "检测到已安装 Shizuku，可点「打开 Shizuku」查看授权状态。"
                                        : "如需使用，可先安装 Shizuku 并授权。"));
            b.setPositiveButton(shizukuInstalled ? "打开 Shizuku" : "知道了", null);
            b.setNegativeButton("跳过这页", null);
            AlertDialog d = b.create();
            if (shizukuInstalled) {
                d.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
                    @Override public void onShow(android.content.DialogInterface di) {
                        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                                new View.OnClickListener() {
                                    @Override public void onClick(View v) {
                                        openShizukuApp();
                                        d.dismiss();
                                    }
                                });
                    }
                });
            }
            d.show();
        } catch (Throwable ignored) {}
    }

    private void openShizukuApp() {
        String[] pkgs = {"moe.shizuku.privileged.api", "rikka.shizuku"};
        for (String p : pkgs) {
            try {
                Intent i = act.getPackageManager().getLaunchIntentForPackage(p);
                if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); act.startActivity(i); return; }
            } catch (Throwable ignored) {}
        }
    }

    /** 差异 ③：上游 probeShizuku() 有 binder 回调；本包只做轻量 root 探测，后台线程执行。 */
    private void probePrivilegedAsync() {
        new Thread(new Runnable() {
            @Override public void run() {
                try { rootOk = RootShell.probe().ok; } catch (Throwable ignored) {}
                try {
                    PackageManager pm = act.getPackageManager();
                    for (String p : new String[]{"moe.shizuku.privileged.api", "rikka.shizuku"}) {
                        try { pm.getPackageInfo(p, 0); shizukuInstalled = true; break; }
                        catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
                act.runOnUiThread(new Runnable() {
                    @Override public void run() { syncStatus(); }
                });
            }
        }, "wizard-privileged-probe").start();
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    private int statusBarHeight() {
        try {
            int id = act.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return act.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {}
        return dp(24);
    }

    private TextView cText(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams cTop(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private Button cButton(String label, boolean primary) {
        Button b = new Button(act);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, primary ? 13.5f : 12.5f);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setMinWidth(dp(primary ? 96 : 68));
        b.setMinimumWidth(dp(primary ? 96 : 68));
        b.setPadding(dp(16), dp(9), dp(16), dp(9));
        b.setIncludeFontPadding(false);
        if (primary) {
            b.setTextColor(Color.WHITE);
            b.setBackground(shape(C_ACCENT, C_ACCENT, 0));
        } else {
            b.setTextColor(C_ACCENT);
            b.setBackground(shape(0x144D6BFE, C_ACCENT, 1));
        }
        return b;
    }

    private GradientDrawable shape(int fill, int stroke, int strokeW) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(8));
        if (strokeW > 0) g.setStroke(dp(strokeW), stroke);
        return g;
    }
}
