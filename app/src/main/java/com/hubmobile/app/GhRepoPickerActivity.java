package com.hubmobile.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.hubmobile.app.harness.GhBridge;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 阶段 M：DSH 控制台「AI 工作区（可选）」点「选择 / 更改」时弹的仓库选择器。
 *
 * 上游这里是唤起系统目录选择器（SAF）选本地文件夹；用户要的是「能选 GitHub
 * 的库」，所以改成列当前 githup 令牌能看到的仓库，选完由 GhBridge 展开成
 * App 私有目录里的只读镜像，再把工作区指过去。
 */
public class GhRepoPickerActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean done = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!GhBridge.enabled(getApplicationContext())) {
            Toast.makeText(this, "还没有 GitHub 令牌：请先在 githup 里登录或粘贴令牌", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        final String cur = GhBridge.current(getApplicationContext());
        Toast.makeText(this, "正在读取 GitHub 仓库列表…", Toast.LENGTH_SHORT).show();

        new Thread(new Runnable() {
            @Override public void run() {
                final String raw = GhBridge.repos(getApplicationContext());
                ui.post(new Runnable() {
                    @Override public void run() { showList(raw, cur); }
                });
            }
        }, "gh-repos").start();
    }

    private void showList(String raw, final String cur) {
        if (done || isFinishing()) return;
        try {
            JSONObject o = new JSONObject(raw);
            if (!o.optBoolean("ok")) {
                new AlertDialog.Builder(this)
                        .setTitle("读取仓库失败")
                        .setMessage(o.optString("error", "未知原因"))
                        .setPositiveButton("知道了", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { close(); }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override public void onCancel(DialogInterface d) { close(); }
                        }).show();
                return;
            }
            final JSONArray repos = o.optJSONArray("repos");
            if (repos == null || repos.length() == 0) {
                new AlertDialog.Builder(this).setTitle("没有可用仓库")
                        .setMessage("当前令牌看不到任何仓库。请确认已登录且令牌有 repo 权限。")
                        .setPositiveButton("知道了", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { close(); }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override public void onCancel(DialogInterface d) { close(); }
                        }).show();
                return;
            }

            CharSequence[] labels = new CharSequence[repos.length()];
            for (int i = 0; i < repos.length(); i++) {
                JSONObject r = repos.optJSONObject(i);
                if (r == null) { labels[i] = "?"; continue; }
                String name = r.optString("full_name", "?");
                String mark = r.optBoolean("private") ? "🔒 " : "  ";
                String star = name.equals(cur) ? "  ✓ 当前" : "";
                labels[i] = mark + name + star;
            }
            new AlertDialog.Builder(this)
                    .setTitle("选择 AI 工作区 · GitHub 仓库")
                    .setItems(labels, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int which) {
                            JSONObject r = repos.optJSONObject(which);
                            if (r != null) pick(r.optString("full_name", ""));
                        }
                    })
                    .setNegativeButton(cur.isEmpty() ? "取消" : "清除工作区",
                            new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    if (!cur.isEmpty()) {
                                        GhBridge.setCurrent(getApplicationContext(), "");
                                        Toast.makeText(GhRepoPickerActivity.this,
                                                "已清除 AI 工作区（登录令牌权限不变）", Toast.LENGTH_SHORT).show();
                                    }
                                    close();
                                }
                            })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override public void onCancel(DialogInterface d) { close(); }
                    }).show();
        } catch (Throwable t) {
            Toast.makeText(this, "仓库列表解析失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
            close();
        }
    }

    private void pick(final String fullName) {
        if (fullName.isEmpty()) return;
        done = true;
        Toast.makeText(this, "正在展开 " + fullName + " …", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override public void run() {
                final String r = GhBridge.materialize(getApplicationContext(), fullName);
                ui.post(new Runnable() {
                    @Override public void run() {
                        try {
                            JSONObject o = new JSONObject(r);
                            Toast.makeText(GhRepoPickerActivity.this,
                                    o.optString("msg", o.optString("error", "完成")), Toast.LENGTH_LONG).show();
                        } catch (Throwable ignored) {}
                        finish();
                    }
                });
            }
        }, "gh-materialize").start();
    }

    private void close() { done = true; finish(); }

    @Override public void onBackPressed() { close(); }
}
