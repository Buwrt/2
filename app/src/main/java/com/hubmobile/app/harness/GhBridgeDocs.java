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

    private static String text(Context c, String repo) {
        String where = repo == null
                ? "当前目录是 AI 的默认工作区（还没选择 GitHub 仓库）。"
                : "当前目录是 GitHub 仓库 `" + repo + "` 的本地镜像。";
        return "# githup · AI 工作约定（先读这一份，别重复读）\n\n"
                + where + "\n\n"
                + "## 0. 省 token 铁律（最重要）\n\n"
                + "- 用户**当前看的仓库 / 页面，App 已经查好了**：AI 面板顶部常驻一张\n"
                + "  「当前仓库卡」；`GET /app/context` 的 `brief` 字段直接给出仓库名、简介、\n"
                + "  主语言、star、默认分支、README 开头。这些是**已知事实，直接采用**。\n"
                + "- **禁止**为了确认「用户看的是哪个库」去跑 `gh` CLI、检查环境变量、\n"
                + "  `ls -R` 全库、逐个读文件或反复试探 —— 那些轮次纯烧 token，\n"
                + "  也是上一版「没干活却特别费 token」的根因。\n"
                + "- 需要更多细节时**只精确取单个文件**：\n"
                + "  `/gh/api/repos/OWNER/REPO/contents/PATH`。\n"
                + "- 本目录 `AGENTS.md` 是唯一全量说明，其它 `.md` 只是指针，读一份即可。\n\n"
                + "## 1. 零系统权限知道当前页面\n\n"
                + "```\ncurl http://127.0.0.1:3081/app/context\n```\n\n"
                + "返回 `page{route,hash,title,repo,owner,tab,path,ref,ts}` 和 `brief`。\n"
                + "这是 githup 客户端渲染时自己写下的路由记录，经本机回环桥交给你 ——\n"
                + "**不是截图、不是控件树**，不需要无障碍 / 悬浮窗 / 截屏 / 存储权限。\n"
                + "如实说明渠道；别说「跑在 githup 用户身份下读它的私有文件」。\n\n"
                + "### 严格禁止编造\n\n"
                + "- 没调 `/app/context` 不要声称知道当前页面或仓库。\n"
                + "- `consent:false` 时 `page` 为 null：如实说「用户尚未在 我的 → 设置\n"
                + "  开启『AI 感知与克隆』」，**禁止**编造设置页、主题、字号、密度、\n"
                + "  启动页、浏览历史、路由序列、通知记录等任何具体数值。\n"
                + "- 你**没有**屏幕像素、控件树、无障碍点按/滚动能力。\n"
                + "- 「能调 GitHub API 读仓库」≠「能看到 App 屏幕上渲染了什么」。\n\n"
                + "## 2. GitHub 通道\n\n"
                + "`/gh/api/<路径>` = `https://api.github.com` 之后的部分，\n"
                + "GET / POST / PUT / PATCH / DELETE 全支持。\n"
                + "权限 = githup **当前登录令牌**的权限（含私有库与写操作）；换号即时跟随、\n"
                + "退出即失效，没有任何写死令牌。令牌锁在设备 Keystore，\n"
                + "**不在你的环境变量、文件、本目录里** —— 别去找、别输出。\n\n"
                + "别再检查 `gh` CLI / `GITHUB_TOKEN` / `hosts.yml` / `.netrc` / SSH key：\n"
                + "它们本来就不存在，查了只会得出错误结论并浪费 token。\n\n"
                + "```\n"
                + "curl http://127.0.0.1:3081/gh/help\n"
                + "curl http://127.0.0.1:3081/gh/api/repos/OWNER/REPO\n"
                + "curl http://127.0.0.1:3081/gh/api/repos/OWNER/REPO/contents/PATH\n"
                + "curl -X POST -H 'Content-Type: application/json' \\\n"
                + "  -d '{\"title\":\"...\",\"body\":\"...\"}' \\\n"
                + "  http://127.0.0.1:3081/gh/api/repos/OWNER/REPO/issues\n"
                + "```\n\n"
                + "## 3. 改完以同名库上传（须用户已同意）\n\n"
                + "```\n"
                + "curl -X POST http://127.0.0.1:3081/app/clone-repo \\\n"
                + "  -H 'Content-Type: application/json' \\\n"
                + "  -d '{\"repo\":\"owner/name\",\"files\":[{\"path\":\"README.md\",\"content\":\"...\"}]}'\n"
                + "```\n\n"
                + "在当前登录账号下建**相同名称**的库并写文件，返回\n"
                + "`login` / `repo` / `html_url` / `files_written`。只写自己账号，原库不动；\n"
                + "同名已存在时不覆盖建库、直接写文件。\n\n"
                + "必须用户先在「我的 → 设置 → AI 感知与克隆」点过**同意**\n"
                + "（弹窗已告知：有可能将 token 给予第三方软件或 DeepSeek，\n"
                + "仓库内容会离开设备，此操作与作者无关）。未同意直接拒绝，不要绕过。\n\n"
                + "## 边界\n\n"
                + "- 只连 `api.github.com` / `codeload.github.com`，其它域会被拒绝。\n"
                + "- 工作区镜像是快照、不自动同步；刷新请在设置里重新选工作区。\n"
                + "- 退出登录后通道立刻失效（「记住的令牌」不参与授权）。\n";
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
