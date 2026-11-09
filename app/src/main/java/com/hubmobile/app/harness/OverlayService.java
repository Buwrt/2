package com.hubmobile.app.harness;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.hubmobile.app.HarnessActivity;
import com.hubmobile.app.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Random;

/**
 * githup AI 悬浮桌宠 —— 严格按随包 pet/pet.json 实现。
 *
 * 上一版自作主张画了个 76dp 纯色圆 + 「鲸」字，压在设置页中间；而规格书要求：
 *   ballDp 40、bodyDp 96、body=pet-body.png（334×331 鲸鱼娘立绘）、
 *   cropCenter(0.45,0.34) 圆形裁切 + 描边、10 条台词、
 *   点球出气泡台词 / 点气泡开面板 / 长按说话、贴边半藏、流体云胶囊。
 *
 * 位置一律靠屏幕边缘，绝不盖在页面中间内容上。
 */
public class OverlayService extends Service {

    private static final String TAG = "gh-overlay";
    private static final String PREFS = "dsh_prefs";
    private static final String CHANNEL = "githup_overlay";
    private static final String ACT_SHOW = "com.hubmobile.app.harness.SHOW";
    private static final String ACT_HIDE = "com.hubmobile.app.harness.HIDE";
    private static final String ACT_DOCK = "com.hubmobile.app.harness.DOCK";
    private static final String ACT_PANEL = "com.hubmobile.app.harness.PANEL";
    private static final String ACT_CARD = "com.hubmobile.app.harness.CARD";
    private static final String EXTRA_TEXT = "text";

    private static volatile OverlayService sInstance;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private WindowManager wm;
    private View petView, bubbleView, panelView, fluidView;
    private WindowManager.LayoutParams petLp, bubbleLp, panelLp, fluidLp;

    private boolean visible = false;     // 完整显示（对应前端「在线」）
    private boolean fluid = false;
    private boolean vscreen = false;
    private Bitmap body, ballBmp;
    private JSONArray lines;
    private int ballDp = 40, bodyDp = 96, bubbleHideMs = 6000;
    private float cropX = 0.45f, cropY = 0.34f;
    private int screenW = 0, screenH = 0;

