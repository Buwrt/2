# githup · AI 合并版（Buwrt/2）

Android 上的第三方 GitHub 客户端 **githup**，合并了 **DeepSeek Harness（DSH）** AI 引擎。
一个 APK、一个桌面图标、一个包名、一套官方签名。

> 底座是 WebView 承载的纯前端单页应用（GitHub 客户端）；AI 是同进程内的本地
> node 引擎 + WebView 面板。两者**同屏共存**，不是两个软件，也不需要切换 App。

| 项 | 值 |
|---|---|
| 包名 | `com.hubmobile.app` |
| 版本 | `1.2.20` / `versionCode 1002020` |
| 软件名 | `githup` |
| 签名证书 SHA-256 | `863dd1cd3752e59165e152bc4ab35fde478fcd296d724a6bc58cec0368184927` |
| 最低系统 | Android 7.0（API 24） |
| targetSdk | 28（Android 10+ 的 W^X 会禁止执行私有目录下的 node） |

---

## 下载

**最新版 APK**：本仓库 `Releases` → tag `v1.2.20-merged` 的附件
`githup-v1.2.20.apk`（约 155 MB，含 41,426 个引擎文件）。

> APK 不进 git（体积过大）。`apk/` 目录只放各版本的 `.sha256` 校验文件；
> `version.json` 记录当前包的 `size` 与 `sha256`，供 App 内更新检测比对。
> Release 附件、`version.json` 与仓库源码三者始终指向同一个包。

```sh
sha256sum githup-v1.2.20.apk
apksigner verify --print-certs githup-v1.2.20.apk
```

---

## 第一次使用

1. 覆盖安装即可（同包名、同签名、versionCode 不降级）；与上游原版
   `com.deepseek.harness` 签名不同，不能互相覆盖。
2. 点开只有 **githup** 一个图标，直接是 GitHub 客户端；引擎在后台静默装载。
3. **我的 → 设置 → AI 辅助 githup**：这个开关只管右下角可拖动的 AI 球显隐，
   **默认关**。打开后球出现。
4. **第一次点开 AI 球**会弹一次风险确认（同意 / 拒绝）：
   > 开启后，AI 无需任何 Android 系统权限（不用无障碍、不用截图、不用悬浮窗），
   > 就能知道 githup 当前所在的页面、正在阅读的仓库与屏幕上已渲染的内容，
   > 并可替你操作本软件、总结与修改代码，再把修改后的内容以「相同名称」的
   > 仓库上传到你当前登录的 GitHub 账号。
   >
   > 注意：此过程**有可能将 token 给予第三方软件或 DeepSeek**，仓库内容也会
   > 离开设备。**是否同意？此操作与作者无关。**

   点「同意」后能力即时生效（插件已常驻，不用重启引擎、不用再找第二个开关）。
5. AI 球 → 面板里的 ⛶ 全屏 / ⌄ 收起；返回键只收面板，不退出、不跳页面。
6. DeepSeek 账号：设置 → 账号与余额 → 登录（会**直接跳系统浏览器**完成 OAuth；
   若默认浏览器是 UC / 夸克 / QQ 浏览器会拦回环回调，请改用 Chrome）。
   已登录账号时新建会话默认走 `deepseek-account`，可使用充值 / 赠金余额；
   也可以在 Models 页填 `sk-` API Key。

---

## AI 能真正辅助这个软件（不只是聊 GitHub API）

### 它「看得到」什么

| 渠道 | 内容 | 权限 |
|---|---|---|
| `GET /app/context` | 当前路由 / 仓库 / tab / 文件路径（App 自己写下的状态） | 零系统权限 |
| `GET /app/ui/state` | **githup WebView 当前已渲染的内容**：顶栏标题、正文文本、可见按钮列表、滚动位置 | 零系统权限 |
| `GET /app/summary?repo=O/R` | 当前仓库元数据 + README 摘要 + 顶层文件树（≤2KB，已算好） | 走 GitHub REST |
| `/gh/api/<GitHub REST 路径>` | 全方法透传，权限 = 当前登录令牌 | 令牌跟随登录态 |

> 这不是截图、不是控件树、不是无障碍服务，也不需要悬浮窗或存储权限。
> AI **没有屏幕像素、不能点按系统界面**；能读的是 App 自己 WebView 的 DOM。
> 上下文每轮由 DSH 插件自动注入 ≤600 字符，模型不用探测、不用翻全库。

### 它「做得到」什么

`POST /app/ui/act` 白名单动作（清单：`GET /app/ui/help`）：

