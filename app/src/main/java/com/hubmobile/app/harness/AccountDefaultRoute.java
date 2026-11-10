package com.hubmobile.app.harness;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 阶段 N：让「赠送余额 / 充值余额」这条账号路由成为已登录账号时的默认模型路由。
 *
 * ### 为什么需要它
 *
 * DSH 内核里并排挂着两条互不通用、也不会互相回退的鉴权路由：
 *
 *   · `deepseek-official` —— 用 DEEPSEEK_API_KEY（sk- 开头）走官方 API 计费
 *   · `deepseek-account`  —— 用系统浏览器登录 DeepSeek 网页账号得到的
 *                            `x-dsh-auth-token`，消费平台账户里的
 *                            **充值余额 + 赠金余额（bonus_wallets）**
 *
 * `dsh-base/cordis.patch.yml` 把新 agent 的默认写死成
 * `provider: deepseek-official`。上游原版能用赠金，是因为那份 `$DSH_HOME`
 * 里已经登录过账号、并在模型选择器里选过「DeepSeek 账号」分组 ——
 * `session/selectModel` 会把选择**持久化进 profile 的 agent-default-model**，
 * 之后新建会话一直走账号路由。
 *
 * 我们的包是从上游发行 payload 全新展开的干净 `$DSH_HOME`，没有这份历史
 * 选择，所以每个新会话都落在 `deepseek-official` → 找 sk- 找不到 →
 * 报 `llm-deepseek: no API key for provider route "deepseek-official"`，
 * 而截图里 ¥1.66 / ¥0.93 的钱包在另一条路上，一分钱都花不出去。
 *
 * 本类在**引擎启动前**自适应补一条 profile patch 覆盖默认：账号已登录 →
 * 默认改 `deepseek-account`（赠金可用）；没登录 → 什么都不写，保持
 * `deepseek-official`，避免未登录用户的新会话直接 ACCOUNT_SIGN_IN_REQUIRED。
 *
 * 只写用户层 profile patch，不动 160MB payload 里的内核文件；每次启动
 * 幂等重写，覆盖安装 / payload 刷新后自动恢复。
 */
public final class AccountDefaultRoute {

    private static final String TAG = "AccountDefaultRoute";
    private static final String BEGIN = "# githup-account-route begin";
    private static final String END = "# githup-account-route end";

    private AccountDefaultRoute() {}

    /** 在 HarnessBridge.start() 起 node 之前调用。任何异常都吞掉，绝不阻塞引擎。 */
    public static void apply(Context c, File dshHome) {
        try {
            boolean signedIn = accountSignedIn(dshHome);
            File patch = new File(dshHome, "profiles/web/cordis.patch.yml");
            if (!patch.getParentFile().exists() && !patch.getParentFile().mkdirs()) return;

            String old = read(patch);
            String clean = strip(old);            // 先摘掉上一次写的块，保证幂等
            String next = clean;
            if (signedIn) {
                if (!next.endsWith("\n")) next += "\n";
                next += "\n" + BEGIN + "\n"
                        + "# 已登录 DeepSeek 网页账号：新建会话默认走账号路由，\n"
                        + "# 消费「充值余额 / 赠金余额」，不再要求 sk- API Key。\n"
                        + "# 未登录时本块不写入，回落到内核默认 deepseek-official。\n"
                        + "- id: agent-default-model\n"
                        + "  config:\n"
                        + "    provider: deepseek-account\n"
                        + "    model: deepseek-flash\n"
                        + END + "\n";
            }
            if (!next.equals(old)) {
                FileOutputStream f = new FileOutputStream(patch, false);
                f.write(next.getBytes(StandardCharsets.UTF_8));
                f.close();
            }
            Log.i(TAG, "account route: signedIn=" + signedIn
                    + " patch=" + patch.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "apply failed", t);
        }
    }

    /**
     * 账号登录态检测：宽松文本匹配即可，不需要解析 YAML。
     * `dsh-deepseek-account-platform` 用 credentialKey(namespace, "default")
     * 把授权 grant 存进 `$DSH_HOME/.credentials.yaml`，key 形如
     * `deepseek-account-platform/default`，记录里带 kind: grant 与 token。
     * 账号与余额页能显示 ¥1.66 / ¥0.93，就说明这条记录存在且有效。
     */
    private static boolean accountSignedIn(File dshHome) {
        File f = new File(dshHome, ".credentials.yaml");
        if (!f.isFile()) return false;
        String s = read(f);
        if (!s.contains("deepseek-account-platform")) return false;
        // 只认「有 grant / token 内容」的记录，避免残留的空壳或注释误判
        int i = s.indexOf("deepseek-account-platform");
        String tail = s.substring(i, Math.min(s.length(), i + 1200));
        return tail.contains("grant") || tail.contains("token")
                || tail.contains("payload") || tail.contains("secret");
    }

    private static String strip(String s) {
        if (s == null) return "";
        int a = s.indexOf(BEGIN);
        if (a < 0) return s;
        int b = s.indexOf(END, a);
        if (b < 0) return s.substring(0, a);   // 半截块，直接丢掉
        b = s.indexOf('\n', b);
        if (b < 0) b = s.length(); else b++;
        // 连同块前可能多出的空行一起收掉
        int cut = a;
        while (cut > 0 && (s.charAt(cut - 1) == '\n' || s.charAt(cut - 1) == '\r')) cut--;
        return s.substring(0, cut) + "\n" + s.substring(b);
    }

    private static String read(File f) {
        if (f == null || !f.isFile()) return "";
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            in.close();
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }
}
