package com.hubmobile.app.harness;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 阶段 O：让 AI **一进工作区就看见** GitHub 通道的存在。
 *
 * 上一版只在「用户选了 GitHub 仓库」时往镜像目录写 GITHUP.md，结果 AI 没选
 * 工作区、或者先去检查了 gh CLI / GITHUB_TOKEN，就得出「我没有任何 GitHub
 * 身份凭证、看不到你的仓库」的错误结论。
 *
 * 这里做两件纯粹在 App 侧、零依赖的事：
 *
 *  ① 引擎启动前，在 AI 的默认工作区根目录写 AGENTS.md / CLAUDE.md /
 *     GITHUP.md / README.md（这些是 DSH 会自动读的项目指令/说明文件名）。
 *     没选仓库时 DSH_WORKSPACE 就指向这个目录 —— bash / file 工具的 cwd
 *     一进来就是它，ls 就能看到；选了仓库时该仓库镜像里另有一份。
 *  ② 提供 workspaceFor()，由 HarnessBridge 设置 DSH_WORKSPACE。
 *     dshhome/cordis.patch.yml 里 bash-local 的 cwd 是
 *     `process.env.DSH_WORKSPACE || process.cwd()`，设了就生效。
 *
 * 不注册新工具、不改 payload，避免用户层 node_modules 的依赖解析不确定性。
 */
public final class GhBridgeDocs {

    private static final String TAG = "GhBridgeDocs";

    private GhBridgeDocs() {}

    /** 引擎起 node 之前调用，幂等。 */
    public static void install(Context c) {
        try {
            File root = GhBridge.workspaceRoot(c);
            if (!root.exists() && !root.mkdirs()) return;
            String doc = text(c, null);
            /* 阶段 X：以前同一个目录里塞 4 份 2.5KB 的相同说明，DSH 会把它们
               重复读进上下文 —— 那也是「费 token」的一部分。现在只保留
               AGENTS.md / CLAUDE.md 两份主流约定的全量文档，其余写成一行指针。 */
            String ptr = pointer();
            write(new File(root, "AGENTS.md"), doc);
            write(new File(root, "CLAUDE.md"), ptr);
            write(new File(root, "GITHUP.md"), ptr);
            write(new File(root, "README.md"), ptr);
            File dshHome = new File(c.getFilesDir(), "payload/dshhome");
            write(new File(dshHome, "AGENTS.md"), doc);
            write(new File(dshHome, "CLAUDE.md"), ptr);
            write(new File(dshHome, "GITHUP-BRIDGE.md"), ptr);
            Log.i(TAG, "docs installed at " + root.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "install failed", t);
        }
    }

    /** 当前应作为 AI 工作区的目录：选了仓库 → 镜像目录；没选 → 说明目录。 */
    public static File workspaceFor(Context c) {
        String name = GhBridge.current(c);
        if (!name.isEmpty()) {
            File d = GhBridge.workspaceDir(c, name);
            if (d.isDirectory()) return d;
        }
        return GhBridge.workspaceRoot(c);
    }

    /** 一行指针，替代重复的全量说明，避免 DSH 把同一份文档读进上下文 4 次。 */
    public static String pointer() {
        return "# githup · 完整说明见同级 AGENTS.md\n\n"
                + "请先读 `AGENTS.md`；本文件只是指针，不要重复读取。\n";
    }

    /** 选中仓库后，刷新该镜像里的说明（由 GhBridge.materialize 之后调用也可）。 */
    public static String repoText(Context c, String fullName) {
        return text(c, fullName);
    }

    /**
     * 阶段 X+：工作区说明刻意压到 ≤1.5KB。
     *
     * 上一版写了 9KB 的「通道说明 + 禁止编造 + 完整 curl 流程」，但
     * DSH 记忆系统读的是 $DSH_HOME/AGENTS.md、模型也不会自动读工作区文件；
     * 万一被整份读进上下文，反而就是「费 token」。现在真正生效的是
     * dsh-plugin-githup-context 每轮注入的 ≤600 字符短 brief，
     * 这里只留一份兜底的极简索引。
     */
    private static String text(Context c, String repo) {
        String where = repo == null
                ? "当前是 AI 默认工作区（尚未选择 GitHub 仓库）。"
                : "当前是 GitHub 仓库 `" + repo + "` 的本地镜像。";
        return "# githup · AI 辅助速查\n\n"
                + where + "\n\n"
                + "## 当前页面（不用问、不用猜）\n\n"
                + "每轮对话开头会自动收到一条 `githup-context` 通知，内容是 githup 客户端"
                + "自己写下的路由与仓库状态（不是截图、不是控件树，无需任何系统权限）。"
                + "**直接采信它**，不要再问用户「你在看哪个库」。\n\n"
                + "## 省 token 的硬规矩\n\n"
                + "1. 要总结/修改当前仓库，先一次：\n"
                + "   `curl 'http://127.0.0.1:3081/app/summary?repo=owner/name'`\n"
                + "   已含元数据 + README 摘要 + 顶层文件树（≤2KB）。\n"
                + "2. **不要**逐文件读全库、**不要** `curl /gh/repos` 拿 100 个仓库的大 JSON。"
                + "只有确实需要细节才读 `/gh/api/repos/O/R/contents/PATH` 单个文件。\n"
                + "3. **不要**检查 `gh` CLI / `GITHUB_TOKEN` / 环境变量 / `hosts.yml` ——"
                + "它们本来就不存在，检查只会烧 token 还得错结论。\n"
                + "4. 改完上传用 `POST /app/clone-repo`：在当前登录账号下建**同名**库，不动原库。\n\n"
                + "## 诚实边界\n\n"
                + "- 没有屏幕像素、控件树、点按/滚动能力；除非用户开了系统无障碍服务。\n"
                + "- 没拿到上下文通知时**不要编造**页面、设置值、主题、字号、浏览历史。\n"
                + "- 能调 GitHub API ≠ 能看到 App 屏幕上渲染了什么。\n"
                + "- 权限 = githup 当前登录令牌；换号跟着换，退出登录立刻失效。\n"
                + "- 以上能力须用户在「我的 → 设置 → AI 感知与克隆」点过同意；"
                + "`consent:false` 时如实说明未开启。\n";
    }

    private static void write(File f, String s) {
        try {
            if (f.getParentFile() != null && !f.getParentFile().exists()
                    && !f.getParentFile().mkdirs()) return;
            FileOutputStream o = new FileOutputStream(f, false);
            o.write(s.getBytes(StandardCharsets.UTF_8));
            o.close();
        } catch (Throwable ignored) {}
    }
}