    public static OverlayService get() { return sInstance; }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    public static boolean canDraw(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    /** 前端读的字段：running 控开关、visible 控「在线/已隐藏」、fluid/vscreen 控两个开关。 */
    public static String statusJson(Context c) {
        OverlayService s = sInstance;
        SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean running = s != null || p.getBoolean("pet_running", false);
        boolean vis = s != null && s.visible;
        return "{\"ok\":true,\"running\":" + running
                + ",\"visible\":" + vis
                + ",\"fluid\":" + (s != null ? s.fluid : p.getBoolean("fluid", false))
                + ",\"vscreen\":" + (s != null ? s.vscreen : p.getBoolean("vscreen", false))
                + ",\"permission\":" + canDraw(c)
                + ",\"engine\":\"githup AI\"}";
    }

    @Override public void onCreate() {
        super.onCreate();
        sInstance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        measureScreen();
        loadPetSpec();
        fluid = prefs().getBoolean("fluid", false);
        vscreen = prefs().getBoolean("vscreen", false);
        startForegroundNotification();
        // START_STICKY 重建后按上次状态恢复，避免「开关开着却显示已隐藏」
        if (prefs().getBoolean("pet_running", false)) {
            if (prefs().getBoolean("pet_visible", true)) showPet(false);
            else dockPet();
        }
    }

    private void measureScreen() {
        try {
            android.util.DisplayMetrics m = new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(m);
            screenW = m.widthPixels;
            screenH = m.heightPixels;
        } catch (Throwable t) { screenW = 1080; screenH = 1920; }
    }

    /** 读随包规格书与立绘；读不到就用内置兜底，绝不再画纯色圆。 */
    private void loadPetSpec() {
        try {
            File dir = new File(getFilesDir(), "payload/pet");
            File png = new File(dir, "pet-body.png");
            if (png.isFile()) body = BitmapFactory.decodeFile(png.getAbsolutePath());
            File cfg = new File(dir, "pet.json");
            if (cfg.isFile()) {
                JSONObject o = new JSONObject(readFile(cfg));
                JSONObject ap = o.optJSONObject("appearance");
                if (ap != null) {
                    ballDp = ap.optInt("ballDp", 40);
                    bodyDp = ap.optInt("bodyDp", 96);
                    cropX = (float) ap.optDouble("cropCenterX", 0.45);
                    cropY = (float) ap.optDouble("cropCenterY", 0.34);
                }
                lines = o.optJSONArray("lines");
                JSONObject tap = o.optJSONObject("tap");
                if (tap != null) bubbleHideMs = tap.optInt("bubbleAutoHideMs", 6000);
            }
        } catch (Throwable ignored) {}
        // 预生成一次裁切位图，别在 onDraw 里反复 decode/create
        if (body != null) {
            try {
                int side = Math.min(body.getWidth(), body.getHeight());
                int left = Math.max(0, Math.min((int) (body.getWidth() * cropX - side / 2f), body.getWidth() - side));
                int top = Math.max(0, Math.min((int) (body.getHeight() * cropY - side / 2f), body.getHeight() - side));
                ballBmp = Bitmap.createBitmap(body, left, top, side, side);
            } catch (Throwable ignored) {}
        }
        if (lines == null || lines.length() == 0) {
            lines = new JSONArray();
            String[] fb = {"我是大肥鱼，本机最肥的那只。", "肥是福气，不许笑。", "我在。有事说吧。",
                    "引擎还活着，随时能跑。", "点我，我就去干活。", "别催，我在想。"};
            for (String s : fb) lines.put(s);
        }
    }

    private static String readFile(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        return new String(o.toByteArray(), "UTF-8");
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
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        try {
            startForeground(11, b.setContentTitle("githup AI 桌宠")
                    .setContentText("大肥鱼正在待命").setSmallIcon(R.mipmap.ic_launcher)
                    .setContentIntent(pi).setOngoing(true).build());
        } catch (Throwable ignored) {}
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent != null ? intent.getAction() : null;
        if (ACT_HIDE.equals(act)) {
            removeAll();
            prefs().edit().putBoolean("pet_running", false).putBoolean("pet_visible", false).apply();
        } else if (ACT_DOCK.equals(act)) {
            dockPet();
        } else if (ACT_PANEL.equals(act)) {
            openPanel();
        } else if (ACT_CARD.equals(act)) {
            String t = intent != null ? intent.getStringExtra(EXTRA_TEXT) : null;
            if (fluid && t != null && !t.isEmpty()) pushFluid(t);
        } else {
            showPet(true);
        }
        return START_STICKY;
    }

    /** 完整显示：靠右边缘，不压页面中间。 */
    private void showPet(boolean persist) {
        if (!canDraw(this)) {
            ui.post(new Runnable() {
                @Override public void run() {
                    Toast.makeText(OverlayService.this, "请先授予「显示在其他应用上层」权限",
                            Toast.LENGTH_LONG).show();
                }
            });
            return;
        }
        try {
            removePet();
            petView = buildBall(false);
            attachTouch(petView);
            petLp = new WindowManager.LayoutParams(
                    dp(ballDp), dp(ballDp), overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            petLp.gravity = Gravity.TOP | Gravity.START;
            petLp.x = Math.max(0, screenW - dp(ballDp) - dp(10));
            petLp.y = Math.max(dp(80), (int) (screenH * 0.32f));
            wm.addView(petView, petLp);
            visible = true;
            if (persist) prefs().edit().putBoolean("pet_running", true)
                    .putBoolean("pet_visible", true).apply();
        } catch (Throwable t) {
            petView = null; visible = false;
        }
    }

    /** 贴边半藏：只露一小条，visible=false（前端显示「已隐藏」）。 */
    private void dockPet() {
        if (!canDraw(this)) return;
        try {
            if (petView == null) {
                petView = buildBall(false);
                attachTouch(petView);
                petLp = new WindowManager.LayoutParams(dp(ballDp), dp(ballDp), overlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                petLp.gravity = Gravity.TOP | Gravity.START;
                wm.addView(petView, petLp);
            }
            petLp.x = screenW - dp(12);   // 只露 12dp
            petLp.y = Math.max(dp(80), (int) (screenH * 0.32f));
            wm.updateViewLayout(petView, petLp);
            visible = false;
            prefs().edit().putBoolean("pet_running", true).putBoolean("pet_visible", false).apply();
        } catch (Throwable ignored) { visible = false; }
    }

    /** 圆形裁切立绘 + 描边（pet.json: cropCenter / strokeRatio）。 */
    private View buildBall(final boolean full) {
        final Bitmap src = body;
        return new View(this) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            {
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setColor(Color.parseColor("#6d8bff"));
            }
            @Override protected void onDraw(Canvas c) {
                super.onDraw(c);
                int w = getWidth(), h = getHeight();
                float cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f;
                stroke.setStrokeWidth(Math.max(1f, r * 0.09f));
                if (ballBmp != null) {
                    Bitmap scaled = Bitmap.createScaledBitmap(ballBmp, w, h, true);
                    Path clip = new Path();
                    clip.addCircle(cx, cy, r - stroke.getStrokeWidth() / 2f, Path.Direction.CW);
                    c.save();
                    c.clipPath(clip);
                    c.drawBitmap(scaled, 0, 0, p);
                    c.restore();
                } else {
                    p.setColor(Color.parseColor("#151b2b"));
                    c.drawCircle(cx, cy, r, p);
                }
                c.drawCircle(cx, cy, r - stroke.getStrokeWidth() / 2f, stroke);
            }
        };
    }

    private void attachTouch(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy; float rx, ry; boolean moved; long down;
            @Override public boolean onTouch(View vv, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = petLp.x; sy = petLp.y;
                        rx = e.getRawX(); ry = e.getRawY();
                        moved = false; down = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - rx), dy = (int) (e.getRawY() - ry);
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                        if (moved && petLp != null) {
                            petLp.x = Math.max(0, Math.min(screenW - dp(ballDp), sx + dx));
                            petLp.y = Math.max(0, Math.min(screenH - dp(ballDp), sy + dy));
                            try { wm.updateViewLayout(petView, petLp); } catch (Throwable ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        long dur = System.currentTimeMillis() - down;
                        if (!moved && dur > 550) startDictation();
                        else if (!moved) speakLine();
                        return true;
                }
                return false;
            }
        });
    }