| 动作 | 效果 |
|---|---|
| `navigate` / `open_repo` / `search` | 在 githup 内跳页、开仓库、搜索 |
| `click` | 点当前页可见按钮 / 链接（按文字匹配） |
| `star` / `watch` / `fork` | 走底座原有按钮与登录令牌 |
| `translate` | 切换整页翻译 |
| `scroll` / `refresh` / `back` | 滚动、刷新、返回 |
| `theme` / `copy` | 切主题、写剪贴板 |
| `POST /app/clone-repo` | 把改好的文件以**相同名称**的库传到当前登录账号，不动原库 |

每个动作都给一条用户可见的回执（「AI 助手：已 Star…」），不是只回一段文字。
动作只放行白名单，`arg` 过滤 `javascript:` / `://` / 换行 / 任意函数，
全部经 `window.Router` / `window.API` / `window.UI` 与页面原有按钮执行，
**令牌始终锁在设备 Keystore，不交给 AI**。

### 省 token 的设计

- DSN 插件 `dsh-plugin-githup-context` 监听 `agent/pre-step`，每轮只注入一次
  ≤600 字符的当前页面事实（写在 `$DSH_HOME/AGENTS.md` 与
  `$DSH_HOME/cordis.patch.yml` 的幂等块里）
- 模型不用再执行 `gh` CLI、检查环境变量、`curl /gh/repos` 拿 100 个仓库的大 JSON、
  逐个翻 `contents` —— 那些无用轮次是费 token 的主因
- 完整速览按需取 `/app/summary`，细节才读单文件；工作区说明压到 ≤1.5KB
- 当前仓库卡由 App 用 GitHub REST 算好常驻显示，**零 DeepSeek token**

> 「省 token」指少掉探测轮次与大 JSON；把代码发给 DeepSeek 做总结本身仍会
> 消耗模型 token，内核自带的系统提示与全量工具描述在 payload 里，本层无法裁剪。

---

## 底座原有功能（githup）

- **登录**：Personal Access Token / OAuth；Token 只存在设备 Keystore 保护的
  SharedPreferences，退出登录即移除。
- **首页 / 通知 / 探索 / 搜索**：活动时间线、未读角标、Trending、仓库与用户搜索、
  粘贴 GitHub 链接直接跳内部页。
- **个人主页**：仓库 / Star / 关注者、编辑资料、复制主页链接。
- **仓库**：简介、文件树逐级展开、README 渲染、代码高亮、图片预览、Issue / PR
  筛选与评论、Actions 日志与产物、Releases、Commits / Contributors / Branches /
  Tags、仓库设置（可见性、归档、删除）。
- **写操作**：新建仓库、上传文件、创建 Release、一键云端打包 APK（生成 workflow、
  写入 Secrets、触发构建、回 App 下载安装）。
- **设置**：主题、代码字号（12/13/15/17px）、启动页、API 配额、清除缓存、检查更新。
- **整页翻译**：滚动驱动、多引擎自动切换、跳过代码与版本号、译文本机缓存。
- **平板 / 大屏**：不锁竖屏、宽屏限宽居中、避开刘海、UI 字号按 smallestWidthDp 分档。
- **更新机制**：打开即查；版本号 x.y.z 前两位变化 = 强制更新，第三位 = 可选；
  版本号不变但包换了，靠 `version.json` 的 SHA-256 指纹识别「有新内容可用」；
  「跳过此版」记的是指纹。手动检查即使已最新也给一条 2 秒小提示。
- **下载**：统一存到 `Download/githup/`，APK 直接拉起系统安装器。

---

## 安全

| 措施 | 作用 |
|---|---|
| 官方专属签名 + 证书指纹钉扎 | 重签名包在装包前与启动时被拦下 |
| 五环防护链 | 签名 → 链签名 → 资源清单 → 身份 → 环境，闭环复检；删任一环即断链 |
| R8 混淆 + 资源压缩 | 抬高逆向成本 |
| 令牌不进 WebView / 不进 DSH 环境 | 只在 App 进程内附加到 `api.github.com` |
| `allowBackup=false` + 禁止数据导出 | 云端备份 / 换机转移不带数据 |
| 本机代理只认回环 + Host/Origin 围栏 | 挡外部网络访问 3081 |
| consent 闸门 | 未同意时 `/app/*` 返回 `page:null`，AI 如实说明「未开启」，禁止编造 |

`tools/gen-guard.py` 用官方私钥生成 `GuardKeys.java` 与 `assets/guard/assets.sha`，
`tools/gen-srcfingerprint.py` 写前端 `SRC_SHA256`。**改动源码后必须重跑这两个脚本**。

> 说句实话：不存在绝对无法反编译的安卓包。能做到的是抬高成本、把官方身份
> 钉在签名证书上、让被改过的包跑不起来。
>
> 另外：AI 替你操作软件、总结或修改代码时，内容会发到 DeepSeek 云端；
> 若当前登录令牌带 `delete_repo` / `admin:org` 等大权限，AI 经本机代理也拥有
> 同样权限。建议用 **fine-grained 最小权限令牌**，并定期轮换。

