package com.hubmobile.app.harness;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.hubmobile.app.HarnessActivity;
import com.hubmobile.app.R;

/**
 * githup AI 的悬浮桌宠（第二层能力）。
 *
 * 用纯 Android framework 实现，不依赖 Shizuku / 隐藏 API：
 *   · WindowManager 悬浮球，可拖动、点击说话、长按打开 AI 界面
 *   · 「流体云」：引擎侧推送 AI 回复时在桌宠旁飘一张卡片
 *   · 「悬浮屏预览」：虚拟屏需要 Shizuku 特权进程，本版给出明确的降级提示卡片
 *
 * 状态写进 dsh_prefs，由 3081 本地桥的 /overlay?action=status 读回给网页设置页。
 */
public class OverlayService extends Service {

    private static final String TAG = "gh-overlay";
    private static final String PREFS = "dsh_prefs";
    private static final String CHANNEL = "githup_overlay";
    private static final String ACT_SHOW = "com.hubmobile.app.harness.SHOW";
    private static final String ACT_HIDE = "com.hubmobile.app.harness.HIDE";
    private static final String ACT_PANEL = "com.hubmobile.app.harness.PANEL";
    private static final String ACT_CARD = "com.hubmobile.app.harness.CARD";
    private static final String EXTRA_TEXT = "text";

    private static volatile OverlayService sInstance;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View petView;
    private View cardView;
    private WindowManager.LayoutParams petLp, cardLp;
    private boolean visible = false;
    private boolean fluid = false;
    private boolean vscreen = false;
    private final String[] lines = {
            "你好，我是 githup AI", "很高兴为您服务", "让我们共同进步",
            "有需要就点我一下", "DeepSeek 引擎已就绪"
    };
    private int lineIdx = 0;

    public static OverlayService get() { return sInstance; }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    public static boolean canDraw(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    /** 供本地桥读取的当前状态（不依赖 Service 是否已绑定）。 */
    public static String statusJson(Context c) {
        OverlayService s = sInstance;
        SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean running = s != null;
        boolean vis = s != null && s.visible;
        return "{\"ok\":true,\"running\":" + running
                + ",\"visible\":" + vis
                + ",\"fluid\":" + (s != null ? s.fluid : p.getBoolean("fluid", false))
                + ",\"vscreen\":" + (s != null ? s.vscreen : p.getBoolean("vscreen", false))
                + ",\"permission\":" + canDraw(c)
                + ",\"engine\":\"githup AI\"}";
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        fluid = prefs().getBoolean("fluid", false);
        vscreen = prefs().getBoolean("vscreen", false);
        startForegroundNotification();
    }

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "githup AI 桌宠",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, HarnessActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 11, open, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        Notification n = b.setContentTitle("githup AI 桌宠")
                .setContentText("点开可回到 AI 界面")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        try { startForeground(11, n); } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent != null ? intent.getAction() : null;
        if (ACT_HIDE.equals(act)) {
            removePet();
        } else if (ACT_PANEL.equals(act)) {
            openPanel();
        } else if (ACT_CARD.equals(act)) {
            String text = intent != null ? intent.getStringExtra(EXTRA_TEXT) : null;
            if (fluid && text != null && !text.isEmpty()) pushCard(text);
        } else {
            showPet();
        }
        return START_STICKY;
    }