    /** 点球 → 台词气泡（pet.json tap.onBall=line）。 */
    private void speakLine() {
        final String text = lines.optString(rnd.nextInt(Math.max(1, lines.length())), "我在。");
        ui.post(new Runnable() {
            @Override public void run() {
                removeBubble();
                LinearLayout wrap = new LinearLayout(OverlayService.this);
                wrap.setPadding(dp(10), dp(7), dp(10), dp(7));
                GradientDrawable g = new GradientDrawable();
                g.setColor(Color.parseColor("#E6101A2B"));
                g.setCornerRadius(dp(12));
                wrap.setBackground(g);
                TextView t = new TextView(OverlayService.this);
                t.setText(text);
                t.setTextColor(Color.WHITE);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                t.setMaxWidth((int) (screenW * 0.72f));
                wrap.addView(t);
                // 点气泡 → 面板
                wrap.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { openPanel(); }
                });
                bubbleLp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                bubbleLp.gravity = Gravity.TOP | Gravity.START;
                int px = petLp != null ? petLp.x : screenW - dp(ballDp) - dp(10);
                bubbleLp.x = Math.max(dp(8), px - (int) (screenW * 0.62f));
                bubbleLp.y = (petLp != null ? petLp.y : dp(180)) - dp(4);
                try { wm.addView(wrap, bubbleLp); bubbleView = wrap; } catch (Throwable ignored) {}
            }
        });
        ui.postDelayed(new Runnable() {
            @Override public void run() { removeBubble(); }
        }, bubbleHideMs);
    }

    /** 点气泡 → 桌宠面板（简化：台词 + 打开 AI + 收起）。 */
    private void openPanel() {
        ui.post(new Runnable() {
            @Override public void run() {
                removeBubble();
                removePanel();
                LinearLayout card = new LinearLayout(OverlayService.this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(18), dp(16), dp(18), dp(16));
                GradientDrawable g = new GradientDrawable();
                g.setColor(Color.parseColor("#F2141B2E"));
                g.setCornerRadius(dp(20));
                g.setStroke(1, Color.parseColor("#3a4257"));
                card.setBackground(g);
                TextView title = new TextView(OverlayService.this);
                title.setText("大肥鱼 · githup AI");
                title.setTextColor(Color.parseColor("#CFD8EA"));
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                title.setTypeface(Typeface.DEFAULT_BOLD);
                card.addView(title);
                TextView body = new TextView(OverlayService.this);
                body.setText("我是大肥鱼，本机最肥的那只。\n轻点我说台词，长按我来听你说话。");
                body.setTextColor(Color.parseColor("#CFD8EA"));
                body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                body.setPadding(0, dp(10), 0, dp(14));
                card.addView(body);
                LinearLayout row = new LinearLayout(OverlayService.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                TextView open = new TextView(OverlayService.this);
                open.setText("打开 githup AI");
                open.setTextColor(Color.parseColor("#6d8bff"));
                open.setPadding(0, 0, dp(24), 0);
                open.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { gotoAi(); removePanel(); }
                });
                TextView close = new TextView(OverlayService.this);
                close.setText("收起");
                close.setTextColor(Color.parseColor("#8b949e"));
                close.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { removePanel(); }
                });
                row.addView(open); row.addView(close);
                card.addView(row);
                panelLp = new WindowManager.LayoutParams(dp(240),
                        WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                panelLp.gravity = Gravity.TOP | Gravity.START;
                panelLp.x = Math.max(dp(12), (screenW - dp(240)) / 2);
                panelLp.y = Math.max(dp(80), (int) (screenH * 0.28f));
                try { wm.addView(card, panelLp); panelView = card; } catch (Throwable ignored) {}
            }
        });
    }

    private void gotoAi() {
        try {
            Intent i = new Intent(this, HarnessActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Throwable ignored) {}
    }

    /** 长按 → 语音识别说话（接 VoiceManager，不再跳回主界面）。 */
    private void startDictation() {
        ui.post(new Runnable() {
            @Override public void run() {
                String r = VoiceBridge.get(OverlayService.this).voice("start");
                pushFluid("🎤 在听，说完等我一下…");
                ui.postDelayed(new Runnable() {
                    @Override public void run() {
                        String st = VoiceBridge.get(OverlayService.this).voice("stop");
                        try {
                            JSONObject o = new JSONObject(st);
                            String t = o.optString("lastResult", "");
                            if (t.isEmpty()) t = o.optString("partialResult", "");
                            pushFluid(t.isEmpty() ? "没听清，再长按一次试试" : "听到：" + t);
                        } catch (Throwable ignored) {}
                    }
                }, 8000);
            }
        });
    }

    public void setFluid(boolean on) {
        fluid = on;
        prefs().edit().putBoolean("fluid", on).apply();
        if (!on) removeFluid();
    }

    public void setVscreen(boolean on) {
        vscreen = on;
        prefs().edit().putBoolean("vscreen", on).apply();
        if (on) pushFluid("悬浮屏预览需要 Shizuku 特权进程，本版以卡片替代实时画面");
    }

    /** 流体云：状态栏下沿胶囊，点一下展开成卡片（pet.json card.atTop=true）。 */
    public void pushFluid(String text) {
        if (!canDraw(this)) return;
        pushFluidNow(text);
    }

    private void pushFluidNow(final String text) {
        ui.post(new Runnable() {
            @Override public void run() {
                removeFluid();
                final String brief = text.length() > 26 ? text.substring(0, 26) + "…" : text;
                final LinearLayout cap = new LinearLayout(OverlayService.this);
                cap.setPadding(dp(12), dp(7), dp(12), dp(7));
                GradientDrawable g = new GradientDrawable();
                g.setColor(Color.parseColor("#F2141B2E"));
                g.setCornerRadius(dp(22));
                cap.setBackground(g);
                final TextView t = new TextView(OverlayService.this);
                t.setText(brief);
                t.setTextColor(Color.parseColor("#CFD8EA"));
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                t.setMaxWidth(dp(170));
                cap.addView(t);
                cap.setOnClickListener(new View.OnClickListener() {
                    boolean expanded = false;
                    @Override public void onClick(View v) {
                        expanded = !expanded;
                        t.setText(expanded ? text : brief);
                        t.setMaxWidth(expanded ? (int) (screenW * 0.78f) : dp(170));
                        try { wm.updateViewLayout(cap, fluidLp); } catch (Throwable ignored) {}
                    }
                });
                fluidLp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                fluidLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                fluidLp.y = dp(6);
                try { wm.addView(cap, fluidLp); fluidView = cap; } catch (Throwable ignored) {}
                ui.postDelayed(new Runnable() {
                    @Override public void run() { removeFluid(); }
                }, 12000);
            }
        });
    }

    private void removeFluid() {
        if (fluidView != null) { try { wm.removeView(fluidView); } catch (Throwable ignored) {} fluidView = null; }
    }
    private void removeBubble() {
        if (bubbleView != null) { try { wm.removeView(bubbleView); } catch (Throwable ignored) {} bubbleView = null; }
    }
    private void removePanel() {
        if (panelView != null) { try { wm.removeView(panelView); } catch (Throwable ignored) {} panelView = null; }
    }
    private void removePet() {
        removeBubble(); removePanel();
        if (petView != null) { try { wm.removeView(petView); } catch (Throwable ignored) {} petView = null; }
    }
    private void removeAll() {
        removePet(); removeFluid(); visible = false;
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override public void onDestroy() { removeAll(); sInstance = null; super.onDestroy(); }
    @Override public IBinder onBind(Intent i) { return null; }

    public static void dispatch(Context c, String action, String text) {
        Intent i = new Intent(c, OverlayService.class);
        if ("hide".equals(action)) i.setAction(ACT_HIDE);
        else if ("dock".equals(action)) i.setAction(ACT_DOCK);
        else if ("panel".equals(action)) i.setAction(ACT_PANEL);
        else if ("card".equals(action)) { i.setAction(ACT_CARD); i.putExtra(EXTRA_TEXT, text); }
        else i.setAction(ACT_SHOW);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable ignored) {}
    }
}
