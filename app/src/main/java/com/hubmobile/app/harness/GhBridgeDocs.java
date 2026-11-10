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
            write(new File(root, "AGENTS.md"), doc);   // DSH/Codex 约定：项目指令自动读
            write(new File(root, "CLAUDE.md"), doc);   // Claude 风格约定，双保险
            write(new File(root, "GITHUP.md"), doc);
            write(new File(root, "README.md"), doc);   // AI 常读 README
            File dshHome = new File(c.getFilesDir(), "payload/dshhome");
            write(new File(dshHome, "AGENTS.md"), doc);
            write(new File(dshHome, "CLAUDE.md"), doc);
            write(new File(dshHome, "GITHUP-BRIDGE.md"), doc);
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

    /** 选中仓库后，刷新该镜像里的说明（由 GhBridge.materialize 之后调用也可）。 */
    public static String repoText(Context c, String fullName) {
        return text(c, fullName);
    }

    private static String text(Context c, String repo) {
        String where = repo == null
                ? "当前目录是 AI 的默认工作区（还没选择 GitHub 仓库）。"
                : "当前目录是 GitHub 仓库 `" + repo + "` 的本地镜像。";
        return "# githup · GitHub 能力说明（AI 请先读这段）\n\n"
                + where + "\n\n"
                + "## 别再用 gh CLI / 环境变量判断权限\n\n"
                + "只要 githup 里**当前登录着令牌**，你就拥有该令牌的全部 GitHub 权限"
                + "（含私有库与写操作）。**换账号会立刻跟着换，退出登录会立刻失效**——"
                + "这里没有任何写死的令牌。\n"
                + "但它**不在**这些地方：\n\n"
                + "- `gh` CLI（未安装）\n"
                + "- `GITHUB_TOKEN` / `GH_TOKEN` 环境变量（故意不注入）\n"
                + "- `~/.config/gh/hosts.yml`、`~/.netrc`、SSH key、git credential helper\n\n"
                + "令牌由 githup 客户端保管在设备 Keystore 里，只在 App 进程内附加到\n"
                + "`api.github.com` 的请求上。DSH 刻意不让凭据出现在你的环境或文件里。\n"
                + "所以「检查环境有没有凭证」必然得出「没有」——**那是错的**。\n"
                + "正确做法：直接调用下面的本机代理。\n\n"
                + "## 通道\n\n"
                + "```\n"
                + "curl http://127.0.0.1:3081/gh/help          # 完整用法（先跑这个）\n"
                + "curl http://127.0.0.1:3081/gh/repos         # 列出当前账号可见的全部仓库（含私有库）\n"
                + "curl http://127.0.0.1:3081/gh/workspace     # 当前工作区状态\n"
                + "curl http://127.0.0.1:3081/gh/api/user      # 当前登录账号\n"
                + "curl http://127.0.0.1:3081/gh/api/user/repos?per_page=100\n"
                + "```\n\n"
                + "`/gh/api/<路径>` 里的路径 = GitHub REST API 去掉\n"
                + "`https://api.github.com` 之后的部分，例如\n"
                + "`/repos/OWNER/REPO/issues`、`/repos/OWNER/REPO/pulls`、\n"
                + "`/repos/OWNER/REPO/contents/src/main.js`。\n\n"
                + "**GET / POST / PUT / PATCH / DELETE 全部支持。**\n"
                + "你的权限 = githup 当前登录令牌的权限：token 能读私有库你就能读，\n"
                + "token 能写你就能建 Issue / 提交 / 开 PR。没有额外开关，也不限只读。\n\n"
                + "写操作示例：\n"
                + "```\n"
                + "curl -X POST -H 'Content-Type: application/json' \\\n"
                + "  -d '{\"title\":\"标题\",\"body\":\"正文\"}' \\\n"
                + "  http://127.0.0.1:3081/gh/api/repos/OWNER/REPO/issues\n"
                + "```\n\n"
                + "## 边界\n\n"
                + "- 令牌**不在本目录、不在你的环境变量中**，不要尝试寻找、读取或输出它。\n"
                + "- 只连 `api.github.com` / `codeload.github.com`，其它域会被拒绝。\n"
                + "- 退出登录或清空令牌后，这条通道立刻失效（「记住的令牌」不参与授权）；"
                + "切换账号后自动换成新令牌，无需重启引擎。\n"
                + "- 本目录若是仓库镜像，为快照，不会自动同步；刷新请在设置里重新选工作区。\n";
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