    private void openPanel() {
        try {
            Intent i = new Intent(this, HarnessActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Throwable ignored) {}
    }

    /** 供本地桥调用：切换流体云开关。 */
    public void setFluid(boolean on) {
        fluid = on;
        prefs().edit().putBoolean("fluid", on).apply();
        if (!on) removeCard();
    }

    /** 供本地桥调用：切换悬浮屏预览开关。虚拟屏需 Shizuku，这里明确降级提示。 */
    public void setVscreen(boolean on) {
        vscreen = on;
        prefs().edit().putBoolean("vscreen", on).apply();
        if (on) {
            showCard("悬浮屏预览需要 Shizuku 特权进程\n本版以悬浮卡片替代实时画面", 6000);
        }
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void showPet() {
        if (!canDraw(this)) {
            ui.post(new Runnable() {
                @Override public void run() {
                    Toast.makeText(OverlayService.this,
                            "请先授予「显示在其他应用上层」权限", Toast.LENGTH_LONG).show();
                }
            });
            return;
        }
        if (petView != null) { visible = true; return; }
        try {
            petView = buildPet();
            petLp = new WindowManager.LayoutParams(
                    dp(76), dp(76), overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            petLp.gravity = Gravity.TOP | Gravity.START;
            petLp.x = dp(24);
            petLp.y = dp(180);
            wm.addView(petView, petLp);
            visible = true;
        } catch (Throwable t) {
            petView = null;
            visible = false;
        }
    }

    private View buildPet() {
        final FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#151b2b"));
        bg.setStroke(dp(2), Color.parseColor("#6d8bff"));
        root.setBackground(bg);

        TextView face = new TextView(this);
        face.setText("鲸");
        face.setTextColor(Color.parseColor("#e6edf3"));
        face.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        face.setTypeface(Typeface.DEFAULT_BOLD);
        face.setGravity(Gravity.CENTER);
        root.addView(face, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 拖动 + 轻点说话 + 长按进 AI
        root.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY; float rawX, rawY; boolean moved;
            long downAt;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = petLp.x; startY = petLp.y;
                        rawX = e.getRawX(); rawY = e.getRawY();
                        moved = false; downAt = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - rawX), dy = (int) (e.getRawY() - rawY);
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                        if (moved) {
                            petLp.x = startX + dx; petLp.y = startY + dy;
                            try { wm.updateViewLayout(petView, petLp); } catch (Throwable ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        long dur = System.currentTimeMillis() - downAt;
                        if (!moved && dur > 550) {
                            openPanel();
                        } else if (!moved) {
                            speak();
                        }
                        return true;
                }
                return false;
            }
        });
        return root;
    }

    private void speak() {
        String s = lines[lineIdx % lines.length];
        lineIdx++;
        showCard(s, 2600);
    }

    /** 流体云卡片：贴着桌宠右侧飘出，到点自动收起。 */
    private void showCard(final String text, final int millis) {
        ui.post(new Runnable() {
            @Override public void run() { pushCard(text); }
        });
        ui.postDelayed(new Runnable() {
            @Override public void run() { removeCard(); }
        }, millis);
    }

    private void pushCard(String text) {
        if (!canDraw(this)) return;
        try {
            removeCard();
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(10), dp(14), dp(10));
            GradientDrawable g = new GradientDrawable();
            g.setColor(Color.parseColor("#1b2337"));
            g.setCornerRadius(dp(14));
            g.setStroke(1, Color.parseColor("#3a4257"));
            card.setBackground(g);
            TextView t = new TextView(this);
            t.setText(text);
            t.setTextColor(Color.parseColor("#e6edf3"));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            t.setLineSpacing(dp(3), 1f);
            t.setMaxWidth(dp(240));
            card.addView(t);

            cardLp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            cardLp.gravity = Gravity.TOP | Gravity.START;
            cardLp.x = (petLp != null ? petLp.x : dp(24)) + dp(82);
            cardLp.y = (petLp != null ? petLp.y : dp(180)) + dp(4);
            wm.addView(card, cardLp);
            cardView = card;
        } catch (Throwable ignored) {}
    }

    private void removeCard() {
        if (cardView != null) {
            try { wm.removeView(cardView); } catch (Throwable ignored) {}
            cardView = null;
        }
    }

    private void removePet() {
        removeCard();
        if (petView != null) {
            try { wm.removeView(petView); } catch (Throwable ignored) {}
            petView = null;
        }
        visible = false;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    public void onDestroy() {
        removePet();
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    /** 本地桥用的动作入口（静态，避免 Binder）。 */
    public static void dispatch(Context c, String action, String text) {
        Intent i = new Intent(c, OverlayService.class);
        if ("hide".equals(action)) i.setAction(ACT_HIDE);
        else if ("panel".equals(action)) i.setAction(ACT_PANEL);
        else if ("card".equals(action)) { i.setAction(ACT_CARD); i.putExtra(EXTRA_TEXT, text); }
        else i.setAction(ACT_SHOW);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable ignored) {}
    }
}