---

## 目录结构（合并版真实结构）

```
2/
├── app/
│   ├── build.gradle                    版本 / 签名 / minSdk24 / targetSdk28
│   └── src/main/
│       ├── AndroidManifest.xml          单 LAUNCHER（MainActivity）
│       ├── java/com/hubmobile/app/
│       │   ├── MainActivity.java       底座 WebView + AI 面板 attach
│       │   ├── AiPanel.java            同屏 AI 面板 / 品牌启动 / 风险确认
│       │   ├── JsBridge.java           前端 ↔ 原生总桥，路由上报
│       │   ├── HarnessBridge.java      展开 payload、起 node、注入插件
│       │   ├── Http.java / SecurePrefs.java / Guard.java / GuardKeys.java
│       │   └── harness/                ★ AI 侧全部原生实现
│       │       ├── AppContext.java    零权限页面状态 / consent / 同名库克隆
│       │       ├── AppUi.java         ★ /app/ui/state、/app/ui/act 反向操作软件
│       │       ├── UiBridge.java      ★ 同步执行底座 WebView JS 并取回结果
│       │       ├── RepoBrief.java     当前仓库速览（GitHub REST，零 LLM）
│       │       ├── GithupContextPlugin.java  DSH 插件安装器（两处 patch + 两份 node_modules）
│       │       ├── LocalBridgeServer.java   3081 本机桥全部路由
│       │       ├── GhBridge.java / GhBridgeDocs.java   GitHub 代理与说明
│       │       ├── OverlayService.java / HarnessAccessibilityService.java
│       │       ├── ShellBridge.java / KernelStore.java / BuildEnvInstaller.java
│       │       └── …（桌宠、语音、通知、日程、备份、虚拟屏探测等）
│       ├── assets/
│       │   ├── web/                    底座前端单页应用（js / css / vendor / guard）
│       │   ├── harness/                ★ 随包 DSH 插件源码与 AGENTS.md
│       │   └── payload.zip            DSH 引擎（约 160MB，CI 注入）
│       └── res/
├── harness/                            deepseek-harness-android 合入的源码树
│   ├── android-app/                    上游原生壳源码（参考/对照）
│   ├── selfbuild/ plugins/ skills/     手机内自建工具链、插件、技能
│   └── …
├── docs/  preview/  apk/  ci/         文档 / 截图 / APK 校验 / CI 报告
├── tools/                             防护链与源码指纹生成脚本
├── .github/workflows/build-ai.yml     ★ 构建 + 签名 + 发布 Release
├── MERGE_PLAN.md                       逐阶段改动记录（第 1～28 章）
├── version.json                        当前包 size / sha256 / 更新说明
├── build.gradle / settings.gradle / gradlew / gradle.properties
└── README.md / CHANGELOG.md / RELEASE_NOTES.md
```

---

## 构建

仓库自己的 Actions 完成全流程：拉源码 → 下载上游发行 payload → 解包注入 →
Gradle 编译 → 追加 payload → zipalign → 官方密钥 v1/v2/v3 重签 →
更新 `version.json` → 替换 Release 附件。

```sh
# 手动触发
gh workflow run build-ai.yml --ref main
```

本机编译（需 Android SDK）：

```sh
export ANDROID_HOME=$HOME/Android/Sdk
python3 tools/gen-srcfingerprint.py .
python3 tools/gen-guard.py .
./gradlew assembleRelease
```

> 只跑 `assembleRelease` 得到的是**不含引擎的骨架包**；完整 155MB 包由
> `build-ai.yml` 在 CI 里把 `assets/payload.zip` 追加进去并重新签名。

签名密钥与口令**不进仓库**（`.gitignore` 排除 `*.keystore`），
CI 使用仓库 Secrets。`keystore/pass.txt` 这类明文口令文件属于本地构建物料，
不应提交到公开仓库。

---

## 边界与许可

- 虚拟屏实时画面、特权 shell、Shizuku 相关能力需要 Shizuku 服务端与授权，
  本包未实现；`/shell` 只在 App 自身 uid 下运行，返回 `privileged:false`。
- 未真机跑完整 OAuth 授权、建库写入、DSH 插件加载链路；验证的是构建链、
  包身份、签名、防护链与包内结构自洽。
- 本应用为个人学习用途的第三方客户端，与 GitHub, Inc. 无隶属关系。
  上游 DSH payload 为第三方二进制，再分发请确认其许可。
- 完整改动记录见 `MERGE_PLAN.md`，每章都有「为什么 / 改了什么 / 边界」。
