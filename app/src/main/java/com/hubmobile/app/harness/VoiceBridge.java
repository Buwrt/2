package com.hubmobile.app.harness;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONObject;

/**
 * 把上游 VoiceManager（STT / TTS / 热词）包装成 3081 本地桥需要的同步 JSON 接口。
 *
 * 插件 dsh-tool-shizuku 的 android_voice / android_tts 会 POST：
 *   /voice {action: start|stop|cancel|status}
 *   /tts   {action: speak|stop|status, text, queue}
 * 期望字段：recognitionAvailable / isListening / state / partialResult /
 *           lastResult / lastResultTime / lastError / ttsReady / isSpeaking。
 */
public final class VoiceBridge {

    private static final Object LOCK = new Object();
    private static VoiceBridge sInstance;

    private final Context ctx;
    private final VoiceManager vm;
    private VoiceManager.VoiceCallback owner;
    private String state = "idle";
    private String partial = "";
    private String lastResult = "";
    private long lastResultTime = 0;
    private String lastError = "";
    private float rms = 0f;

    private VoiceBridge(Context c) {
        ctx = c.getApplicationContext();
        vm = VoiceManager.get(ctx);
        vm.addCallback(new VoiceManager.VoiceCallback() {
            @Override public void onStateChange(String s, String msg) {
                synchronized (LOCK) { state = s == null ? "idle" : s; }
            }
            @Override public void onResult(String text, boolean isFinal) {
                synchronized (LOCK) {
                    String v = text == null ? "" : text;
                    partial = v;
                    if (isFinal) {
                        lastResult = v; lastResultTime = System.currentTimeMillis();
                        state = "final";
                    }
                }
            }
            @Override public void onError(String err) {
                synchronized (LOCK) { lastError = err == null ? "" : err; state = "error"; }
            }
        });
    }

    public static synchronized VoiceBridge get(Context c) {
        if (sInstance == null) sInstance = new VoiceBridge(c);
        return sInstance;
    }

    private boolean micOk() {
        return Build.VERSION.SDK_INT < 23
                || ctx.checkSelfPermission("android.permission.RECORD_AUDIO")
                == PackageManager.PERMISSION_GRANTED;
    }

    public String voice(String action) {
        if (action == null || action.isEmpty()) action = "status";
        try {
            if ("start".equals(action)) {
                if (!micOk()) return err("麦克风权限未授予，请在系统设置或控制台里开启");
                synchronized (LOCK) {
                    if (owner != null) return err("已有听写正在进行");
                    state = "starting"; partial = ""; lastError = "";
                    owner = new VoiceManager.VoiceCallback() {
                        @Override public void onStateChange(String s, String m) {}
                        @Override public void onResult(String t, boolean f) {}
                        @Override public void onError(String e) {}
                    };
                }
                vm.startDictation(owner);
            } else if ("stop".equals(action)) {
                synchronized (LOCK) {
                    if (owner == null) return err("当前没有进行中的听写");
                    state = "recognizing";
                }
                vm.stopDictation(owner);
            } else if ("cancel".equals(action)) {
                synchronized (LOCK) {
                    if (owner != null) vm.cancelDictation(owner);
                    owner = null; state = "cancelled";
                }
            }
            return status();
        } catch (Throwable t) {
            return err(String.valueOf(t.getMessage()));
        }
    }

    public String tts(String action, String text, boolean queue) {
        if (action == null || action.isEmpty()) action = "status";
        try {
            if ("speak".equals(action)) {
                if (text == null || text.isEmpty()) return err("text 不能为空");
                vm.speak(text, queue);
            } else if ("stop".equals(action)) {
                vm.stopSpeaking();
            }
            JSONObject o = new JSONObject();
            o.put("ok", true).put("speaking", vm.isSpeaking())
                    .put("isSpeaking", vm.isSpeaking());
            return o.toString();
        } catch (Throwable t) {
            return err(String.valueOf(t.getMessage()));
        }
    }

    private String status() {
        try {
            synchronized (LOCK) {
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("recognitionAvailable", vm.isRecognitionAvailable());
                o.put("isListening", "listening".equals(state) || "recognizing".equals(state));
                o.put("state", state);
                o.put("rms", (double) rms);
                o.put("partialResult", partial);
                o.put("lastResult", lastResult);
                o.put("lastResultTime", lastResultTime);
                o.put("lastError", lastError);
                o.put("ttsReady", true);
                o.put("isSpeaking", vm.isSpeaking());
                return o.toString();
            }
        } catch (Throwable t) {
            return err(String.valueOf(t.getMessage()));
        }
    }

    private static String err(String m) {
        try { return new JSONObject().put("ok", false).put("error", m).toString(); }
        catch (Throwable t) { return "{\"ok\":false,\"error\":\"voice bridge error\"}"; }
    }
}
