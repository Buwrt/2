package com.hubmobile.app.harness;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 定时任务执行（精简版）。
 *
 * 上游版本会经 Shizuku 起特权进程；本版只用 App 自身能力：
 *   · 记录执行历史（供 /schedule/list 的 history 字段）
 *   · 尽量把任务文本投递给正在运行的 node 引擎（EngineRpc → /api/session.prompt）
 *   · 引擎没起时静默降级为「只发通知」，通知由 AlarmReceiver 负责
 *
 * 这样 android_schedule_add / list / cancel 三个工具都能真跑，而不是回一句不支持。
 */
final class ScheduleExecutor {

    private static final String TAG = "gh-schedule";
    private static final int MAX_LINES = 200;

    static void log(Context ctx, String line) {
        try {
            File f = new File(ctx.getFilesDir(), "schedule-history.txt");
            FileOutputStream out = new FileOutputStream(f, true);
            out.write((System.currentTimeMillis() + " " + line + "\n").getBytes("UTF-8"));
            out.close();
            trim(f);
        } catch (Throwable t) {
            Log.w(TAG, "log failed", t);
        }
    }

    static List<String> history(Context ctx, int limit) {
        List<String> all = new ArrayList<String>();
        try {
            File f = new File(ctx.getFilesDir(), "schedule-history.txt");
            if (!f.exists()) return all;
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String l;
            while ((l = r.readLine()) != null) all.add(l);
            r.close();
        } catch (Throwable ignored) {}
        if (all.size() > limit) return new ArrayList<String>(all.subList(all.size() - limit, all.size()));
        return all;
    }

    private static void trim(File f) {
        try {
            if (f.length() < 128 * 1024) return;
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            List<String> lines = new ArrayList<String>();
            String l;
            while ((l = r.readLine()) != null) lines.add(l);
            r.close();
            int from = Math.max(0, lines.size() - MAX_LINES);
            FileOutputStream out = new FileOutputStream(f, false);
            for (int i = from; i < lines.size(); i++) out.write((lines.get(i) + "\n").getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {}
    }

    /** 把任务文本投给引擎；引擎不在就只留日志，不假装成功。 */
    static void execute(Context ctx, String task) {
        log(ctx, "执行任务: " + task);
        try {
            SharedPreferences p = ctx.getSharedPreferences("dsh_prefs", Context.MODE_PRIVATE);
            int port = p.getInt("engine_port", 3080);
            File log = new File(ctx.getFilesDir(), "dsh-web.log");
            JSONObject req = EngineRpc.prompt("", task);
            EngineRpc.call(log, port, "session/prompt", req);
            log(ctx, "已投递引擎 session/prompt");
        } catch (Throwable t) {
            log(ctx, "引擎未就绪，降级为通知提醒: " + t);
        }
    }

    /** 生活助理唤醒：本版没有 life daemon 的 IPC，只记录并收口 goAsync。 */
    static void tickLife(Context ctx, Runnable finish) {
        log(ctx, "生活助理唤醒（本版由 life.js 自行调度）");
        try {
            SharedPreferences p = ctx.getSharedPreferences("dsh_prefs", Context.MODE_PRIVATE);
            int port = p.getInt("engine_port", 3080);
            File log = new File(ctx.getFilesDir(), "dsh-web.log");
            EngineRpc.call(log, port, "session/prompt",
                    EngineRpc.prompt("", "生活助理定时任务到点，请检查 life.db 的 scheduled_tasks"));
        } catch (Throwable ignored) {}
        if (finish != null) finish.run();
    }

    private ScheduleExecutor() {}
}
