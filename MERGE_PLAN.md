# 合并改造方案：以 Buwrt/githup 为底座，增添 deepseek-harness-android

> 本文只依据**源码**分析，未读取 harness 仓库的 README / CHANGELOG 等说明文档。
> 目标包名 `com.hubmobile.app`，签名沿用 githup 官方密钥（指纹 `863dd1cd…927`）。

## 一、结论先行

**能做源码级合并，不能做「二进制级直接合并」。** 两个仓库根本不是同一种构建物：

| | Buwrt/githup（底座） | guzhou079-arch/deepseek-harness-android |
|---|---|---|
| 工程形态 | 标准 Gradle 工程，`./gradlew assembleRelease` | 无 Gradle；靠 `apktool` / `d8` / `proot` 在**手机里**重打包上游发行 APK |
| 包名 | `com.hubmobile.app` | `com.deepseek.harness` |
| 入口 | `MainActivity`（WebView 壳） | `MainActivity`（525 KB 的原生壳，**重名**） |
| 运行时 | 纯前端 SPA + 少量原生桥 | 内置 `node` + `dsh` 内核 + proot/Alpine + `assets/payload.zip` |
| 源码树里的二进制 | 无 | **无**（0 个 `.apk/.so/.dex/.jar/.aar/.zip`；内核以 160 MB 级 Release 附件分发） |
| 外部依赖 | 无 | Shizuku（`rikka.shizuku.*` / `moe.shizuku.server.*`）、自带 `R` 资源、若干 hidden API |
| 防护 | 五环校验链（签名 ↔ GuardKeys ↔ assets.sha ↔ 版本 ↔ 环境） | `checks.manifest` 补丁漂移检测 + 底包 sha256 锁定 |

所以「把 harness 的 APK 解包、把 class 塞进 githup 的 dex」这条路走不通：harness 仓库里根本没有那个 APK 的骨架和内核，只有补丁脚本与 28 个 Java 源文件。

## 二、本次已完成的合并（源码 + 资产入包）

以 `Buwrt/githup` 公开库 v1.2.20 为底座，整个工程原样保留，新增：

1. **`harness/`** —— harness 仓库全部非文档源码（161 个文件）：
   `android-app/`（28 个 Java + res + Manifest）、`selfbuild/`（59）、`plugins/`（32）、`skills/`（22）、`patches/`。
   所有 `.md` / README / CHANGELOG / LICENSE 类文档按约定**不纳入**。
2. **包名适配**（脚本化全量替换）：
   - Java `package com.deepseek.harness` → `com.hubmobile.app.harness`
   - Manifest `package`、provider `authorities` → 同一子包
   - 70 处硬编码 `/data/user/0/com.deepseek.harness/files/...` → `/data/user/0/com.hubmobile.app/files/...`
   - `pm path com.deepseek.harness` → `pm path com.hubmobile.app`
   - 斜杠形式 `com/deepseek/harness` → `com/hubmobile/app/harness`
   放到子包而不是直接塞进 `com.hubmobile.app`，是为了避开与底座 27 个类（尤其同名 `MainActivity`）冲突。
3. **`app/src/main/assets/harness/`** —— 94 个 JS / Python / Shell / JSON / CSS 脚本资产 + `manifest.json` 清单。
   这些文件会被 Gradle 打进 APK，因此成品包里确实含 harness 的插件、技能与自构建脚本源码。
4. **`app/src/main/AndroidManifest.xml`** —— 补入 harness 所需 9 个权限、2 个可选硬件特性，只声明权限，不动原有组件。
5. **`.github/workflows/build-merged-apk.yml`** —— 用官方密钥构建并回写 APK 的工作流。

### 为什么五环防护链不用改

`tools/gen-guard.py` 只扫描 `app/src/main/assets/web/` 生成 `assets.sha`；
`tools/gen-srcfingerprint.py` 只扫描 `app/src/main/java/` 与 `assets/web/`。
本次所有新增内容都在 `assets/harness/` 与仓库根目录 `harness/`，**两个扫描范围都没碰**，所以
`GuardKeys.java` 的 5 环 CHAIN、`SignCheck.EXPECTED_SHA256`、`api.js` 的 `SRC_SHA256`、
`version.lock` / `app/build.gradle` 的版本号全部保持原值即可，包不会被自己的第 1/3/4 环拦下。
构建时工作流仍会跑 `gen-guard.py` 复核，用同一枚官方密钥重算，结果逐字节一致（已复算验证）。

## 三、要让 harness 的 Android 原生能力真正跑起来，还需要怎么改

这部分是**后续接入清单**，不是本次已编译进 dex 的内容（缺依赖会直接编译失败，不做假编译）：

| 项 | 现状 | 需要改什么 |
|---|---|---|
| Shizuku 依赖 | `RootShell` / `VsreenBridgeService` / `AccessibilityService` 引用 `rikka.shizuku.Shizuku`、`moe.shizuku.server.IRemoteProcess`、`IShizukuService` | `app/build.gradle` 增加 `mavenCentral()` + `implementation 'dev.rikka.shizuku:api:<ver>'` 与 `provider`；`IRemoteProcess/IShizukuService` 属 server 内部类，需改为反射或移除对应分支 |
| 资源 R | harness 用 `R.string.text_caption`、`R.drawable.ic_whale_black` 等 20+ 个自定义资源 | 把 `harness/android-app/res/values/{colors,dimens,strings,styles}.xml` 与 2 个 drawable 合进 `app/src/main/res/`，资源名加 `harness_` 前缀避免与底座冲突，同步改 Java 里的 `R.*` 引用 |
| 入口冲突 | 两边都叫 `MainActivity` | harness 侧重命名为 `HarnessActivity`，在 `AndroidManifest.xml` 用 `<activity android:name=".harness.HarnessActivity">` 单独注册，或在底座 SPA 里加一个跳转入口 |
| 组件注册 | harness Manifest 有 `EngineService` / `OverlayService` / `VsreenBridgeService` / `AccessibilityService` / `AlarmReceiver` / 2 个 provider | 逐条合并进主 Manifest；`authorities` 必须带 `${applicationId}` 前缀保证唯一；无障碍服务需 `res/xml/accessibility_config.xml` 一并合入 |
| targetSdk | harness 是 24/28，底座是 34 | `MANAGE_EXTERNAL_STORAGE`、`SYSTEM_ALERT_WINDOW`、`SCHEDULE_EXACT_ALARM`、`FOREGROUND_SERVICE_MICROPHONE` 在 34 上都要补运行时申请与 `foregroundServiceType`，`VsreenBridgeService` 的反射调用需按 Android 14 隐藏 API 限制验证 |
| dsh 引擎 | 仓库里没有 node / kernel payload | 把上游 `kernel-*.tar.gz` 与发行 APK 里的 `assets/payload.zip` 解出，按 `selfbuild/lib/selfbuild.js` 的 `payload-patch` 流程注入；`selfbuild.sh` 里的 `NODE/CURL/RUN/PY/TC/KEYS` 路径已改成 `/data/user/0/com.hubmobile.app/...`，但工具链本身要随包 |
| 前端入口 | 底座 SPA 无 harness 页面 | 在 `assets/web/index.html` 增加 `harness-bridge.js` 並从 `assets/harness/manifest.json` 读模块清单；**一旦改动 `web/` 下的文件，必须重跑 `gen-srcfingerprint.py` 与 `gen-guard.py` 并重算 `version.json` 的 `src_sha256`** |
| 自构建发布 | `release-*.mjs` / `release.sh` 指向上游仓库 | 目标仓库、tag 规则、`RELEASE_NOTES` 来源改成 Buwrt/2；Release 附件名保持 `githup-v<version>.apk` 以匹配底座更新检测的直链拼法 |

## 四、签名与包名口径

- `applicationId` / Manifest `package`：`com.hubmobile.app`（底座原值，**未改**）
- 软件名：`githup`（底座原值）
- `versionName 1.2.20` / `versionCode 1002020`（底座原值，避免触发版本锁定与防护链重算）
- 签名：官方 `keystore/githup-release.jks`（别名 `githup`），证书 SHA-256 `863dd1cd3752e59165e152bc4ab35fde478fcd296d724a6bc58cec0368184927`
  以加密 Actions Secret 形式提供给 `build-merged-apk.yml`，**不写入仓库文件**。
- 产物：`apk/githup-v1.2.20.apk` + `.sha256`，由工作流通过 REST API 回写仓库。

## 五、复现构建

```
# 在 Buwrt/2 的 Actions 页面手动触发 "Build Merged APK"（workflow_dispatch）
# 或本地（需 JDK 17 + Android SDK 34）：
python3 tools/gen-guard.py .
./gradlew assembleRelease --no-daemon
```

校验点：APK 内 `assets/harness/manifest.json` 存在、`AndroidManifest.xml` 的
`versionCode=1002020`、`resources.arsc` 含 `githup`、`classes.dex` 含官方证书指纹、
签名块为 v2/v3。

## 六、诚实边界

1. 成品 APK = **底座全部功能 + harness 的脚本/插件/技能源码资产 + 合并后的权限声明**；
   harness 的 28 个 Java 原生类与 dsh AI 引擎**尚未编译进 dex**，需按第三节补依赖与 payload 才能启动。
   没有这些依赖就强行 `include` 会直接 `compileReleaseJavaWithJavac` 失败。
2. harness 上游 `DeepSeekHarness-v*-dist.apk` 是第三方发行物，其内核与许可证需自行确认后再注入；
   本合并只搬运仓库内可见源码，不含任何上游二进制。
3. 未在真机验证读屏/悬浮窗/虚拟屏行为；本方案验证的是源码一致性、Gradle 可构建性与包身份。

---

## 七、阶段 B：让 AI 真正跑起来（已实施）

阶段一的包只有源码资产，没有内核。阶段 B 用 `build-ai.yml` 把上游发行包里的
`assets/payload.zip` 注入成品，并补一个精简启动桥。

### 为什么不移植上游 28 个 Java 类

上游 `MainActivity` 是 525KB 的原生壳，绑着 Shizuku AAR、20+ 个自有 R 资源、
悬浮窗/虚拟屏/无障碍服务、隐藏 API 反射。整套塞进底座会引入大量编译与运行时风险。
AI 对话本体其实在 payload 的 `dshroot/.../dsh/lib/bin.js` 里，壳只负责四件事：
展开 payload → 起 node → 抓一次性 token → 把 URL 交给 WebView。
所以只新增两个类，约 400 行：

- `HarnessBridge.java`：`AssetManager.open("payload.zip")` 流式展开 4 万+ 文件到
  `files/payload`（增量、补执行位），按上游 `spawnNode()` 同一套 env 拉起
  `node --expose-internals .../dsh/lib/bin.js web --host 127.0.0.1 --port 3080`，
  从 stdout 抓 `?token=`，合成 `http://127.0.0.1:3080/?token=xxx`。
- `HarnessActivity.java`：独立 WebView 容器，负责进度提示与加载。

**桌面双入口**：`githup` 仍是原来的 GitHub 客户端，`githup AI` 是 Harness 界面，
用 `taskAffinity` 隔开。底座 SPA 一行没改。

### 三个必须改的底座配置

| 改动 | 原因 |
|---|---|
| `targetSdk 34 → 28` | Android 10+ 的 W^X：`targetSdk>=29` 禁止 exec 私有目录下的 ELF，node 会 EACCES。上游锁 28 也是这个原因。compileSdk 仍 34，底座原有 API 不变 |
| `usesCleartextTraffic false → true` | 引擎是 `http://127.0.0.1:3080`。后续想收紧可换 `network_security_config` 只对 loopback 放行 |
| `requestLegacyExternalStorage=true` + 去掉 `WRITE_EXTERNAL_STORAGE` 的 `maxSdkVersion=28` | 降 targetSdk 后保住底座 DownloadProvider 写公共目录的能力 |

### 内核注入与重签名

GitHub Contents API 单文件上限 100MB，160MB 的包不能走 `apk/` 回写；
且 aapt2 处理超大 asset 不稳。构建链改成：

1. Actions runner 下载上游 `DeepSeekHarness-v1.49.1-dist.apk`（沙盒出口被策略拦，runner 不受限）
2. 取出 `assets/payload.zip`（159,738,098 字节 / 41,426 条目），把文本里
   `com.deepseek.harness` 替换成 `com.hubmobile.app`，保留原压缩方式重打包
3. `gen-srcfingerprint.py` → `gen-guard.py` 重算源码指纹与 assets.sha
4. `./gradlew assembleRelease` 出 1.9MB 的骨架包
5. Python 把 `payload.zip` 以 **STORE** 追加进 APK（跳过旧 v1 签名条目）
6. `zipalign -f 4` → `apksigner` 用官方密钥重签 v1/v2/v3
7. 上传 Release 附件；仓库 `apk/` 只留 sha256 与说明

### 使用

- 装完后桌面出现两个图标，点 **githup AI**
- 首次进入要展开 4 万多个引擎文件，进度走完才会启动 node，请耐心
- 进入 Web UI 后需自行配置 DeepSeek API Key（引擎侧设置），之后即可对话
- 读屏 / 悬浮窗 / 虚拟屏 / 定时任务这些**依赖 Shizuku 与上游原生壳**的能力本版没有，
  仍属于第三节的后续清单

### 已知代价

- 官方密钥以加密 Secrets 配在公开库 Buwrt/2；发版面扩大，建议后续换专用密钥或迁私库
- 上游 payload 是第三方二进制，再分发需自行确认其许可
- `usesCleartextTraffic=true` 是对整个 App 放开明文，底座自身的 GitHub 请求仍全走 HTTPS
- 无真机验证 node 在 targetSdk 28 下的实际启动与 WebView cookie 流程；已验证的是
  构建链、包身份、签名与包内结构

---

## 八、阶段 C：把「我的软件」放回主体（品牌启动页 + 开机对话）

阶段 B 打开 `githup AI` 后直接是上游 Harness 的 Web UI（「探索未至之境」），
整屏只有 DeepSeek 字样，主体感丢了；而上游原版那套 `DEEPSEEK HARNESS / BOOT SEQUENCE / PAYLOAD / COLD START`
冷启动动画属于它的 `MainActivity`，我们的精简桥里没有。

阶段 C 不动内核、不动上游前端，只在原生层补回品牌：

### 1) 启动页 = githup 版式 + 开机对话设计

`HarnessActivity` 重写成两段式：

- **装载段**：沿用底座启动页的视觉（`#0d1117` 深底、圆形 `ic_launcher`、`githup`、
  `GitHub 客户端 · AI 引擎装载中`），中部叠一张带四角取景框与点阵底纹的卡片，
  卡头 `GITHUP × DEEPSEEK / V1.2.20 · BOOT`，左下角 `BOOT SEQUENCE / PAYLOAD … MB / COLD START`
  —— 视觉语言取自上游开机画面，但**品牌归属 githup**。
- **对话段**：打字机逐句播放固定四句，主体是「我的软件」，DeepSeek 是被引擎请进来的一方：

  | 谁 | 说什么 |
  |---|---|
  | githup → DeepSeek | 你好，DeepSeek |
  | DeepSeek → githup | 很高兴为您服务 |
  | githup → DeepSeek | 让我们共同进步 |
  | DeepSeek | 好 |

- **真实进度进这一页**：`HarnessBridge.Callback` 增加 `onProgress(entries, written, total)`，
  解压每 400 项回调一次，底部角标实时显示 `展开 12,345 / 41,426 · 新增 …`；
  启动 node、等待 token、失败原因也都落在同一页，不再是一个裸 TextView + 转圈。

### 2) AI 界面保留 githup 在场

上游 Web UI 在 payload 的 `dsh-web-frontend/dist` 里，改它要动 4 万文件中的前端包、
还会连带影响引擎的静态资源门禁，风险大。折中：WebView 上方加一条原生品牌条
`githup AI ｜ 由 DeepSeek Harness 引擎驱动`，任何页面都在场。

### 3) 覆盖安装与包名的真相

- 包名**一直是 `com.hubmobile.app`**，`aapt dump badging` 输出：
  `package: name='com.hubmobile.app' versionCode='1002020' versionName='1.2.20'`。
  桌面两个图标是**同一个包的两个 LAUNCHER Activity**，不是两个 App。
- Android 覆盖规则：同包名 + 同签名证书 + versionCode 不降级。
  我们的包签名证书 `863dd1cd…927` 与 githup 官方一致 → 可覆盖 githup 官方版、
  `Buwrt/2` 第一版、`githup-release` 出的包。
- **不能覆盖上游原版 `com.deepseek.harness`**（包名不同、签名不同），只能卸载后装或并存；
  想让上游用户「直接升级」成我的软件，必须用对方的包名与签名，那与「包名签名以我为准」冲突。
  图三那种 `BOOT SEQUENCE / PAYLOAD 963 MB / COLD START` 正是上游原版的开机画面，
  不是本包的界面 —— 若手机上出现它，说明装的是另一个 App。

### 4) 未改的东西

`com.hubmobile.app`、官方签名、`1.2.20 / 1002020`、五环防护链、底座 SPA 全部保持；
只改了 2 个 Java 类并相应重算 `SRC_SHA256` 与 `assets.sha`。

---

## 九、阶段 D：真机反馈修复（引擎提前退出 + 进度显示误导）

真机截图暴露两个问题，均已修复：

### 1) 「展开 0/41,426 · 新增 0」不是没干活

覆盖安装不会清 `files/payload`。`extract()` 对大小一致的文件直接跳过，
原实现只在**新写入**时累加计数，于是全量命中缓存时显示 0，看着像解压失败。
现在改成「已校验 X / 41,426 · 本次新增 Z」，扫描数与新增数分开统计，
跳过分支也按每 400 项回报进度。

### 2) 「引擎进程提前退出」的根因与三层修复

`start()` 的等待循环里检测到 node 进程已退出。高概率原因是**端口 3080 被占**：

- 覆盖安装时系统杀掉 App，但上一轮 `node` 子进程不跟着退（孤儿进程），继续占端口；
  上游 `MainActivity` 有 `findEnginePid()` / `killEngineNow()`，精简桥初版漏了这一步
- 或用户手机上还装着上游原版 `com.deepseek.harness`，它的正式版引擎同样绑 3080

新 node 起来 `listen EADDRINUSE` → 立刻退出 → 报「引擎进程提前退出」。

修复：

| 层 | 做法 |
|---|---|
| 清场 | `killStaleEngines()` 扫 `/proc`，按**本 payload 绝对路径** + `bin.js` + `web` 锚定，SIGKILL 掉本应用自己的残留引擎（不会误伤上游原版或 Termux） |
| 避让 | `pickPort()` 探测 127.0.0.1 从 3080 起 12 个端口，被别的进程占了就顺延；`sActivePort` 让 `existingUrl()` 与 WebView URL 同步跟随 |
| 可诊断 | 进程退出时把 `dsh-web.log` 尾部 1200 字符**直接显示在开机页红字**里（EADDRINUSE / EACCES / 缺 .so 一眼可见），不再只甩一句「日志见 /files/」 |

另外把清空日志的动作挪到 kill 之后，避免旧进程持有的 fd 继续写进新日志造成 token 串台。

### 3) 桌面为什么有两个图标

不是两个软件。**同一个 APK、同一个包 `com.hubmobile.app`、同一份官方签名**，
只是 Manifest 里有两个带 `MAIN/LAUNCHER` 的 Activity：底座 `MainActivity`（标签 `githup`）
与 `HarnessActivity`（标签 `githup AI`）。启动器会把每个 LAUNCHER Activity 各显示成一个图标。
两者共用同一份私有数据、同一套权限、卸载时一起消失。

当初这么做是为了**不动底座 `assets/web/`**（改前端就要重算源码指纹与 `assets.sha`，
还会牵连五环防护链）。代价就是看起来像两个 App，容易误解。

若合并成单图标，有三种方向，需要产品上先定：

- A：只留 `githup`，在客户端内加「AI」入口（AI 从属于 GitHub 客户端）
- B：只留一个图标，冷启动先走品牌启动页 + 四句对话 + 装载进度，进 AI；GitHub 客户端作为二级页面
- C：保留两个图标但把 `githup AI` 改成快捷方式/小组件语义，避免「两个软件」观感

现阶段保持现状，等明确选择后再动。

---

## 十、阶段 E：真机第二轮 —— `libz.so.1` 缺失（软链没还原）

阶段 D 把真实报错显示到开机页后，真机给出确切原因：

```
CANNOT LINK EXECUTABLE "/data/user/0/com.hubmobile.app/files/payload/runtime/bin/node":
library "libz.so.1" not found: needed by main executable
```

### 根因

payload.zip 是 **zip 不存 symlink** 的打包方式。`runtime/lib/` 里只有带完整版本号的
真身（`libz.so.1.3.2`、`libsqlite3.so.3.53.4`、`libicuuc.so.78.3` …），
所有 soname 软链被拆成一份 **`runtime/lib/LINKS.txt`** 清单（tab 分隔：`链接名 \t 真身`）。

解析 `runtime/bin/node` 的 ELF `DT_NEEDED` 得到 12 个依赖：

| 依赖 | payload 里的形态 | 是否需要软链 |
|---|---|---|
| `libz.so.1` | `libz.so.1.3.2` | ✅ 缺 |
| `libsqlite3.so` | `libsqlite3.so.3.53.4` | ✅ 缺 |
| `libicui18n.so.78` / `libicuuc.so.78` | `*.78.3` | ✅ 缺 |
| `libcares.so` / `libffi.so` / `libc++_shared.so` / `libcrypto.so.3` / `libssl.so.3` | 真身同名 | ❌ |
| `libc.so` / `libm.so` / `libdl.so` | 系统提供 | ❌ |

linker 按 DT_NEEDED 顺序解析，第一个 `libz.so.1` 就断链，直接退出 —— 这就是
「引擎进程提前退出」的完整链条，和端口无关（阶段 D 的端口修复仍然保留，是另一类问题）。

上游 `MainActivity.applyLinks()`（第 6186 行）正是读 `LINKS.txt` 用
`Os.link` → `Os.symlink` → `copyFile` 三级兜底还原软链，在 `extractPayload()`
里紧跟解压、`setExecutables()` 之前调用。**精简桥初版漏了这一步**。

### 修复（只改 HarnessBridge.java）

1. `extract()` 末尾新增 `applyLinks(dir)`：照抄上游三级兜底；额外做**坏链自愈**——
   链接已存在但既不是软链（`Os.readlink` 失败）又长度不等于真身时，判定为坏的占位文件，删掉重建。
2. 新增 `ensureProfileKernelLinks(dir)`：读 `dshhome/profiles/web/PROFILE-KERNEL-LINKS.txt`，
   给 profile 树补指向内核树的 `dsh-tools` 包软链。缺它不会立刻崩，但会让部分插件**静默失效**。
3. `LD_LIBRARY_PATH` 追加 `nativeLibraryDir` 与 `/system/lib64` 等系统库目录，
   兼容部分 ROM 的 linker namespace。

### 关键：幂等，老用户不用清数据

`applyLinks()` **每次启动都跑**，`link.exists()` 才跳过。覆盖安装不清 `files/payload`，
41,426 个真身文件都还在，所以只要装上新包重进 `githup AI`，就会自动把缺的软链补齐。
阶段 D 那句「已校验 41,426 / 41,426 · 本次新增 0」是正常命中缓存，不是没干活。

只改 1 个 Java 文件，`SRC_SHA256` 与 `assets.sha` 相应重算；包名、签名、版本、双入口均不动。

---

## 十一、阶段 F：第二层能力（App 本地桥 + 悬浮桌宠 + 无障碍）

真机进到 Web UI 后，「大肥鱼 / 桌宠设置」整片报
`连不上 App 本地桥，刷新页面再试` / `状态读不到：Failed to fetch`。

### 根因

翻 payload 里的前端源码，`dsh-android-console/lib/client.js` 写死了：

```js
const OVERLAY_BRIDGE = 'http://127.0.0.1:3081/overlay';
```

`dsh-doc-actions` 用 `127.0.0.1:3081/open`；控制台页靠
`window.dshshell.ctlState()` / `ctlAct()` 判断是不是在安卓 App 内。
这些全部由**上游原生壳在 App 进程里**提供，node 引擎（3080）只负责 Web UI 与 AI 对话。
精简桥初版只有 3080 → 3081 无人监听 → fetch 直接拒绝连接。刷新多少次都没用。

无障碍插件走 `127.0.0.1:3181`，Shizuku 系插件还要 `APP_LOCAL_TOKEN` /
`APP_NOTIFY_PORT` / `APP_A11Y_PORT` / `SHIZUKU_DEX` 等环境变量——缺了整类能力静默失效。

### 实施方式：不移植 525KB 的 MainActivity

上游 `MainActivity` 里本地桥、桌宠、虚拟屏、Shizuku 全搅在一起，硬塞进来要引入
Shizuku AAR 与 20+ R 资源。改为移植**能独立编译的部分** + 自写精简实现：

| 文件 | 来源 | 作用 |
|---|---|---|
| `LocalFence.java` | 上游 `vscreen/LocalHttpFence` 改造 | Host/Origin/Referer 回环围栏，端口放宽到 3080–3091 以兼容引擎顺延 |
| `LocalBridgeAuth.java` | 新写 | `APP_LOCAL_TOKEN` 共享密钥，敏感路由鉴权 |
| `LocalBridgeServer.java` | 新写 | 127.0.0.1:3081 的 HTTP 服务 + CORS + 路由分发 |
| `OverlayService.java` | 新写 | WindowManager 悬浮桌宠（拖动/说话/长按进 AI）、流体云卡片 |
| `ShellBridge.java` | 新写 | `window.dshshell` 的 `ctlState` / `ctlAct` |
| `HarnessAccessibilityService.java` | 上游 `AccessibilityService` 1987 行移植 | 读屏 / 点按 / 手势 / 截图 / 通知捕获，监听 3181 |
| `NfcStore.java` / `LogShareProvider.java` | 上游移植 | 无障碍服务依赖的 NFC 状态中转、content:// 文件分享 |

**上游源码可直接搬的原因**：这两个类零 Shizuku 依赖、零 R 资源、零 MainActivity 引用
（`NfcStore` 只有 `MainActivity.appForeground` 两处，改成 `false`）。
`VsreenBridgeService` 依赖 `moe.shizuku.server.IRemoteProcess` AIDL，**不移植**。

### 现在各路由的行为

| 路由 | 状态 |
|---|---|
| `/overlay?action=status\|show\|hide\|fluid&on=\|vscreen&on=\|panel` | ✅ 真悬浮窗；`fluid` 飘卡片；`vscreen` 给明确的降级提示 |
| `/open` | ✅ 经 `LogShareProvider` 的 content:// 授权给系统应用打开/分享/定位目录 |
| `/status` `/changelog` | ✅ 返回壳状态、无障碍与桌宠状态 |
| `/notify` `/clipboard` `/setting` | ✅ 通知、剪贴板、跳悬浮窗/无障碍/电池设置 |
| `/shell` | ⚠️ 只在 App 自己的 uid 下跑，返回 `privileged:false`，不冒充 Shizuku |
| `/usage` `/schedule` `/voice` `/tts` `/lan` | ⚠️ 明确回「需 Shizuku 或上游壳」，不假装成功 |
| `window.dshshell` | ✅ 控制台页不再整片灰掉，能做的动作真做，不能做的给原因 |

### 权限与用户操作

Manifest 已声明 `SYSTEM_ALERT_WINDOW`、`PACKAGE_USAGE_STATS`、`RECORD_AUDIO` 等，
并注册了 `OverlayService`、`HarnessAccessibilityService`、`LogShareProvider` 与
`res/xml/harness_a11y_config.xml`。两个能力要**用户手动开一次**：

- 桌宠：点「显示桌宠」→ 若未授权会 Toast 提示，去系统设置开「显示在其他应用上层」
- 读屏：系统设置 → 无障碍 → `githup AI 读屏辅助` → 开启

`HarnessBridge` 现在给 node 注入 `APP_LOCAL_TOKEN`（首次生成并持久化到 `dsh_prefs`）、
`APP_NOTIFY_PORT=3081`、`APP_A11Y_PORT=3181`，并把 `engine_port` / `a11y_port`
写回 prefs 供无障碍服务读取。

### 仍未做的（诚实边界）

**虚拟屏（VirtualScreen Server）没有真做**。它要靠 Shizuku 的
`newProcess()` 以 shell uid 起一个 `app_process` 特权进程、再反射创建虚拟 Display，
纯 Android framework 做不到。本版把「悬浮屏预览」开关降级成一张说明卡片。
同理，特权 shell、定时任务里的系统级动作、通知监听的完整链路仍属第三层。

桌面双入口、包名、官方签名、`1.2.20 / 1002020`、底座 SPA 一行未改。

---

## 十二、阶段 G：把「上游能用、本版用不了」逐项补齐

真机确认第二层已生效后，逐条对照上游完整壳与 payload 插件，发现两大类差距：

### A. 桥已开但**协议没对齐**（功能等于没有）

翻 `dsh-tool-shizuku/lib/index.js`、`dsh-native-command/lib/index.js` 等插件源码，
它们发来的 body 与我阶段 F 写的解析完全不是一套：

| 路由 | 插件实际发的 | 阶段 F 写的 | 本版 |
|---|---|---|---|
| `/clipboard` | `{action:read\|write, content}` | `{text}` | ✅ 已对齐，返回 `{ok, content}` |
| `/setting` | `{key, value}` 写 Settings.System | `?which=` 跳页面 | ✅ 两者都支持，无 WRITE_SETTINGS 时自动引导授权 |
| `/shell` | `{command, timeout_ms, token}` | 仅 `command` | ✅ 超时/退出码/stdout/stderr 全对齐；探测到 `su` 自动提权并如实标 `privileged` |
| `/open-file` | dsh-native-command 用这个路径 | 只有 `/open` | ✅ 加了别名 |
| `/notify` | `{title, text}` | 已一致 | ✅ |
| `/usage` `/voice` `/tts` `/schedule` `/lan` | 插件真会调用 | 一律回「不支持」 | ✅ 全部实现 |

### B. 上游有、本版缺的原生能力

| 能力 | 上游实现 | 本版 |
|---|---|---|
| 语音识别 STT / TTS 播报 / 热词 | `VoiceManager` 874 行 | ✅ 原样移植 + `VoiceBridge` 包装成 `/voice` `/tts` |
| 应用使用统计 | `UsageStatsHelper` 92 行 | ✅ 移植到 `/usage`，无权限自动跳设置 |
| 定时任务（增删改查 / 到点拉起引擎） | `ScheduledTaskStore` + `ScheduleAlarms` + `ScheduleBridge` + `AlarmReceiver` + `EngineService` | ✅ 移植 + 自写精简 `ScheduleExecutor`（去 Shizuku，改走 `EngineRpc` 投给 3080） |
| 引擎前台保活 / 蓝牙耳机按键唤醒 | `EngineService` 156 行 | ✅ 移植，引擎就绪后 `keepAlive()` 拉起 |
| 分享到 App 的输入框草稿 | `DraftInputBridge` + `ShareInbox` | ✅ 移植，注入 `window.dshinput` + `__DSH_INPUT_TOKEN`，并注册 ACTION_SEND |
| NFC 读卡 | `NfcStore` + MainActivity reader mode | ✅ `HarnessActivity` 按 arm 状态动态开关 `enableReaderMode`，不长期抢占 NFC |
| 通知捕获 / 读屏 / 点按 / 手势 / 截图 | `AccessibilityService` 1987 行（阶段 F 已移植） | ✅ 3081 `/notifications*` 现在**代理到 3181** 实时结果，不再只返回空归档 |
| 日志 / 版本 / 备份清单 | 控制台页 | ✅ `/log` `/update` `/backup` 与 `ctlAct` 十余个动作 |
| 控制台 `window.dshshell` | 阶段 F 简版 | ✅ 扩展权限、桌宠、语音、NFC、日志、更新、备份动作 |

### C. 仍然做不到（物理限制，不是漏做）

| 能力 | 为什么 |
|---|---|
| **虚拟屏 VirtualScreen** | 需 Shizuku `newProcess()` 以 shell uid 起 `app_process`，再反射创建虚拟 Display；`VsreenBridgeService` 2072 行依赖 `moe.shizuku.server.IRemoteProcess` AIDL，纯 framework 做不到 |
| **Shizuku / root 特权 API 全量** | 没有 Shizuku AAR，也没有用户在 Shizuku App 里的授权；本版只在**检测到 su 时**让 `/shell` 自动提权，其余如实回 `privileged:false` |
| **完整桌宠面板（4402 行上游 OverlayService）** | 依赖 36 处 R 资源（drawable/dimen/color）与 6 处 MainActivity；本版是 351 行精简实现：悬浮球 + 拖动 + 说话 + 流体云卡片 |
| **手机内自建编译环境 / 自签名发布** | `BuildEnvInstaller` 673 行要从 GitHub 拉整套工具链；底座签名体系与上游不同，且需网络与大量存储 |
| **时光机全站备份 / 内核热切换** | `BackupRestore`/`KernelStore` 已搬进源码但需上游 MainActivity 的完整调用链；本版 `/backup` 只给目录清单与体积，不假装能恢复 |
| **上游原版用户直接升级成我的包** | 包名 `com.deepseek.harness` + 对方签名，与「包名签名以我为准」冲突 |

### D. 需要用户手动开的能力（本轮新增）

- 悬浮窗权限（桌宠）
- 无障碍 `githup AI 读屏辅助`（读屏/通知）
- 麦克风 `RECORD_AUDIO`（语音识别，App 启动时申请）
- 「修改系统设置」`WRITE_SETTINGS`（`/setting` 写值）
- 「使用情况访问权限」`PACKAGE_USAGE_STATS`（`/usage`）
- NFC 由 `android_nfc_arm` 工具按需开启，10 分钟 TTL，不长期占用

本轮共新增/移植 20 个 Java 类，`SRC_SHA256` 与 `assets.sha` 重算；
包名、官方签名、`1.2.20 / 1002020`、底座 SPA、桌面双入口均不动。


---

## 十三、阶段 H：桌宠按随包规格书重做（真机截图反馈）

用户真机截图反馈：桌宠页显示「在线」时，页面中间压着一个巨大的深蓝圆球 + 白「鲸」字，
把「大肥鱼」标题和头像都盖住了；另一张截图里开关开着却显示「已隐藏」，左下角还有个
黑色小圆（系统无障碍快捷按钮，不是 App 的桌宠，需去系统设置关「无障碍快捷方式」）。

### 根因

payload 里本来就有**桌宠唯一配置源** `pet/pet.json` + 立绘 `pet/pet-body.png`
（334×331，鲸鱼娘；注释写明「本项目自己的形象，随包可分发」）。阶段 F 的精简实现
完全没读它，自作主张画了 76dp 纯色圆 + 「鲸」字，还放在 `x=24dp, y=180dp`
——正好落在设置页标题区。

规格书要求：

| 字段 | 规格 | 阶段 F 实际 |
|---|---|---|
| `appearance.ballDp` | 40 | 76dp |
| `appearance.body` | `pet-body.png` 鲸鱼娘立绘 | 纯色圆 + 「鲸」字 |
| `appearance.cropCenterX/Y` | 0.45 / 0.34 圆形裁切 | 无 |
| `appearance.strokeRatio` | 0.045 描边 | 2dp 硬编码 |
| `tap.onBall / onBubble` | line / panel | 轻点飘卡片 / 长按跳回主界面 |
| `lines` | 10 条内置台词 | 5 条自编 |
| `bubble` | `#E6101A2B` 圆角 12dp、6s 自动隐藏、0.72 屏宽 | 普通卡片 |
| `card.atTop` | 状态栏下沿胶囊，点击展开 | 无 |

还有两个确凿的协议 bug：

1. 前端 `dsh-android-console/lib/client.js` 里 `fluid`/`vscreen` 开关读的是 **`j.on`**，
   而阶段 F 的 `/overlay` 一律回 `statusJson`（只有 `fluid` 字段、没有 `on`）→
   `!!j.on === false` → **开关点完立刻弹回关闭**。
2. `OverlayService` 被系统 `START_STICKY` 重建时 `onCreate` 不恢复显示状态 →
   `running=true, visible=false` → **开关开着却显示「已隐藏」**。

### 修改（只动 2 个 Java 文件）

- `OverlayService.java` 按 `pet.json` 重写：
  - 读 `files/payload/pet/pet.json` 与 `pet-body.png`，预生成圆形裁切位图
  - 悬浮球 40dp、靠**屏幕右边缘**（`screenW-40dp-10dp`、y≈32% 屏高），不再压页面中间
  - 轻点 → 台词气泡（10 条随机、`#E6101A2B`、圆角、6 秒自动隐藏）
  - 点气泡 → 桌宠面板（台词 + 打开 githup AI + 收起）
  - 长按 → 接 `VoiceBridge` 真语音识别，识别结果飘流体云，不再跳回主界面
  - 拖动、贴边半藏（`dock`）、`onCreate` 按 prefs 恢复 `pet_running/pet_visible`
  - 流体云改成规格书的**状态栏下沿胶囊**，点一下展开成卡片
- `LocalBridgeServer.java`：
  - `/overlay` 新增 `on` 字段；`show`/`hide` 给乐观状态，`fluid`/`vscreen` 回显正确
  - `/notify` 在流体云开启时同步推胶囊

### 边界

「悬浮屏预览」仍是降级卡片（虚拟屏需 Shizuku）；「AI 回复自动飘流体云」需要引擎侧
hook，本版先覆盖通知与台词/语音结果。桌面双入口、包名、官方签名、版本、底座 SPA 均不动。


---

## 十四、阶段 I：桌宠换成上游 4402 行完整实现

用户要求「和上游一模一样」。核查后确认：**payload 里本来就有桌宠唯一配置源
`pet/pet.json` 与全身立绘 `pet-body.png`**，阶段 H 的 562 行精简实现把它画成了
40dp 圆球，而上游 v1.90 起用 `bodyDp=96` 透明底全身像（`loadPetBody()` 优先），
圆形头像只是没图时的兜底。

### 移植范围

- 完整 `OverlayService.java`（上游 4402 行 → 本版 4466 行），只改 3 处：
  包名、通知回跳 `MainActivity → HarnessActivity`、
  `AccessibilityService → HarnessAccessibilityService`
- 上游 7 处 R 资源：`colors.xml`（accent_brand / panel_bg / panel_text_bright /
  panel_text_dim）、`dimens.xml`（text_caption）、`drawable/ic_whale_black.xml`
- `VsreenBridgeService.java` 占位：4 个入口安全降级（虚拟屏需 Shizuku）
- 本地桥兼容层：`dispatch()` / `statusJson()` / `canDraw()` / `get()` /
  `pushFluid()`，`/overlay` 改走 `togglePanelFromBridge` / `setFluidFromBridge` /
  `setVscreenFromBridge` / `debugState`
- `onStartCommand` 接 `bridgeAction`：show / hide / panel / dock / fluid / vscreen / card

### 随之生效的上游能力

全身立绘与三层动画、272dp 展开面板、球内直接发消息给 AI（RPC + `ovl-readreply.js`
读 `session.v4.jsonl.zstd`）、台词/余额/今日/峰谷加权泡泡、流式流体云卡片、
贴边半藏（露 68%）、拖底隐藏、通知栏两态开关、每 2 秒探引擎端口、
扫 `/proc` 会话锁判断 AI 工作状态、前后台联动。

### 仍然 100% 不了的（详见 `githup桌宠对齐上游_所需物料清单.md`）

| 类别 | 内容 |
|---|---|
| 需用户授权素材 | `pet/whale-shota.png`（第三方，上游自己都不随包；有全身图时不需要） |
| 需手机侧 | Shizuku App + 授权 + AIDL（虚拟屏实时画面 / 特权 shell） |
| 需用户配置 | DeepSeek API Key（余额泡泡 / 峰谷计费读 credentials.yaml） |
| 按用户口径冲突 | 包名、签名、品牌条；上游原版用户不能覆盖升级 |

桌面双入口、包名、官方签名、`1.2.20 / 1002020`、底座 SPA 均未改动。


---

## 十五、阶段 J：把「和桌宠一样被精简」的地方全部对齐

阶段 I 把桌宠换成上游 4402 行完整实现后，用户要求把其余同类位置一并改完。
全工程盘点出 5 处同样的「精简壳替代 / unsupported 占位 / 假成功」：

| 位置 | 之前 | 上游 |
|---|---|---|
| `MainActivity` 控制台 | `HarnessActivity` 508 + `HarnessBridge` 548 | 11179 行 |
| `ShellBridge` | 165 行，36 个动作大半假成功 | `ctlStateJson` + `ctlActionOnUi` |
| `/kernel` `/buildenv` | `unsupported(...)` | KernelStore / BuildEnvInstaller |
| `ScheduleExecutor` | 102 行精简 | 359 行完整 |
| `VsreenBridgeService` | 31 行永远 false | 2072 行 Shizuku 桥 |

### 修改清单

**完整搬入 3 类**：`BuildEnvInstaller.java`(673)、`KernelStore.java`(538)、
`ScheduleExecutor.java`(359，替换 102 行精简版)。适配仅 2 处：
通知回跳 `MainActivity → HarnessActivity`、Shizuku 直接引用改反射。
保留 `execute/tickLife/log/history` 四个接口，`AlarmReceiver` / `ScheduleBridge` /
`LocalBridgeServer` 零改动。补 `import com.hubmobile.app.R;`（ScheduleExecutor 用了
`R.drawable.ic_whale_black`，包名改变后 R 不在同包）。

**`HarnessBridge` 新增 2 个公开方法**：`stopEngine(Context)` 复用 `killStaleEngines`、
`isRunning()` 复用 `portBusy`。

**`LocalBridgeServer`**：`/kernel` `/kernels` `/buildenv` 从 unsupported 换成真路由，
支持 activate/remove/deactivate/install、resolve/cancel/install，
状态读 KernelStore / ShellBridge.buildEnvJson。

**`ShellBridge` 165 → 709 行**：
- `ctlState()` 补齐 17 个字段（extract / engine / rescue / theme / reasoning /
  perm / workspace / plugins / buildEnv / kernel / log / voice / life / schedules
  / overlay / vscreenStatus …），全部读真实类
- `ctlAct()` 36 个动作落地：HarnessBridge、BuildEnvInstaller、KernelStore、
  BackupRestore（真打包 dshhome 为 .dshbackup）、LogShareProvider、
  NotificationArchive、ScheduleBridge、VoiceManager/VoiceBridge、NfcStore、
  OverlayService；`life.*` 照搬上游 `runLifeToolCommand`（node + life.js）与
  `readLifeDbState`（只读 life.db）
- 新增 `argParam()`：前端桌宠页传 `action=fluid&on=1`、控制台页传 `"1"`，
  两种参数形式都吃下

**`VsreenBridgeService` 31 → 92 行**：反射探测 Shizuku binder + 授权、
检查 `vscreen_shizuku.jar`、按「未装 / 未授权 / 缺 jar」给准确原因，不再静默 false。

### 仍然 100% 不了

虚拟屏实时画面缺三样：Shizuku 服务端 hidden AIDL
（`IRemoteProcess` / `IShizukuService`）、`assets/vscreen_shizuku.jar`
（只在上游发行 APK 里，本版 payload 没有）、用户侧 Shizuku 授权。
特权 shell 同因。启动动画按用户口径保留品牌版四句对话，不换回上游
`BOOT SEQUENCE`。包名 / 签名 / 品牌条按「以我为准」。

上游 MainActivity 的原生控制台齿轮与时光机 UI 弹层未搬：功能已全部通过
网页控制台落地，再套一层原生 View 是重复建设且要动底座 SPA 与防护链。


---

## 十六、阶段 L：两个图标合成一个 APP，两个能力同屏共存

### 为什么之前有两个图标

不是装了两个软件，是 `AndroidManifest.xml` 里**两个 Activity 都注册了
`MAIN` + `LAUNCHER`**：`.MainActivity` 与 `.HarnessActivity`，后者还带
`android:taskAffinity="com.hubmobile.app.ai"` + `launchMode="singleTask"`。
启动器给每个 LAUNCHER 各画一个图标；点「githup AI」= 进另一个任务栈的整屏
DSH 装载页，按返回 = 退回 githup。用户明确不要这种「进入 / 退回」。

仓库里曾经有一份「去掉 LAUNCHER」的本地改动（阶段 K 注释 + disabled alias
墓碑），但**没有进到发布包**，所以用户装的包仍是双图标。

### 改成什么

用户口径：**一个图标，点开之后 GitHub 客户端与 AI 同时都能用，不跳页面。**

- Manifest：唯一 LAUNCHER = `.MainActivity`；`.HarnessActivity` 摘掉
  MAIN/LAUNCHER 与 taskAffinity，保留 SEND 分享 filter；
  `.AiLauncher` 作为 `enabled=false` 的 activity-alias 墓碑，覆盖安装时让
  启动器移除旧的「githup AI」快捷方式
- 新增 `AiPanel.java`（559 行）：叠在 `MainActivity.contentRoot` 上的常驻面板
  - 冷启动**直接就是 GitHub 客户端**，没有任何整屏启动页
  - 右下角悬浮球（可拖动）展开 / 收起面板，默认 68% 高、可切全屏
  - 两个 WebView 同进程同界面同时活着：底座 SPA 一直在，DSH WebView 懒创建
    后保留（保住会话），展开时上层 GitHub 仍可见可点
  - 引擎 `HarnessBridge.prepare` 在 attach 后 700ms 后台静默装载，
    3081 本地桥 `LocalBridgeServer.start` 同时起，点开基本秒开
  - `dshshell` = ShellBridge、`dshinput` = DraftInputBridge，
    麦克风权限、NFC reader mode、EngineService 保活一并接上
  - 四句品牌对话保留为**面板装载卡**里的紧凑打字机，不整屏、不阻塞
  - 返回键：面板开着只收起，不退出、不「退回 githup」
- `MainActivity`：boot 末尾 `AiPanel.attach`；`onBackPressed` 优先收面板；
  `onResume/onPause/onDestroy/onNewIntent` 转发；`open_ai` extra 触发自动展开
- `HarnessActivity`：不再整屏展示 DSH。任何外部启动（旧桌面快捷方式、
  系统分享）一律 `startActivity(MainActivity)` + `open_ai` + `finish`，
  分享 intent 经 `AiPanel.setPendingShare` 送进 AI 输入框草稿，
  避免旧快捷方式变成点不开的死图标

### 保持不变

`com.hubmobile.app` / `1.2.20` / `1002020`、官方签名、底座 SPA、
五环防护链（已重算）、payload 41,426 个引擎文件、桌宠与控制台全量对齐成果。

### 边界

未在真机验证两个 WebView 同时驻留时的内存占用与输入法叠加；
已验证 Java 编译、Manifest 合法性、包身份、签名与防护链自洽。


---

## 十七、阶段 M：设置页开关 → 把 GitHub 能力授权给 AI / 工作区能选 GitHub 仓库

### 先回答「现在 AI 能不能辅助 githup」

**不能**。之前两套完全独立：

- githup 的 token 锁在 `SecurePrefs`（Android Keystore AES-GCM），只在 App
  进程里附加到发往 `api.github.com` 的请求
- DSH 是另一个进程里的 node，3081 本地桥**没有任何 GitHub 路由**
- payload 里**没有 `gh` CLI**（只有 `git-remote-https`），自带的
  `@deepseek-ai/dsh-experimental-forge-github` 明确写着：子进程继承的宿主
  环境会剥掉名称含 `KEY / PASSWORD / SECRET / TOKEN` 的变量，
  `GH_TOKEN` / `GITHUB_TOKEN` 不会被转发

所以「把 token 塞进环境变量 / 写进配置文件」这条路**根本走不通**。

### 关键设计：授权 ≠ 交出令牌

底座对用户的承诺是「令牌只保存在设备本机、不会上传到任何服务器」。
直接把 token 交给 DSH 会同时破坏这条承诺、并让第三方 AI 引擎有可能把
私有仓库代码或令牌内容发到云端。

因此「授权」实现为 **127.0.0.1:3081 上的一条本机只读代理**：

- token 继续锁在 `SecurePrefs`，只在 App 进程内附加到 `api.github.com`
- DSH / node 侧拿到的是一个**不含任何凭据**的回环 URL
  `http://127.0.0.1:3081/gh/api/...`
- 令牌既不进 DSH 的环境变量，也不进它的任何文件、不写进工作区

三道闸门：① 设置页开关默认关，关着时 `/gh/*` 全部 403；
② 只放行 GET，POST/PUT/PATCH/DELETE 一律拒绝（AI 改不了仓库）；
③ 目标域只允许 `api.github.com` 与 `codeload.github.com`（zipball 重定向时
手动跟随，且只在 api 域带 Authorization）。

### 新增 / 改动

| 文件 | 内容 |
|---|---|
| `harness/GhBridge.java`（新，357 行） | 开关状态、`SecurePrefs` 读令牌、只读 REST 代理、仓库列表、工作区镜像展开 |
| `GhRepoPickerActivity.java`（新，140 行） | 透明 Activity，弹 AlertDialog 列当前令牌可见的仓库 |
| `harness/LocalBridgeServer.java` | 新增 `/gh/repos` `/gh/workspace` `/gh/api/<REST 路径>`；`/gh/` 免密但受开关控制；dispatch 增加 method 形参 |
| `harness/ShellBridge.java` | `ctlState().workspace` 改读 `GhBridge.workspaceState()`；`ctlAct("workspace.change")` 改成唤起仓库选择器 |
| `JsBridge.java` | 新增 `aiGithubEnabled()` / `setAiGithubEnabled(boolean)` / `aiGithubWorkspace()` 三个窄接口 |
| `assets/web/js/page-user.js` | 设置页新增「AI 辅助 githup」开关行与事件分支 |
| `assets/web/js/i18n-strings.js` | 补 6 条英文词条 |
| `AndroidManifest.xml` | 注册 `GhRepoPickerActivity`（非桌面入口） |

### 工作区怎么落地

上游控制台的「AI 工作区（可选）」原是唤起系统目录选择器选本地文件夹。
现在改成：

1. `GhBridge.repos()` → `GET /user/repos?per_page=100&sort=updated`
2. 用户在列表里选一个（私有库带 🔒 标记，当前选中带 ✓）
3. `GhBridge.materialize()` → `GET /repos/{full}/zipball`，手动跟随 302 到
   `codeload.github.com`，单次下载后解压到
   `files/gh-workspace/<owner>__<repo>/`（上限 60MB / 4000 项 / 单文件 2MB）
4. 在目录里写一份 `GITHUP.md`，告诉 AI：这是只读镜像、怎么用
   `/gh/api/` 查 Issue/PR、令牌不在它手里、不要尝试寻找或输出
5. `ctlState().workspace.path` 指向该目录，DSH 的 file / bash 工具可直接读代码

### 用户操作路径

我的 → 设置 → 打开「AI 辅助 githup」→ 点右下角 AI 球 → DSH 设置 →
控制台 →「AI 工作区（可选）」→ 选择 → 挑一个 GitHub 仓库 → 展开完成。
关闭开关会顺手清掉已选工作区。

### 边界

- **只读**：AI 能读代码、Issue、PR、提交、分支，不能提交、改文件、开 PR
- 镜像是**快照**，不会自动同步；刷新需重新选择工作区
- 超过 60MB 的仓库不展开，仍可用 `/gh/api/` 按需读取
- 开关打开期间，本机任意能连 127.0.0.1:3081 的进程都能经该只读代理访问
  GitHub（回环 + Host 围栏已挡外部网络，但不隔离同机 App）
- 未在真机验证 zipball 解压、AlertDialog 列表与 AI 侧 curl 链路；
  已验证 Java 编译、Manifest、JS 语法、包身份、签名与防护链自洽


---

## 十八、阶段 N：为什么「赠送余额」在上游能用、我们不能用

### 现象

账号与余额页显示「星空 / 充值余额 ¥1.66 / 赠金余额 ¥0.93」，账号显然已登录；
但发消息报：

```
llm-deepseek: no API key for provider route "deepseek-official";
store DEEPSEEK_API_KEY through the credentials service
(the web Models page writes it), or export DEEPSEEK_API_KEY in the launching environment
```

### 根因：两条并列的鉴权路由，钱包不共用

内核 `dsh-base/cordis.patch.yml` 同时挂了两条 provider：

| 路由 | 凭据 | 消费什么 |
|---|---|---|
| `deepseek-official` | `DEEPSEEK_API_KEY`（`sk-`） | 官方 API Key 计费 |
| `deepseek-account` | 系统浏览器登录网页账号后的 `x-dsh-auth-token` | 平台账户的**充值余额 + 赠金余额**（`bonus_wallets`） |

`@deepseek-ai/dsh-llm-deepseek-account` 文档明确写：未登录或目标不允许账号鉴权时
以 `ACCOUNT_SIGN_IN_REQUIRED` 失败，**不会回退到 API key**。反过来也一样：
账号里的赠金不能给 `deepseek-official` 用。

截图里的 ¥0.93 赠金来自
`dsh-deepseek-account-platform` 的 `getUnnotifiedBonuses` / 钱包接口，
属于 `deepseek-account` 路由。

### 为什么默认是错的

`dsh-base/cordis.patch.yml`：

```yaml
- id: agent-default-model
  name: '@deepseek-ai/dsh-agent-default-model'
  config:
    provider: deepseek-official
    model: deepseek-flash
```

`agent-default-model` 决定**新建会话**用哪条路由。我们的包是从上游发行 payload
全新展开的干净 `$DSH_HOME`，`dshhome/profiles/web/cordis.patch.yml` 只有两条 UI
配置，没有任何覆盖 → 每个新会话都落在 `deepseek-official` → 找 `sk-` 找不到 → 报错。

上游能用，是因为那份 `$DSH_HOME` 里已经登录过账号、并在模型选择器里选过
「DeepSeek 账号」分组；`session/selectModel` 会把选择**持久化进 profile 的
`agent-default-model`**，之后新建会话一直走账号路由。上游另有
`patches/account-ui-enable/`（放开 `dshDesktop` 桌面独占门禁以启用账户 UI）
与 selfbuild/v118 overlay 等运行时补丁，这些历史状态不会随 payload 一起过来。

加剧因素：本版 `dshhome/cordis.patch.yml` 里 `ui-model-selection` 被禁用
（交给 `dsh-codex-power-slider` 接管 composer 模型槽），而 Models 设置页的
「DeepSeek 账号」行**只显示「已登录」状态、不提供编辑器/切换**，所以 UI 上
也不容易找到把会话切到账号路由的入口。

### 修复

新增 `harness/AccountDefaultRoute.java`，在 `HarnessBridge.start()` **起 node 之前**
调用，往用户层 `dshhome/profiles/web/cordis.patch.yml` 幂等写入：

```yaml
# githup-account-route begin
- id: agent-default-model
  config:
    provider: deepseek-account
    model: deepseek-flash
# githup-account-route end
```

- **仅当检测到账号已登录**（`$DSH_HOME/.credentials.yaml` 里存在
  `deepseek-account-platform/default` grant 记录）才写 → 赠金可用
- 未登录时摘掉该块，回落内核默认 `deepseek-official` → 有 `sk-` 的用户照常用，
  不会让新会话直接 `ACCOUNT_SIGN_IN_REQUIRED`
- 只写用户层 patch，**不动 160MB payload**；每次启动幂等重写，覆盖安装 /
  payload 刷新后自动恢复
- profile patch 在 bundle layer 之后应用，覆盖 `dsh-base` 的默认值

### 生效条件（必须说清）

- 引擎已在跑时 `start()` 会复用旧 URL 不重进 → 装完**完全退出 App 再重进**
- `agent-default-model` 只管**新建会话**；报错的那个旧会话已固化成 official，
  需**新建一个会话**（或在会话内切到「DeepSeek 账号」分组）
- 账号登录态过期时会提示 `ACCOUNT_SIGN_IN_REQUIRED`，去账号页重新登录即可

### 边界

未真机验证账号 grant 的文本形态与 `/models` 目录拉取；检测采用宽松文本匹配，
不解析 YAML。若账号记录格式与预期不同，可在设置里手动切模型，或把日志发回调整。
已验证 Java 编译结构、Manifest、包身份、签名与防护链自洽。


---

## 十九、阶段 O：权限跟随登录令牌，取消开关与只读限制

### 触发

用户把 DSH 里 AI 的一段回答贴了回来：

> 「gh 未安装 / GITHUB_TOKEN 无 / hosts.yml 不存在 / 没有 GitHub 专用工具 /
> api.github.com 未认证请求 200 → **我没有你的身份凭证，看不到你账号下的仓库**」

同时明确要求：**「用户登录的令牌有什么权限，AI 对于 github 就有什么权限，而不是开关设置的」**。

### 为什么 AI 会得出「看不到」

那段回答是 **DSH 里的 AI 检查它自己的运行环境**后给出的，检查项全对：

- `gh` CLI 确实没装（payload 里只有 `git-remote-https`）
- `GITHUB_TOKEN` / `GH_TOKEN` 确实没有 —— 这是**故意的**：上游
  `forge-github` 明确会剥掉名称含 TOKEN/KEY/SECRET 的环境变量，
  阶段 M 也刻意不让凭据进 DSH 的 env 或文件
- `hosts.yml` / `.netrc` / SSH key / git credential helper 确实不存在
- 公开根端点 `https://api.github.com` 未认证返回 200，那段 JSON 只是
  **端点目录**，任何人都能拿，**不代表能看到你账号下的仓库**

它漏掉的是：阶段 M 在 **127.0.0.1:3081 留了一条本机代理**，但那属于
「**工具型访问**」（要主动 curl），不是「环境型凭据」（不需要调用就存在）。
AI 不知道这条通道存在 → 按常规检查得出「没有凭证」→ 结论错在前提。

加剧因素：阶段 M 只把 `GITHUP.md` 写进**用户选了 GitHub 仓库之后**的镜像
目录；没选工作区时，AI 的 cwd 里根本没有说明文件。

### 用户口径要求的三处改动

| 之前（阶段 M） | 现在（阶段 O） |
|---|---|
| 设置页独立开关，默认关 | **取消开关**，改成登录态状态行 |
| 只放行 GET，写操作一律拒绝 | **GET/POST/PUT/PATCH/DELETE 全放行** |
| 「AI 权限 = 只读代理」 | **AI 权限 = 当前登录令牌的权限**（含私有库、写操作） |

权限来源只有一个：`SecurePrefs` 里的 `gh_token`。退出登录 / 清空令牌 →
`/gh/*` 立刻 403；换成细粒度 token → AI 也只剩细粒度权限。

### 具体改动

| 文件 | 内容 |
|---|---|
| `harness/GhBridge.java` | `enabled()` 改为 `!token().isEmpty()`；`setEnabled()` 退化为 no-op；新增 `status()` 给设置页状态行；`proxy()` 全方法透传 + body 转发；`readonly:false`；下载类端点挡掉（防重定向带令牌） |
| `harness/LocalBridgeServer.java` | 去掉 `/gh/` 的 405 方法限制；新增 `GET /gh/help` 自描述路由；`/gh/api/` 把 method 与 body 一起传给 proxy |
| `harness/GhBridgeDocs.java`（新） | 起 node 前往 AI 默认工作区写 `GITHUP.md` + `README.md`，并在 `$DSH_HOME` 根写一份；文案开宗明义写「**别再用 gh CLI / 环境变量判断权限**」 |
| `HarnessBridge.java` | 启动前调用 `GhBridgeDocs.install()`；env 注入 `DSH_WORKSPACE`（未选仓库 → 说明目录；选了 → 镜像目录），`bash-local` 的 cwd 直接落在说明上 |
| `JsBridge.java` | 新增 `aiGithubStatus()`；`aiGithubEnabled()` 语义改为登录态 |
| `page-user.js` / `i18n-strings.js` | 开关行改成状态行，点击只解释口径、不切换 |
| `ShellBridge.java` / `GhRepoPickerActivity.java` | 错误提示由「请打开开关」改为「请先登录」；note 文案同步 |

`proxy()` 改用底座 `Http.requestBytes()`：它基于 Socket 实现，**支持 PATCH**
（`HttpURLConnection` 在 Android 上会抛 `Invalid HTTP method: PATCH`），
且 CA / 超时口径与客户端一致。

### 让 AI「自动看到」的关键

不是靠用户手动说明，而是三路同时铺：

1. **工作区 cwd**：`DSH_WORKSPACE` 指向带 `GITHUP.md` / `README.md` 的目录，
   AI 用 bash/file 工具一 `ls` 就看见
2. **`/gh/help`**：AI 只要 curl 一次就拿到完整端点表、示例和「别检查环境」的提示
3. **已选仓库镜像**：`materialize()` 写同一份文案（统一由
   `GhBridgeDocs.repoText()` 生成，不再两份各写各的）

不注册新 DSH 工具插件，是为了避开用户层 `node_modules` 对
`@deepseek-ai/dsh-tools` 的解析不确定性 —— 纯 App 侧文件 + env，零依赖。

### 安全边界（变化要如实说）

- ✅ **令牌仍不出设备**：不进 DSH 的 env / 文件 / 工作区，只在 App 进程内附加到
  `api.github.com` 请求
- ✅ 域白名单与 apiPath 校验保留；`zipball/tarball/archive/redirect` 类端点
  挡在代理外（底座 `Http` 会自动跟随重定向并转发 Authorization，故改由
  `openWithOfficialRedirect()` 的官方域白名单单独处理）
- ⚠️ **AI 现在能改仓库了**：建 Issue、提交、开 PR、改设置……token 有
  `delete_repo` 就能删库。这是用户明确要的「权限等价」，代价是提示词注入
  或模型误判可能真的产生写操作
- ⚠️ 开关打开期间（现在是「只要登录着」），本机任意能连 127.0.0.1:3081 的
  进程都能经该代理以你的令牌访问 GitHub。回环 + Host 围栏挡外部网络，
  不隔离同机 App
- 未真机验证写方法链路与 AI 是否真的会主动读说明；已验证 Java 结构、
  JS 语法、Manifest、包身份、签名与防护链自洽


---

## 二十、阶段 Q：开关回归（管悬浮球）+ 全屏白屏/缩放卡死修复

### 触发

用户截图两处：

1. 设置页「AI 辅助 githup」变成了带 `>` 的状态行（阶段 O 把开关取消了）。
   用户明确：**「这个开关是决定是否出现可移动的这个 AI 悬浮窗的」**。
2. 第一张图：整屏白、顶部 `githup AI` 与系统状态栏时间/图标叠成一团；
   **「用户把这个界面放大就缩不小了」**。

### 开关语义澄清（两轮口径合起来）

| 轮次 | 用户口径 | 落地 |
|---|---|---|
| 阶段 O | 「登录令牌有什么权限，AI 对 github 就有什么权限，**而不是开关设置的**」 | GitHub 权限只看 `SecurePrefs.gh_token`，与开关无关；全方法放行 |
| 阶段 Q | 「这个开关是决定是否出现可移动的 AI 悬浮窗」 | 开关 = 右下角可拖动 AI 球显隐 |

之前我把两句话理解成「取消开关」，错了。正确做法是**保留开关、换掉它管的东西**：
- `KEY_BALL = "ai_ball_on"`，**默认 true**（用户一直有球，升级后不该突然消失）
- `GhBridge.ballOn()` / `setBallOn()` / `switchOn()` / `setEnabled()` → 都指向球
- `GhBridge.enabled()` 仍是 `!token().isEmpty()` → `/gh/*` 代理不随球开关变化
- `status()` → 「悬浮球已开启/已隐藏 · 已登录 ghp_… · 权限跟随此令牌」
- `JsBridge.setAiGithubEnabled()` 切完**立即**调 `MainActivity.applyAiBall()`，
  不用等下次进设置页；`requestAiGithubEnable()` 的授权确认弹窗降为 no-op
  （开关不再是「把令牌授权给第三方」的动作）
- `AiPanel.setBubbleEnabled()`：关时若面板展开就收起；`collapse()` 尊重该状态

### 第一张图的两个成因

**① 全屏时品牌条被状态栏压住**

`fullBtn(⛶)` 把面板高度设成 `MATCH_PARENT` + `Gravity.BOTTOM`；App 是
edge-to-edge（`LAYOUT_FULLSCREEN`），所以面板顶部到 y=0，38dp 的品牌条正好
落在状态栏那一条上 → `1:08` 和 `githup AI` 叠成 `1:1gitQ…AI`。

修：`applyBarInsets()` 在切全屏时给 bar 加 `statusBarHeight()` 的 top padding，
并把 bar 高度改成 `dp(38) + statusBarHeight()`。收起全屏时还原为 0。
状态栏高度走 `android:dimen/status_bar_height`，取不到兜底 `dp(24)`。

**② 捏合放大后缩不回来 → 整屏白**

`configure()` 原来是 `setBuiltInZoomControls(true)` + `setDisplayZoomControls(false)`：
允许双指放大，但**不显示系统缩放控件**，而 DSH 是 SPA、视口被固定，放大后
没有可用的复位路径，内容被推出可视区就只剩白屏。

修：
- `setSupportZoom(false)` / `setBuiltInZoomControls(false)` / `setDisplayZoomControls(false)`
  从**原生层**禁掉手势缩放（WebSettings 优先级高于页面 viewport meta），
  并 `setInitialScale(100)`；`HarnessActivity`（旧整屏入口）同样处理
- 品牌条新增 **`⤾` 复位视图** 按钮：`web.setInitialScale(100) + web.reload()`，
  给旧状态一个明确的一键恢复入口
- 切全屏时也顺手 `setInitialScale(100)`，不带着放大状态过去

底座 `WebViewActivity`（看仓库文件/外部网页）保留缩放，那是另一类页面，
且有可见控件，不受影响。

### 改动文件

| 文件 | 内容 |
|---|---|
| `harness/GhBridge.java` | `KEY_BALL` / `ballOn` / `setBallOn`；`enabled()` 只看 token；`status()` 改为球+登录态 |
| `JsBridge.java` | 开关接口指向球 + 即时通知；授权弹窗 no-op |
| `MainActivity.java` | `applyAiBall()`；attach 后按 pref 初始化 |
| `AiPanel.java` | `ballEnabled` / `setBubbleEnabled()` / `collapse()` 尊重开关；`applyBarInsets()` / `statusBarHeight()` / `resetView()` / `⤾` 按钮；禁缩放 |
| `HarnessActivity.java` | 旧整屏入口同样禁缩放 + initialScale |
| `page-user.js` / `i18n-strings.js` | 恢复 `switchItem`，点击切换球显隐并即时刷新 |

### 边界

- 球隐藏后，从系统分享 / `open_ai` 显式拉起仍可展开面板（显式意图优先）
- 未真机验证状态栏高度在挖孔屏 / 分屏下的取值；已验证 Java 结构、JS 语法、
  Manifest、包身份、签名与防护链自洽


---

## 二十一、阶段 R：默认关悬浮球、白屏/放大修复、GitHub 能力可自检

> 第二十章（阶段 Q）已把开关从「授权总闸」改回「悬浮球显隐」并修了缩放，
> 但**默认仍是开**，且那一版没有进用户手上的安装包。本章按真机截图收口。

### 用户这轮的四句话

1. 「AI 辅助 githup 为什么没有开关了呢，**这个开关是决定是否出现可移动的 AI 悬浮窗的**」
2. 「第一张图片的大小不合适，用户把这个界面放大就缩不小了，**默认是关闭**」
3. 「真的 DSH 可以查看得到 github 里面的东西吗，和改写用户的库」
4. 「但还是**用户点击软件之后自动启动完整引擎**」

### ① 开关归位 + 默认关闭

| 概念 | 由什么决定 |
|---|---|
| 可移动 AI 悬浮球显隐 | **设置页开关**，默认**关闭** |
| GitHub 读/写权限 | **当前登录令牌**（阶段 O 口径，不变） |
| 完整引擎是否启动 | **点开 App 就无条件启动**，与上面两者都无关 |

- `GhBridge.KEY_BALL` 换成 `ai_ball_v2`，`ballOn()` 默认 `false`。
  换新 key 是为了让「默认关」对覆盖安装也生效（旧 key 曾被默认开写过）
- `JsBridge.aiGithubEnabled()` 异常兜底由 `true` 改 `false`；
  切完立即 `MainActivity.applyAiBall()` 生效
- 设置页恢复 `switchItem`，副标题由 `status()` 给出：
  「悬浮球已隐藏（默认） · 已登录 Buwrt · ghp_4w… · 权限跟随此令牌」
- `AiPanel.ballEnabled` 字段默认 `false`

### ② 第一张图：整屏白 + 顶部文字糊成一排 + 放大缩不小

| 现象 | 修法 |
|---|---|
| 放大后缩不回去 | `configure()` 禁三档缩放；品牌条把 ⤾ 换成醒目的「**复位**」文字按钮；`expand()` 每次自动 `setInitialScale(100)`；全屏切换也顺手复位 |
| 顶部一排糊字 | `panelLp()` 全屏时把高度扣掉 `statusBarHeight()`、底部对齐，整个面板从状态栏下方开始；`applyBarInsets()` 不再给 bar 叠 padding，避免双重留白 |
| 整屏白 | 新增 `WebViewClient.onReceivedError / onReceivedHttpError` → `showPageError()`：亮出装载卡、写明原因、给「重新加载 AI 界面」按钮 |

球默认隐藏后用户本来不会误进这个界面；但引擎与 WebView 仍照常准备，
在设置里打开开关后，点开就是已就绪的 AI 面板。

### ③ 完整引擎无条件启动（硬约束）

`AiPanel` 构造里 `ui.postDelayed(startEngine, 700)` 在**球显隐判定之前**；
`showWeb()` 无论面板是否收起都会创建 WebView、加载 DSH 首页、
调 `EngineService.keepAlive()`。

> 关球 ≠ 关引擎、≠ 关 GitHub 权限。注释已写进代码，防止后续再被误删。

### ④ 「DSH 真的能读到 / 改到我的库吗」—— 给可验证的答案

**a. 设置页新增「GitHub 连通自检」**（`GhBridge.selfTest()` + `/gh/selftest`）
真打一次 `GET /user`、`GET /user/repos`，返回登录用户、可见仓库数、私有库数、
可写仓库数、`X-OAuth-Scopes`、`can_read / can_write / can_delete`。
**自检只发 GET，不改任何仓库**。

**b. AI 侧自动感知再加固**：`GhBridgeDocs.install()` 除 `GITHUP.md` /
`README.md` 外，再写 `AGENTS.md` / `CLAUDE.md` 到工作区根与 `$DSH_HOME`；
选中仓库的镜像目录同样写这四份。`DSH_WORKSPACE` 指向工作区。
`/gh/help` 加入 `selftest` 端点说明。

**实测（2026-11-11，用当前登录令牌，只读）**：

- 登录用户 `Buwrt`
- `X-OAuth-Scopes`：`repo, delete_repo, workflow, admin:org, admin:repo_hook,
  admin:public_key, admin:ssh_signing_key, gist, notifications, project, user,
  codespace, copilot, audit_log, write:discussion, write:packages, …`
- 可见仓库 **19 个**，其中**私有 2 个**
- `Buwrt/2` 的 `permissions`：`admin / maintain / push / triage / pull` 全 true

结论：**能读（含私有库），能改（push/admin），甚至能删库（delete_repo）**。
DSH 经 `127.0.0.1:3081/gh/api/...` 拿到的就是同一份能力，令牌不出设备。

### 风险（如实）

- 这个 token **权限过大**（含 `delete_repo` / `admin:org`），且明文出现在对话里。
  强烈建议撤销并换 fine-grained、只授所需仓库的最小权限
- AI 是否「主动」调代理仍取决于它读不读工作区说明；已多铺几路，
  但未真机验证模型行为
- 写操作链路未真机验证；已验证 Java 结构、JS 语法、Manifest、包身份、
  签名与防护链自洽


---

## 二十二、阶段 S：令牌严格跟随「当前登录」，去掉 saved 回落

### 用户口径

> 「是这个软件登录什么令牌，AI 就读其他的令牌，而不是固定
> `ghp_4w…` 这个令牌。」

即：软件里**当前登录哪一份令牌，AI 就用哪一份**；换号跟着换，退出就失效，
包里不能写死任何一份凭据。

### 核查结论：没有写死，但有一个真实错位

**先排除写死**：整个 `app/src/main` 里 `ghp_4wL…` 全文 **0 处**；
`HarnessBridge` 起 node 的 env 里**没有** `GITHUB_TOKEN` / `GH_TOKEN`，
也没有把令牌写进任何文件；`GhBridgeDocs` 写进工作区的只是说明文字。
`LocalBridgeServer.gh()` 每次请求 → `GhBridge.proxy()` → `headers(c)` →
`token(c)` → `SecurePrefs.get(c, "gh_token")`，**全链路现读、无缓存、无副本**。

截图里设置页显示「已登录 · ghp_4w…」，是**用户自己把这个令牌登录进了软件**，
不是包里内置的；`/gh/selftest` 实测到的 `Buwrt` 也是同一份登录态的结果。

**但发现一个违反口径的坑**：`GhBridge.token()` 原来是

```java
String t = SecurePrefs.get(c, "gh_token", "");
if (t.isEmpty()) t = SecurePrefs.get(c, "gh_token_saved", "");   // ← 问题
```

`gh_token_saved` 的语义是「**退出登录也不删**、给登录页一键回填用的记住令牌」
（见 `JsBridge.TOKEN_SAVED_KEY` 的注释）。底座自己 `getToken()`、`Http.java`、
通知轮询**都只读 `gh_token`**。于是：

| 用户动作 | 底座 | 修复前 AI |
|---|---|---|
| 登录 A | A | A ✅ |
| 切换账号到 B | B | B ✅（`setToken`/`switchAccount` 都写 `gh_token`） |
| **退出登录** | 未登录 | ❌ **仍拿记住的旧令牌继续读写 GitHub（含私有库）** |

退出登录时 `remove(gh_token)` 但 `gh_token_saved` 还在 → AI 绕过登录态。
这正是「不是软件登录什么就用什么」。

### 修法

```java
public static String token(Context c) {
    String t = SecurePrefs.get(c, "gh_token", "");   // 只读当前生效
    return t == null ? "" : t.trim();
}
```

与底座完全对齐。于是：

- 登录 PAT / OAuth access_token（都写 `gh_token`）→ AI 立刻用那份
- 切换账号 / 换令牌 → AI 立刻跟着换，**无需重启引擎**（每次请求现读）
- 退出登录 → `gh_token` 被清 → `/gh/*` 立刻返回「请先登录」
- 「记住的令牌」只留在登录页回填，**不参与任何授权**

### 让用户能自己验证

- `/gh/selftest` 增加 `token_head`（当前生效令牌**前 6 位**，脱敏）
- 设置页「GitHub 连通自检」弹层第一行显示「当前令牌：`ghp_4w…`（仅显示前 6 位）」
  → 换号后点一下，前缀变了就证明 AI 跟着换了；退出登录后点，会直接提示未登录
- `/gh/help` 的 `scope` 写明「换账号或退出登录后即时变化，没有任何固定令牌」
- 工作区 `AGENTS.md` / `CLAUDE.md` / `GITHUP.md` / `README.md` 把原来
  「你**已经拥有** GitHub 访问权限」改成**条件式**：「只要当前登录着令牌…
  换账号立刻跟着换，退出登录立刻失效」，并补「记住的令牌不参与授权」
- `proxy()` 里残留的旧三元错误文案（「请打开开关（会弹确认框）」）清掉，
  统一成「请先登录；AI 使用的是当前登录的那一份」

### 边界

- 仍是「令牌不出设备」：不进 DSH 的 env / 文件 / 工作区，只在 App 进程内
  附加到 `api.github.com` 请求头
- 未真机验证换号即时生效链路；已验证全链路无硬编码、无缓存副本、
  Java 结构、JS 语法、Manifest、防护链自洽


---

## 二十三、阶段 T：点「登录」白屏 —— 对齐上游 WebView 落地逻辑

### 真机现象

用户截图：AI 面板顶部品牌条正常（`githup AI / DeepSeek Harness / 复位 / ⛶`），
**下面整屏白**。左侧灰色竖条 + 喇叭 + 三点，是**系统音量面板**（用户按了音量键），
不是 App 内容。「点击登录之后」= 在 DSH Web UI 的账号登录里点登录。

### 前端源码：登录是「先预开空白标签页，再导航」

`dshroot/.../dsh-client-ui-settings-account/lib/client.js`：

```js
begin() {
  this.dispose();
  this.popup = window.open("about:blank", "_blank");   // 预占标签页
  if (this.popup) this.popup.opener = null;
}
update(view) {
  ...
  this.navigated = true;
  if (this.popup && !this.popup.closed)
    this.popup.location.replace(this.destination(attempt.authorizeUrl));
}
```

注释也写清楚了：`/** Preopen a tab during the user's click; blocked popups need the copy-link route. */`

### 我们缺的两件事（上游都有，只看源码）

上游 `harness/android-app/.../MainActivity.java` 的注释直接点名这个坑：

> 账户登录对话框只提供「复制授权链接」（自动拉起浏览器由桌面壳 dshDesktop 负责）。
> Web 壳没有 dshDesktop……WebView 默认没有 shouldOverrideUrlLoading 时，
> 点链接会「原地加载」—— 整个 GUI 被平台登录页顶掉。

| 上游 | 我们（修复前） |
|---|---|
| `ws.setSupportMultipleWindows(true)` + `WebChromeClient.onCreateWindow`：造 `probe` WebView 接住 `window.open`，`routeOnce()` **忽略 `about:blank` 预占页**，等真正的授权地址再落地 | `WebChromeClient` 是空实现，`setSupportMultipleWindows` 默认 false → `window.open` 拿不到 popup，或空白页把主 WebView 顶成白屏 |
| Activity 级 `openExternally(Uri)`：非回环链接外发系统浏览器，用 **`Intent.createChooser(..., "选择浏览器完成登录")`** | `shouldOverrideUrlLoading` 恒 `return false` → 平台登录页在 WebView 里原地加载 / 白屏 |
| `routeNewWindow()`：弹窗 URL 回环 → 主 WebView；其余 → 外发；`file://`/`.apk` → 下载 | 无 |
| 子框架 `resourceAllowed()`：`blob:` / `data:` 预览放行，只拦本地特权协议 | 无 |
| `DownloadListener` + `onConsoleMessage` + `onShowFileChooser` | 无 |

**为什么必须用 chooser**（上游 v5 注释）：账户登录回调是
`http://127.0.0.1:<port>/oauth/callback`，由引擎本地 HTTP 服务接收；
UC / 夸克 / QQ浏览器会把 `127.0.0.1` 当危险地址拦掉或当搜索词 → 引擎收不到 code →
登录永远卡「等待中」。Chrome / 系统浏览器能正常跳回环。

### 本轮改动（全部在 `AiPanel.java`）

1. `setSupportMultipleWindows(true)`；`WebChromeClient.onCreateWindow` 照上游 v1.39.6
   的 probe 写法，`about:blank` 不算落地，真实授权地址来了才 `probe.destroy()`
   + `routeNewWindow()`
2. `shouldOverrideMain()`：主框架 `about:` / `data:` / `blob:` / `javascript:`
   **一律拦**（防白屏）；回环交回 WebView；其余 `openExternally()`
3. `openExternally()`：`ACTION_VIEW` + `createChooser("选择浏览器完成登录")`，
   `mqqapi` 直接交 QQ；Toast 提示「完成后切回本 App」
4. `routeNewWindow()`：回环 → 主 WebView；`file://`/`.apk` → 下载；其余 → 浏览器
5. `resourceAllowed()` 子框架放行 `blob:` / `data:`（文档预览），只拦
   `file://` / `content://` / `javascript:`
6. `DownloadListener`：`http(s)` 走 DownloadManager（通知栏可点），本地 `file://`
   经 `LogShareProvider.uriForAbs()` 转 `content://` 再 `ACTION_VIEW`（避 FileUriExposedException）
7. `onConsoleMessage` 把 JS ERROR/WARNING 转进 logcat
8. **白屏自救**：`onPageStarted` / `onPageFinished` 若主文档竟是 `about:blank`，
   立刻拉回引擎根路径；「复位」按钮由原来的 `reload()`（白屏页 reload 还是白）
   改成 `loadUrl(引擎根路径)` 并收回错误装载卡

### 用户侧操作（改完之后）

1. AI 面板 → 设置 → 账号与余额 / Models → 点登录
2. 弹出 **「选择浏览器完成登录」** → 选 **Chrome 或系统浏览器**
   （不要用 UC / 夸克 / QQ浏览器，它们会拦回环回调）
3. 浏览器里完成 DeepSeek 账号授权；回调 `127.0.0.1:<port>/oauth/callback`
   由引擎接收，浏览器显示什么不用管
4. **切回本 App**，前端 RPC 会把「等待中」收敛成已登录
5. 若仍卡住或又白屏 → 点品牌条「**复位**」直接回 AI 首页

（没有 `sk-` API Key 也可以：赠金余额走账号登录；阶段 N 已把新建会话默认路由
改成账号已登录时用 `deepseek-account`。）

### 仍未对齐上游的部分（如实）

- `onShowFileChooser`：聊天里「添加附件」的系统文件选择器，需要 Activity
  转发 `onActivityResult`，本轮未接（与白屏无关）
- 上游 `FALLBACK_URL = file:///android_asset/fallback.html` 静态兜底页 +
  健康探测自动切回、401 重捞令牌 `authReloadTries`：本版用
  `showPageError()` + 「重新加载 / 复位」替代，没有自动探测轮询
- `guardResource()` 的 `shouldInterceptRequest` 403 资源围栏：本版只在
  导航层判据，未做逐请求拦截（安全面略宽）
- 虚拟屏 / Shizuku 特权仍不属于本层

### 验证状态

未真机跑完整 OAuth 授权链路；已验证前端 `BrowserLogin` 源码与上游
`MainActivity` 补丁逐条对应、Java 结构、Manifest、包身份、签名与防护链自洽。


---

## 二十四、阶段 U：为什么点登录「不会跳转到浏览器」（上游能快速跳）

### 用户口径

> 「我的软件为什么不会跳转到浏览器？上游的 DSH 可以快速的跳转。」

上一轮（阶段 T）已把上游 `MainActivity` 的 `setSupportMultipleWindows(true)` +
`WebChromeClient.onCreateWindow` probe + `openExternally()` chooser 逐条搬进
`AiPanel`，但真机点了登录仍然**没有跳浏览器** —— 只停在
「等待登录 / 没有自动打开新页面？复制登录链接」。

### 根因：前端登录弹窗有两条路，我们只接住了其中一条

前端 `dsh-client-ui-settings-account/lib/client.js` 的 `BrowserLogin`：

```js
begin(){ this.popup = window.open("about:blank", "_blank"); }   // 预占标签页
update(){ ... this.popup.location.replace(destination(authorizeUrl)); }
```

注释写得很直白：`/** Preopen a tab during the user's click; blocked popups need the copy-link route. */`
文案里也确实有 `browserPrompt: "没有自动打开新页面？"` + `copyLink: "复制登录链接"`。

**`window.open` 返回 null → popup 不存在 → 前端直接退到「复制链接」路线，
根本不发起任何跳转。**

上游壳为什么能跳？它有**两条**外发通道：

| 通道 | 触发 | 上游实现 | 我们（阶段 T 后） |
|---|---|---|---|
| `ctlAct("open.url", url)` | console / 设置页链接 | `startActivity(ACTION_VIEW)`，**直接启动，最快** | `ShellBridge` 已实现，也是直接启动 ✅ |
| `window.open` / `<a target=_blank>` | 账号登录弹窗 | `onCreateWindow` probe 接住 → `routeNewWindow` → `openExternally()` chooser | 已搬，但**真机没触发** ❌ |

`onCreateWindow` 方案在部分 WebView 内核上不可靠：即使设了
`setSupportMultipleWindows(true)`，`window.open("about:blank")` 仍可能返回 null；
probe 是未 attach 的 WebView，`location.replace` 的导航回调也可能被吞；
而且 `probe.destroy()` 写在 probe 自己的回调里，会进一步放大这个差异。
上游 v1.39.6 的注释本身就记录了「2026-10-18 账号登录复现」——说明 upstream
自己也被这条链坑过。

**结论：不能只依赖原生 WebView 的多窗口回调。要在页面层把跳转抓死。**

### 本轮改动（全部在 `AiPanel.java`）

**① 注入 `EXTERNAL_BRIDGE_JS`（onPageStarted + onPageFinished 双注入，幂等）**

覆盖 `window.open`：

- `about:blank` / 空 → 返回一个**假 popup**（`closed:false` / `opener` / `close()` /
  `location.replace()`），让 `BrowserLogin` 认为 popup 存在 → 不会退到「复制链接」；
  假 popup 的 `location.replace(authorizeUrl)` **直接调 `githupExternal.open()`**
- 真实 `http(s)` → 立即 `githupExternal.open()`
- `blob:` / `data:` / `file:` / 回环 → 交回 WebView 原生（不破坏 PPT presenter、
  文档预览、DSH 自身页面）

两条兜底：

- `navigator.clipboard.writeText()`：即便用户点了「复制登录链接」，只要内容是
  http(s) 且含 `deepseek/oauth/authorize/login/signin`，**复制的同时也自动跳浏览器**
- `document` 捕获阶段监听 `<a target="_blank">`：非回环 http(s) 直接外发，
  不等 `onCreateWindow`（跳过 `download` 属性和 `.apk` 链接，交给 DownloadListener）

**② 新增原生桥 `githupExternal.open(url)` → `openExternalFast()`**

对齐上游 `ctlAction("open.url")`：**直接 `startActivity(ACTION_VIEW)`，不用选择器**，
所以点登录是「立刻跳」，而不是先弹一层 chooser。直发失败才回落
`openExternally()` 的选择器。回环地址仍交回主 WebView。

**③ 补齐 WebView 差异面**

- `setJavaScriptCanOpenWindowsAutomatically(true)`：连非手势 `window.open` 也允许，
  避免内核静默吞掉
- `onCreateWindow` 里 `probe.destroy()` 从「回调内自毁」改成 `ui.post` 延迟一帧，
  保证 `routeNewWindow()` 一定执行完（原生通道继续作为第二条保险）

### 现在用户怎么操作

1. 覆盖安装 → 完全退出 App 再重进
2. AI 面板 → 设置 → 账号与余额 / Models → 点登录
3. **立刻跳系统浏览器**（DeepSeek 授权页），完成授权
4. 回调 `127.0.0.1:<port>/oauth/callback` 由引擎接收 → **切回本 App**，
   「等待登录」自动收敛成已登录
5. 若浏览器停在白页 / 一直「等待中」：多半是默认浏览器（UC / 夸克 / QQ浏览器）
   拦了回环回调 → 去系统设置把 **Chrome / 系统浏览器**设为默认，再点「重试登录」；
   或点用户可见的「复制登录链接」，本版也会自动外发

### 与上游仍有的差异（如实）

- 上游 `openExternally()` 用 `Intent.createChooser("选择浏览器完成登录")`，
  让用户主动避开会拦回环的浏览器；本轮为「快速」改成直发，
  原生 chooser 只作为直发失败和 `<a>`/主框架路由的兜底
- 上游 `fallback.html` 静态兜底页 + 自动健康探测、`onShowFileChooser`、
  `shouldInterceptRequest` 逐请求围栏仍不在本版
- 未真机跑完整 OAuth 授权链路；已验证注入 JS 语法、Java 结构、Manifest、
  包身份、签名与防护链自洽


---

## 二十五、阶段 V：零系统权限感知页面 + 用户同意后建立同名库

### 用户口径

> 「不给我的软件任何权限他都能知道我的 githup 软件在哪个页面，并且读的是哪个库，
> 也能给我总结，这个库也能修改，修改之后上传到自己的账号，会建立相同名称的库；
> 如果用户打开这个按钮就会提出弹窗：有可能会将 token 给予第三方软件或 DeepSeek，
> 同意或拒绝，此操作与作者无关，同意就会打开此软件。」

### 为什么「零权限」是可行的（不是读屏）

之前 AI 回答「我看不到实时画面，但我跑在 githup 自己的用户身份下，能直接读到它
刚写下的界面状态」—— 那段是**编造**：AI 既没有无障碍也没有截图，它把「能调
GitHub API 读仓库」和「能看到 App 屏幕」混为一谈，还编出了设置页密度/字号/主题/
路由序列等具体数值。

但这件事**本来就可以真做到**，只是渠道不同：

githup 自己就是 WebView SPA，`Router.render()` 每次渲染都算出了
`parseHash(location.hash)` → `{name, ctx:{owner, repo, tab, ref, path, ...}}`。
「当前在哪个页面、看哪个库」是 **App 自己进程内写下的状态**，不是屏幕像素、
不是控件树。把它上报到原生层、再由 3081 回环桥交给 DSH，
**不需要无障碍 / 悬浮窗 / 截屏 / 存储等任何 Android 运行时权限**。

于是 AI 的回答应该改成：「我能通过 `/app/context` 准确知道当前页面与仓库，
没有屏幕像素和控件树，也不能帮你点按；禁止编造界面数值。」

### 改动清单

**① 新增 `AppContext.java`**（`com.hubmobile.app.harness`）

- `report(Context, hash, title, routeJson)`：把路由 / 仓库 / tab / path / ref /
  ts 写进 App 私有 `SharedPreferences("ai_repo_ctx")`；前端没给结构化 ctx 时
  从 hash 兜底认 `owner/repo`（一级路由保留字表排除误认）
- `consent()/setConsent()`：用户同意闸门，**默认 false**
- `contextJson()`：`GET /app/context` 的响应。含
  `consent` / `zero_permission:true` / `screen_pixels_available:false` /
  `accessibility_tree_available:false`；
  未同意时 `page:null` 并附「请如实告知、禁止编造」的 hint
- `cloneRepo()`：`POST /app/clone-repo`。校验 consent → `GET /user` 拿当前
  login → `POST /user/repos` 建**同名**库（同名已存在 422 时不覆盖、直接写文件）
  → 逐文件 `PUT /repos/{login}/{name}/contents/{path}`（已存在则补 sha 再写）。
  全程复用 `GhBridge.proxy()`，**权限跟随当前登录令牌**，只写自己账号下的库，
  **不改原仓库**；单文件 ≤900KB、单次 ≤50 文件、路径过滤 `..`

**② 底座前端**（`assets/web/js/app.js`）

`Router.render()` 里 `App.setTab(...)` 之后加一行上报：

```js
if (window.NativeBridge && window.NativeBridge.reportPage)
  window.NativeBridge.reportPage(hash, String(title||''), JSON.stringify(r||{}));
```

不动 SPA 业务逻辑，只把已经算好的状态交出去。

**③ `JsBridge.java` 新接口**：`reportPage` / `aiRepoConsent` /
`setAiRepoConsent` / `aiRepoStatus` / `openAiPanel`

**④ 设置页**（`page-user.js`）新增开关「**AI 感知与克隆**」，默认关

- 打开 → 自定义 `UI.sheet` 弹窗，按钮精确为「**拒绝**」「**同意**」，
  正文含用户原文要点：零系统权限感知页面与仓库、可总结/修改、
  同名库上传到当前登录账号、**有可能将 token 给予第三方软件或 DeepSeek**、
  **此操作与作者无关**
- 同意 → 写 consent、`openAiPanel()`（**「同意就会打开此软件」**：展开 AI 面板
  并让悬浮球入口常驻）、刷新设置页
- 拒绝 / 点遮罩 → 什么都不做，开关保持关
- 关闭时不弹窗

**⑤ `MainActivity.openAiPanel()`**：主线程展开面板 + 打开球入口。
这是用户主动同意后的行为，不违背悬浮球「初始默认关」。

**⑥ 3081 本地桥**（`LocalBridgeServer.java`）

- 免密白名单与 `dispatch` 增加 `/app/`
- `appRoute()`：`GET /app/context` → `AppContext.contextJson()`；
  `POST /app/clone-repo` → `AppContext.cloneRepo()`
- `/gh/help` 增加 `zero_permission_page` 与 `clone` 两条自描述

**⑦ 工作区说明**（`GhBridgeDocs.text()`，写入 `AGENTS.md` / `CLAUDE.md` /
`GITHUP.md` / `README.md`）

新增三节，专门纠正上一轮幻觉：

- 「你能知道用户现在在哪个页面、看哪个库 —— 而且不需要任何系统权限」：
  先 `curl /app/context`，如实说明渠道，**不是**截图、**不是**控件树
- 「严格禁止编造」：没调 `/app/context` 不许声称知道页面；`consent:false` 时
  禁止编造设置页/主题/字号/密度/浏览历史/路由序列；没有像素与点按能力
- 「总结当前库 / 修改后建立同名库」：完整 curl 流程，且必须已点同意

**⑧ 阶段 W 顺带修**：`AiPanel.expand()` 展开动画前给 WebView 挂硬件层，
布局落定后（`panel.post`）再复位 `setInitialScale(100)` 并摘掉硬件层 ——
修「点 AI 放大没反应 / 有延迟」（上一轮承诺但只做了诊断，这轮落地）。

### 安全与边界（如实）

- **零权限**指 Android 运行时权限；`INTERNET` 是安装时声明的普通权限，
  「把代码发给 DeepSeek 总结 / 修改」必然联网，内容会离开设备 —— 这正是弹窗
  要告知的风险，不是能绕过的
- 令牌**仍锁在 Keystore**，不进 DSH 的 env / 文件 / 工作区；`/app/clone-repo`
  与 `/gh/api/*` 都只是经 App 进程把令牌附加到 `api.github.com`，
  弹窗文案按用户要求保守写成「有可能将 token 给予第三方软件或 DeepSeek」
- 未同意时 `/app/context` 返回 `page:null`，AI 拿不到页面也不会建库；
  但 `/gh/api/*` 仍跟随登录令牌（这是用户上一轮定的「权限跟随令牌」口径，
  本次未收窄）
- 未真机跑完整 OAuth + 克隆写链路；已验证 Java 结构、JS 语法、Manifest、
  包身份、签名与防护链自洽


---

## 二十六、阶段 X：打开仓库 AI 不辅助、还特别费 token

### 真机现象

用户截图：正停在 `gbataille/gitHUD` 代码页（.github/app/bench/docs/images 目录、
76 star、17 fork、HTML、master、已归档），AI 悬浮球在右下角。
反馈：「AI 不会辅助我的软件，用户已经打开了一个仓库但是他不会分析，还特别费 token」。

### 根因（三条叠加）

**① AI 是被动的，我们只「给了端点」没「喂事实」**

上一轮做了 `GET /app/context` + 工作区说明，但 LLM 不会自己主动去 curl。
用户不问，它不知道用户在看哪个库；一问，它要么答「我看不到」，
要么开始跑 `gh` CLI / 检查环境变量 / `ls -R` / 逐个读文件去**反推**当前仓库。
那几轮无用工具调用，就是「没干活却特别费 token」的直接来源。

**② 面板没有任何页面联动**

`AppContext.report()` 只把路由写进 SharedPreferences，`AiPanel` 完全不知道
底座换页了。所以用户逛到新库，AI 面板里什么都不会发生。

**③ 工作区说明重复 7 份**

`GhBridgeDocs.install()` 在 workspaceRoot 写 4 份、在 dshHome 写 3 份
**同一份 ~2.5KB 文档**；`GhBridge.materialize()` 展开镜像时又写 4 份。
DSH agent 可能把它们重复读进上下文 —— 又是一笔固定开销。

**还有一个用户侧高概率原因**：「AI 辅助 githup」是**悬浮球显隐**开关，
「AI 感知与克隆」才是上一轮用户自己要求的 consent 闸门。两个开关不同；
球显示着 ≠ consent 已同意。没同意时 `/app/context` 返回 `page:null`，
AI 确实拿不到页面 —— 但界面上没有任何提示，看起来就是「不辅助」。

### 改动（核心思路：把「让 AI 自己找」改成「App 先查好直接喂」）

**① 新增 `RepoBrief.java` —— 原生侧预抓，零 LLM token**

换仓库时用当前登录令牌打 **GitHub REST**（`GET /repos/{repo}` +
`GET /repos/{repo}/readme`），压成 ≤500 字的精简事实：
仓库名 / 简介 / 主语言 / star / fork / 默认分支 / 归档 / 许可证 / README 前 1200 字。
缓存 10 分钟、只在 repo 变化时抓。
**全程不调 DeepSeek**，只是 App 本来就会做的 GitHub GET。

**② 页面联动**

`JsBridge.reportPage()` 去重（同页重渲染不重复）→
`MainActivity.onPageChanged(repo)` → `AiPanel.onRepoChanged(repo)`。
冷启动 `expand()` 里也补一次同步，卡片不空着。

**③ AI 面板顶部新增「当前仓库卡」（原生渲染，零 token）**

`当前：gbataille/gitHUD · HTML · ★76 · master · 已归档`

右侧按钮随 consent 变化：
- 未同意 → 「**开启感知**」，点击弹与设置页同一份风险文案的原生确认框
  （拒绝 / 同意、有可能将 token 给予第三方软件或 DeepSeek、此操作与作者无关）；
  同意后刷新卡片，**不自动发 LLM**
- 已同意 → 「**带入对话**」

**④ 「带入对话」= 把精简事实预填进 DSH 输入框（省 token 的关键）**

`injectBrief()` 用 React 兼容的 prototype native setter + `input`/`change` 事件
把 brief 写进 composer，并挂 `window.__GITHUP_NOW__`；**不自动发送**。
模型不用再跑 N 轮探测，用户按一下发送，一轮就能答。
找不到输入框时退化成复制到剪贴板 + Toast，不静默失败。

**⑤ `/app/context` 附带 `brief`**（`AppContext.contextJson()`）

缓存命中时直接返回已查好的事实，AI 真要 curl 也一次拿全，不用翻文件。

**⑥ 工作区说明瘦身 + 省 token 铁律置顶**

- `text()` 从 ~2.5KB 压到 ~1.4KB，删掉重复的 curl 示例与权限解释
- 置顶新增「**0. 省 token 铁律**」：当前仓库是已知事实直接采用；
  **禁止**为确认页面跑 `gh` CLI / 查环境变量 / `ls -R` 全库 / 逐个读文件 /
  反复试探；需要细节只精确取单个文件；AGENTS.md 读一份即可
- `install()` 从 7 份全量降到 **3 份全量（workspaceRoot 的 AGENTS.md、
  dshHome 的 AGENTS.md、加镜像里 1 份）+ 其余一行指针**；
  `materialize()` 镜像也从 4 份降到 1 全量 + 3 指针

**⑦ 严格尊重 consent 闸门**

未同意时 `AppContext.report()` 不预抓 GitHub 元数据、`AiPanel` 不联网、
卡片只显示仓库名 + 「点右侧开启感知」引导；同意后才抓。
不绕过用户上一轮定的弹窗，但再也不会「点了没反应还不知道为什么」。

### 省 token 的边界（如实）

能省的：**无用工具轮次**（N 轮探测 → 1 轮）、**重复上下文**（7 份 → 3 份、
2.5KB → 1.4KB）、**卡片/速览本身零 LLM 调用**（走 GitHub REST）。
改不了的：DSH 引擎每次会话自带的系统提示与全量工具描述，在 160MB
payload 的内核里，本层无法裁剪。所以是「大幅减少浪费」，不是「零消耗」。

### 验证状态

未真机跑完整 composer 预填链路（DSH 的 composer 可能是 contenteditable，
已加多选择器 + native setter + 剪贴板退化）；已验证 Java 结构、JS 语法、
Manifest、包身份、签名与防护链自洽。


---

## 二十七、阶段 X+：从「一键带入」到「每轮自动辅助」，并再压一次 token

> 第二十六章已经解决了「App 不认识当前仓库」（RepoBrief 卡片 + 带入对话 +
> 工作区说明去重）。本轮补的是用户口径里「**真正辅助**」的最后一段：
> 连「点一下带入对话」都不需要，模型每轮自己就知道；同时把每轮注入的
> 上下文再压一档。

### 还差什么

核查 payload 后发现两个硬事实：

1. **工作区 `AGENTS.md` 不是 DSH 读的那份**。DSH 记忆系统读
   `$DSH_HOME/AGENTS.md`（`dshhome/AGENTS.md`），而 payload 里
   **这个文件根本不存在**；工作区根目录那份是给 bash/file 工具的 cwd 用的，
   模型不会自动读。所以第二十六章的「去重说明」再精简，也只是兜底。
2. **模型是被动的**。即使 `/app/context` 摆在那，它也不会主动 curl；
   用户不点「带入对话」，这一轮仍然不知道当前页面。

### 本轮增量

**① 新增 DSH 插件 `dsh-plugin-githup-context`（每轮自动注入）**

`GithupContextPlugin.java` 在 `HarnessBridge` 起 node 之前：

- 从 `assets/harness/` 写出
  `dshhome/profiles/web/node_modules/dsh-plugin-githup-context/`
  （`package.json` + `lib/index.js`）
- 往 `profiles/web/cordis.patch.yml` 追加幂等 `- insert:` 块
  （语法照 `dshhome/cordis.patch.yml` 里已有的 insert 条目；
  与 `AccountDefaultRoute` 各自的 BEGIN/END 块互不干扰）
- 往 **`$DSH_HOME/AGENTS.md`** 追加一段 ≤1.2KB 的省 token 规矩
  （追加，不覆盖记忆系统已有内容）

插件 `apply(ctx)` 监听 `agent/pre-step`，结构照内置
`dsh-plugin-neuron-hub`：每个 `sessionId:turn` 只注入一次，
拉 `/app/context?brief=1`，把 `brief` 作为一条
`{ role:'user', source:{kind:'githup-context',form:'notice'},
content:[{type:'text',text}] }` 追加到 `decision.messages`。
全链路 try/catch，任何异常都 `return decision`，绝不让引擎 pending。
只有 `AppContext.consent()` 为 true 才启用；关掉即摘除 insert 块。

**② 上下文分档：每轮 ≤600 字符，完整速览按需**

| 内容 | 何时给 | 大小 |
|---|---|---|
| `AppContext.shortBrief()` | **每轮**由插件注入 | ≤600 字符：页面/仓库/一行速览 + 「不要探测」 |
| `GET /app/summary?repo=...` | 模型要总结时才 curl | ≤2.2KB：复用 RepoBrief 缓存（元数据 + README 摘要） |
| `/gh/api/repos/O/R/contents/PATH` | 确实需要细节 | 按需单文件 |

`contextJson()` 的 `brief` 改为**总是返回**短版（之前只在 RepoBrief 缓存命中时
才有，缓存未命中时插件拿不到东西就不注入）。短版明确写：
「不要执行 gh CLI、不要检查环境变量、不要列全库或逐文件读（那些轮次会白烧 token）」。

`LocalBridgeServer.appRoute()` 增加 query 与 `/app/summary` 路由，
直接复用 `RepoBrief.cachedBrief()`，**不重复造缓存、不重复打 GitHub**；
`/gh/help` 同步补 `cheap_summary` 自描述。

**③ 工作区说明 9KB → ≤1.5KB**

`GhBridgeDocs.text()` 整段重写成极简索引。真正生效的是插件注入的短 brief
与 `$DSH_HOME/AGENTS.md`；工作区那份只留兜底，避免万一被整份读进上下文
反而费 token。

### 现在的行为

1. 逛到 `gbataille/gitHUD` → 路由上报 → `RepoBrief` 后台预热（GitHub GET，
   零 DeepSeek token）→ AI 面板顶部卡片显示
2. 点开 AI 面板**随便说一句** → 插件自动把 ≤600 字符的当前页面事实注入本轮
   → 模型不用问、不用探测、不用读全库，直接基于事实回答
3. 「帮我总结这个库」→ 一次 `curl /app/summary` 拿完整速览
4. 「改完传到我账号」→ `POST /app/clone-repo` 建同名库
5. 「带入对话」按钮仍保留，可把完整速览预填进输入框

### 边界（如实）

- 插件加载依赖 `agent/pre-step` 与 `cordis.patch.yml insert`，照内置插件写法
  实现；未真机验证引擎加载，但失败也只是退回第二十六章的行为（卡片 +
  一键带入），不会崩引擎
- 「省 token」是相对上一版：少掉探测轮次、大 JSON、每轮重复的长说明；
  把代码发给 DeepSeek 做总结本身仍消耗模型 token，且 DSH 内核每次会话
  自带的系统提示 / 全量工具描述在 160MB payload 里，本层无法裁剪
- `consent:false` 时不装插件、不注入、不预抓
- 未真机跑完整加载链路；已验证 Java 结构、JS 语法、插件 `node --check`、
  package.json、Manifest、包身份、签名与防护链自洽


---

## 二十八、阶段 Y：为什么「根本没真正彻底辅助我的软件」

### 用户口径

> 「我感觉 DeepSeek agent 根本就没有真正的彻底辅助我的软件」，并要求把
> **修改后完整源码 + APK** 上传到 Buwrt/2。

前面十轮都在做「把仓库数据/路由喂给 AI」。这一轮核查后发现三个确凿硬伤，
每一个单独都能让用户觉得「AI 没辅助」：

### 根因 ①：插件大概率压根没被引擎加载（最硬的 bug）

核查 payload 内 `dshhome/cordis.patch.yml`：所有 `- insert:` 条目
（`memory-ui` / `user-profile-ui` / `another-me` / `neuron-hub` / `tool-gate` /
`self-card` …）**全部写在根 `dshhome/cordis.patch.yml`**（L114、L266–291）。
而 `dshhome/profiles/web/cordis.patch.yml` 只有 2 条 `config` 覆盖
（welcomeNoticeVersion / busyEnter），没有一条 insert。

阶段 X 的 `GithupContextPlugin.apply()` 偏偏只把
`- insert: githup-context` 写进了 **`profiles/web/cordis.patch.yml`**。
那是「用户层 config 覆盖」，插件极可能从未进入 loader 树 ——
于是「每轮自动注入」从来没发生过，模型继续按老习惯探测环境、烧 token。

**修**：两处都写（根 patch + 用户层 patch），幂等 BEGIN/END 块互不干扰；
插件文件同时放到 `profiles/web/node_modules/` 与 `$DSH_HOME/node_modules/`
（防模块解析基准不同）。

### 根因 ②：consent 藏在一个用户根本不会去开的第二个开关里

阶段 V 加了「AI 感知与克隆」开关，**默认关**。但用户早已打开的是
「AI 辅助 githup」（悬浮球开关，见用户截图 17 的绿色开关）——
两件事名字像、语义完全不同。用户以为开了就有 AI 辅助，实际：

- `AppContext.consent()` = false → `/app/context` 返回 `page:null`
- `GithupContextPlugin.apply()` 里 `on=false` → 插件不装
- AI 面板仓库卡显示「点右侧开启感知」
- 于是「AI 完全不认得当前库、也不辅助」

更糟的是：用户第一次点开 AI 球时**引擎早已启动**，即使这时去设置页同意，
插件也要重启引擎才能装上。

**修**：
- 插件改为**无条件常驻安装**；consent 只作为 `/app/*` 的**数据闸门**
  （未同意时返回 `page:null` + 禁止编造的 hint，模型如实说明）
- **第一次展开 AI 面板**就弹一次风险确认（同意 / 拒绝，文案按用户原话），
  点同意后 `setConsent(true)` + 立刻补抓 RepoBrief，**下一轮注入即生效**，
  不用重启引擎、不用再找第二个开关
- 设置页开关保留为「撤销 / 重新同意」入口，副标题说明首次展开也会确认
- 「AI 辅助 githup」开关语义不变（只管悬浮球显隐）

### 根因 ③：只有单向通道，AI **不能操作软件本体**（「彻底辅助」的真正缺口）

`LocalBridgeServer` 里 `MainActivity` / `runOnUiThread` / `evaluateJavascript`
出现次数全是 **0**，`/app/ui` 也是 0。也就是说：

- AI 读不到 githup WebView **当前已经渲染出来的内容**（Issue 正文、README、
  文件列表、可见按钮、顶栏）——这些是 App 自己的 DOM，零系统权限就能读
- AI **不能替用户操作软件**：不能导航、不能 Star、不能切 tab、不能翻译、不能返回
- 它永远只是「旁边一个能聊 GitHub API 的窗口」

**修：新增双向通道 `/app/ui/*`**

新增 `UiBridge.java`：`UiBridge.bind(Activity)` 由 MainActivity 挂上，
`eval(js)` 用 `CountDownLatch(2.5s)` 在**底座** WebView 同步执行受限 JS 并取回
结果（精确用 `MainActivity.hostWebView()`，不遍历 contentRoot —— AI 面板自己
也有 WebView，遍历会取错）；`toast(msg)` 给系统 toast + 底座 `UI.toast` 双回执。

新增 `AppUi.java`：

| 端点 | 作用 |
|---|---|
| `GET /app/ui/state` | 读当前屏幕渲染内容：hash / 标题 / 正文前 1400 字 / ≤18 个可见按钮 / 滚动位置 |
| `GET /app/ui/help` | 全部可用动作清单 |
| `POST /app/ui/act` | 白名单：`navigate` `open_repo` `search` `click` `star` `watch` `fork` `translate` `scroll` `refresh` `back` `theme` `copy` |

动作全部走底座已有 `window.Router` / `window.API` / `window.UI` 与页面原有按钮
（`#btn-star` 等），不绕过登录令牌；`arg` 过滤 `javascript:` / `data:` / `://` /
换行 / 任意函数，长度 ≤200；每个动作给用户一条可见回执
「AI 助手：已…」。闸门仍是 `AppContext.consent()`。

`AppContext.shortBrief()` / `contextJson()` endpoints / `ghHelp` /
`GhBridgeDocs.text()` / `$DSH_HOME/AGENTS.md` 同步写明这条通道，并明确
「用户说帮我 Star / 切到 Issue / 翻译这页时**直接调 /app/ui/act**，
不要只回一段文字让用户自己点」。

### 根因 ④（附加）：仓库 README 与「完整源码」认知

用户贴的仓库页面里 README 还是**原始 350KB 纯客户端**说明，Latest commit
也停在旧快照，看起来像「改动根本没传上来」。实测本地 515 个受控文件 vs
线上 515 个，只差 6 个（3 个历史 `.sha256`、2 个本轮新增 Java、
1 个 `keystore/pass.txt`）。

**修**：
- 重写 `README.md`：包身份、首次使用、AI 双向能力表、底座功能、安全、
  **合并版真实目录结构**、构建方式、边界
- 推送时做**全量 blob SHA 对齐**，补齐缺失文件
- `keystore/pass.txt` 是**明文签名口令**，故意不推到公开仓库（`.gitignore`
  排除 `*.keystore`；CI 用 Secrets）。这是安全决定，不是遗漏
- `version.json` 与 `apk/*.sha256` 由 CI 在发版时写，推送时跳过以免覆盖

### 现在的行为

1. 点开 githup → 引擎后台装载 → 插件常驻已装好
2. 打开「AI 辅助 githup」→ 球出现 → **第一次点球**弹风险确认 → 同意
3. 下一轮对话自动带着 ≤600 字符当前页面事实；逛到 `gbataille/gitHUD`
   顶部卡片立刻认出来
4. 「帮我 Star」→ 模型调 `POST /app/ui/act {"action":"star"}` →
   底座 Star 按钮被点、toast「AI 助手：已切换 Star」→ **软件本体真的动了**
5. 「切到 Issue」「翻译这页」「总结这个库」「改完传到我账号」同理

### 验证与边界（如实）

- 已验证：Java 结构、JS 语法、插件 `node --check`、Manifest、包身份、
  签名、防护链与源码指纹自洽；仓库文件树全量对齐
- **未真机验证**：cordis 是否真的把新 insert 条目加载进 loader 树、
  `agent/pre-step` 注入是否生效、`evaluateJavascript` 取回的 DOM 摘要格式。
  插件全链路 try/catch，加载失败也只是退回「卡片 + 一键带入」，不会崩引擎
- `/app/ui/state` 读的是 WebView DOM 文本，**不是屏幕像素**；没有控件树、
  不能点按系统界面。零权限指 Android 运行时权限
- 动作白名单不含任意 JS 执行；`click` 只在 `#view` 与 `#appbar-actions`
  内按可见文字匹配，找不到按钮会明确返回 `not found`
- 总结 / 修改代码仍会把内容发到 DeepSeek 云端；令牌权限越大 AI 经代理
  的能力越大，建议换 fine-grained 最小权限令牌
- 上游 payload 为第三方二进制，再分发请确认许可


---

## 二十九、阶段 Y+：EADDRINUSE 3080 ——「双启动器抢端口」根治

### 真机现象

覆盖安装后打开 AI 面板，开机页显示：

```
已校验 41,426 / 41,426  ·  本次新增 2
装载中断
启动失败：...ueues:90:21)
Plugins waiting for services (10): web-runtime / open-in-app / ...
Full diagnostics: /data/user/0/com.hubmobile.app/files/payload/dshhome/logs/startup-...log
```

用户贴出的完整诊断：

```
StartupError: dsh: startup failed: 2 required plugins did not activate
  webserver (required) @deepseek-ai/dsh-host-webserver
    Error: listen EADDRINUSE: address already in use 127.0.0.1:3080
  Plugins waiting for services (10): web-runtime (required) webServer,
    open-in-app webServer, directory-picker webServer, harness-dev-context webApp ...
```

> ⚠️ 诊断文件开头那句 `WARNING: Raw diagnostics may contain configuration or
> credential values from plugin errors. Review before sharing.` 不是客套话：
> 这类日志可能带配置/凭据值，**分享前先过一遍**。本次这份只含端口与堆栈。

### 因果链（单因，不要被 10 个 pending 迷惑）

```
3080 被占用
  → webserver(required) listen 失败
  → 依赖 webServer / webApp 服务的 10 个插件全部 pending
  → dsh-app-boot 的 required-entry audit 判定 "2 required plugins did not activate"
  → 写 startup-*.log、node 进程 exit(1)
  → HarnessBridge.start() 的 alive(p) 变 false
  → 抛「引擎进程提前退出：+ dsh-web.log 尾部」
  → 开机页显示整段英文堆栈
```

所以**不是**插件没装好、不是 AI 能力缺失、不是源码没传全，
就是**一个端口**。

### 为什么之前「端口顺延 3080→3091」没救回来

先澄清一个旧结论：核查内核后确认 `--port` **是生效的**——
`bin.js` 的 `parseDshArgs` 用 `allowUnknownOption()/passThroughOptions()`
把 `web --host 127.0.0.1 --port N` 透传给 `dsh-web-app` 的 `webCommand()`，
`webStartup.port` 再喂给 `webserver` 行的
`port: !!js ctx.webStartup.port ?? 3080`。顺延本身没问题。

真正的问题是**包里有两套彼此不知道对方存在的启动器**：

| 启动器 | 位置 | 端口来源 | 是否走 LOCK / 残留清理 |
|---|---|---|---|
| 主启动 | `HarnessBridge.start()` | `pickPort(3080)` | ✅ |
| 后台定时引擎 | `ScheduleExecutor.startEngine()` | `enginePort(ctx)`（默认 3080） | ❌ 独立 `ProcessBuilder` |

`ScheduleExecutor` 那套是历史遗留：独立环境变量、独立端口、
不设 `sProc`/`sTokenUrl`、还把日志 **append 到同一个 `dsh-web.log`**。
它自己的注释里甚至已经写着「引擎在跑也判"未就绪"，定时任务于是又去
拉起一个引擎（EADDRINUSE）」——坑早有记录，只是没根治。

竞态窗口：node 从 `pb.start()` 到真正 `listen` 要加载 4 万文件的插件树，
好几秒。两边 `pickPort`/`enginePort` 探测时 3080 都是空的 → 都选中 3080
→ 谁先 listen 谁赢，输的那个报 `EADDRINUSE: 127.0.0.1:3080`。
**顺延根本没机会介入**，因为失败方拿到的 CLI 参数就是 3080。

覆盖安装又放大了它：系统杀掉 App 主进程，但 `EngineService` 是
`START_STICKY` 前台服务、通知/定时任务会被拉起，node 子进程也可能变孤儿
继续占着 3080。

顺带解释截图里的「**本次新增 2**」：每次启动 `extract()` 会发现
`dshhome/cordis.patch.yml` 与 `profiles/web/cordis.patch.yml` 被我们上一轮
的插件安装器追加了块、大小与 zip 原版不一致，于是各重写一次。
安装器在 `start()` 里又会幂等写回去，**属正常现象，不是解压失败**。

### 本轮改动

**① 统一启动入口（根治）**

`HarnessBridge.ensureEngine(Context)`：所有后台入口共用的唯一方法。
- `existingUrl() != null` → 已在跑，直接返回
- `portBusy(engine_port)` → 本进程被重建但上次 node 还活着，直接认定就绪
- 否则走 **`start()`**：同一把 `LOCK`、同一套 `killStaleEngines` +
  `pickPort`、同一份 `sProc`/`sTokenUrl`、同一条日志

`ScheduleExecutor.startEngine()` 整段独立 `ProcessBuilder` 删除，
改成一行委托。全仓核查：`"web", "--host"` 形式的独立 spawn **归零**
（剩下的 `ProcessBuilder` 是 `life.js tick` 之类的本地脚本，不监听端口）。

**② 残留清理放宽到「同 uid 的任意 DSH node」**

旧 `killStaleEngines` 只杀 cmdline 含本包 payload 路径的进程；定时任务那套
可能从外部 `dshroot` 找 binjs，cmdline 未必含该路径。现改为
`Os.stat("/proc/<pid>").st_uid == Os.getuid()` 锚定，配合
`cmdline 含 bin.js + web` 双条件：能清掉本应用所有残留 node，
**不会误伤上游原版 / Termux（不同 uid）**。

**③ EADDRINUSE 自动重试**

`prepare()` 捕获到 `EADDRINUSE` → `resetEngineState()`（清掉指向死进程的
`sProc`/`sTokenUrl`，否则 `existingUrl()` 永远返回旧值）→ 再杀一次残留 →
等 900ms → 重走 `start()`（`pickPort` 重新探测并顺延），最多 2 次。
开机页同步显示「端口被占用，已清理残留，正在重试（1/2）…」。

**④ 错误文案不再甩一屏英文**

`AiPanel.onError()` 识别端口冲突，改成：
「引擎端口 3080 被占用（残留进程 / 后台定时任务 / 上游原版同时运行）。
已自动清理并重试；若仍失败：请完全退出本 App 再打开，或卸载手机上
另一个 DeepSeek Harness。」技术细节折叠在后面。

### 仍需用户操作的情况

只有一种：手机上**上游原版** `com.deepseek.harness` 正后台运行并占着 3080。
它的 uid 与本包不同，我们按设计**不杀别的 App 的进程**；此时
`pickPort` 会顺延到 3091，本包正常启动 —— 若顺延也失败，卸载上游原版
或完全退出它即可。

### 验证与边界（如实）

- 已验证：Java 结构、JS 语法、Manifest、包身份、签名、防护链与源码指纹自洽
- **未真机验证**：定时任务与主启动并发的真实时序；但统一入口后
  「两个 node 同时 listen」在代码路径上已不可能
- `life.js tick` 等本地脚本仍用独立 `ProcessBuilder`，它们不监听 TCP，
  与本次故障无关
- 诊断日志可能含配置/凭据，分享前请先审阅


---

## 三十、阶段 Z：第二次 EADDRINUSE 3080 ——「探测层」才是真凶，改用 3090 段

### 现象

装了第二十九章的新包（统一入口），真机**仍**报：

```
webserver (required)  Error: listen EADDRINUSE: address already in use 127.0.0.1:3080
Plugins waiting for services (10): web-runtime(required) webServer, ...
```

与上一轮日志逐字相同。说明第二十九章的「双启动器竞态」判断**只对了一半**：
统一入口消除了包内双启动，但 3080 上仍有占用者，且我的防护没兜住。

### 为什么上一轮没修好（诚实复盘）

关键线索：**日志里端口始终是 3080**。
如果 `pickPort` 真的顺延过，报错端口应该是 3091/3082 之类。
报错 3080 意味着 `--port 3080` 原样传给了 node —— 即 `pickPort(3080)`
返回了 3080，**它认为端口是空的**。

查 `pickPort` 原实现：

```java
private static boolean portBusy(int port) {
    Socket s = new Socket();
    s.connect(new InetSocketAddress("127.0.0.1", port), 250);  // ← 问题
    return true;   // 连得上 = 有人在 listen
}
```

`connect` 只能回答「有没有人在 listen」，**不能**回答「我能不能 bind」；
250ms 超时在部分 ROM 的回环策略下会把**已被占用**的端口判成空闲。
于是 3080 被选中 → node 加载 4 万文件插件树几秒后 listen → 撞上占用者。

占用者是谁？两种都不需要再猜：

| 候选 | uid | 为什么杀不掉 |
|---|---|---|
| 覆盖安装前**旧版本**留下的孤儿 node | 同 uid | `killStaleEngines` 依赖 `/proc/<pid>/cmdline`，Android 10+ 对其它进程有 SELinux 读取限制，真机上很可能一个都读不到 |
| 手机上的**上游原版** `com.deepseek.harness` | 不同 uid | 按设计不杀别的 App 的进程 |

`OverlayService.java` 里的上游注释其实早就写明这个坑：
「三套 App 同时安装会抢同一个 3080（表现为 EADDRINUSE）」。

另外顺手确认了 `--port` **是生效的**：`bin.js` 的 `parseDshArgs` 把
`web --host/--port` 透传为 `cmdlineArgs` → `dsh-cmdline.parseCmdline()`
→ `webCommand()` → `webStartup.port` → `webserver` 配置行
`port: !!js ctx.webStartup.port ?? 3080`。所以问题不在 CLI，
在「我们喂给 CLI 的那个数字」。

### 本轮改动：不再依赖探测结果去猜 3080 能不能用

**① 引擎整体迁到 3090 段**（新建 `EnginePorts.java` 作唯一真相来源）

`DEFAULT = 3090`、`MAX = 3100`。避开：
3080/3082/3084（上游正式/Lite/兼容三版本）、3081（本包本地桥）、
3181（无障碍）。上游原版和旧残留都占不到这一段。

**② `pickPort` 改用 ServerSocket 真 bind 探测**

```java
new ServerSocket(); setReuseAddress(false);
bind(new InetSocketAddress("127.0.0.1", p), 1);  // bind 成功 = 我能 listen
```

`bind` 比 `connect` 严格：TIME_WAIT、被别进程 listen、SELinux 拒绝都会
直接抛异常。顺延范围 3090–3099。

**③ 用户层 patch 双保险（关键）**

往 `$DSH_HOME/cordis.patch.yml` 追加幂等 `- id: webserver` 覆盖块，
把 bundle 层的 `port: !!js ctx.webStartup.port ?? 3080` 改成
`?? <本次实测端口>`（其余 5 个 config 键全部 restate，因为 patch 按 id
替换整份 config）。这样**即便 CLI --port 没被解析**，引擎也不会回落到
内核默认的 3080。每次起 node 前重写（`extract()` 会先把 patch 还原成
zip 原版，所以必须在 start() 里写）。

**④ 端口落盘，所有后台组件跟随真实端口**

`EnginePorts.save()` 把选定端口写进 `dsh_prefs.engine_port`。
`ScheduleExecutor.enginePort()` / `VoiceManager.getEnginePort()` /
`OverlayService` / `ShellBridge` / `LocalBridgeServer.statusJson()` 全部改为
读它，不再各自硬编码 3080。这一条同时修掉了另一个隐藏 bug：
主引擎顺延端口后，后台任务以前永远连 3080 → 判定「引擎没跑」→ 又拉起一个。

**⑤ 围栏范围同步**

`LocalBridgeServer.ENGINE_MIN/MAX`、`LocalFence`、
`AiPanel` / `HarnessActivity` 的回环 URL 校验，全部从
`[3080,3092)` 改成 `[3090,3100)`；CORS 默认 Origin 与 `/status` 的
`enginePort` 改成动态值。给 node 注入 `DSH_WEB_URL`（部分技能默认写死 3080）。

**⑥ 保留但降级**：`killStaleEngines` 仍会尝试清同 uid 残留，
`prepare()` 的 EADDRINUSE 自动重试仍在；但**不再把它们当兜底**——
真正的兜底是「根本不用 3080」。

### 不变的东西

- 本地桥仍 3081、无障碍仍 3181（`APP_NOTIFY_PORT` / `APP_A11Y_PORT` 不动；
  `HarnessAccessibilityService` 的 `3080+101=3181` 保留，注释已说明它只用于
  算 a11y 端口，不跟随引擎迁移）
- 启动入口仍是唯一的 `HarnessBridge.ensureEngine()`（第二十九章成果保留）
- 包名 / 版本 / 官方签名 / 防护链 / 双向 `/app/ui/*` 能力全部不变

### 装完怎么操作

覆盖安装 → **完全退出 App 再重进** → 开机页会显示
「本地引擎端口 3090，正在拉起 node…」。出现 3090 就说明本版已生效；
若仍显示 3080，说明装的还是旧包。

### 验证与边界（如实）

- 已验证：Java 结构、JS 语法、Manifest、包身份、签名、防护链与源码指纹自洽
- **未真机验证** 3090 段在这台设备上确实空闲；但 3090 不在上游三版本
  (3080/3082/3084)、本包桥 (3081)、无障碍 (3181) 的取值里，且 bind 探测
  失败会自动顺延到 3099
- 若 3090–3099 全被占（极罕见），`pickPort` 回退 DEFAULT 并交给
  `prepare()` 的 EADDRINUSE 重试；此时开机页会明确提示端口冲突
- 用户层 `- id: webserver` 覆盖依赖 cordis「按 id 替换 config」语义；
  根 `dshhome/cordis.patch.yml` 是最后应用的 patch 层，优先级最高
- 诊断日志可能含配置/凭据值，分享前请先审阅


---

## 三十一、阶段 AA：为什么上游有输入框、同屏面板里却没有

### 两张截图

| | 图 1（上游 / 期望） | 图 2（本包 / 实际） |
|---|---|---|
| 顶栏 | DSH 自己的 `Greeting` | 原生品牌条 `githup AI · DeepSeek Harness · 复位 · ⛶` |
| 输入区 | 圆角白色 composer：`发消息或创建任务, / 调用指令, @ 文件…` + `DeepSeek-V4…` + 蓝色发送键 | **完全不见**，只剩下面那排工具图标 |
| 底部 | composer + 工具栏 + 系统键盘 | 工具栏 + 系统键盘，中间空了一大截 |
| 正文字号 | 正常 | 明显偏大，标题被裁 |

用户问的是「为什么上游的输入可以成为第一张图片，而我的根本看不到输入框」。

### 根因：DSH 住进了**别人的 window**，键盘模式没跟着搬

三处源码逐条对照，结论非常干净：

| 位置 | windowSoftInputMode | WebView 字号 |
|---|---|---|
| 上游 `harness/android-app` 的 `MainActivity`（图 1） | **Manifest 未设置**（系统默认按可滚动 View 走 `adjustResize`） | `setTextZoom(100)` ✅ |
| 本包 `.HarnessActivity`（独立全屏入口，阶段 L 之前用过） | `adjustResize|stateHidden` ✅ | — |
| 本包 `.MainActivity`（**现在 DSH 实际所在的 window**） | `adjustPan|stateHidden` ❌ | `setTextZoom` **缺失** ❌ |

阶段 L 为了「桌面只留一个图标、点开即 GitHub、AI 同屏」，把
`HarnessActivity` 的 LAUNCHER 摘掉，DSH 改挂在 `MainActivity` 的
`contentRoot` 上一层 `AiPanel` 面板（68% 抽屉 / 全屏）。**Activity 换了，
键盘行为却没跟着换**：

- 上游 / `HarnessActivity`：独立全屏 Activity + `adjustResize`。
  键盘弹出 → window 真的变矮 → WebView 重新测量 → DSH 前端
  `position:fixed; bottom:0` / `100dvh` 的 composer 自动贴到键盘上沿 → 图 1。
- 本包 `MainActivity`：`adjustPan`。键盘弹出时系统把**整个 window 平移**
  去露焦点，但 **WebView 高度不变**；DSH 的固定底栏按原视口算还在屏幕最底，
  正好被键盘盖住。`adjustPan` 对 WebView 内部 `contenteditable` 的焦点位置
  又常常算不准，于是内容被往上顶、标题被裁、composer 挤出屏幕 → 图 2。

叠加第二个差异：`AiPanel.configure()` 少了 `setTextZoom(100)`（上游
`MainActivity.java` L498 有）。WebView 会跟随系统「字体大小 / 显示大小」
设置放大整页，图 2 的字明显更大，composer 更易被挤出视口。

`MainActivity` 还用了 `SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | LAYOUT_STABLE`
（edge-to-edge），这种窗口下光把 Manifest 改成 `adjustResize` 在部分版本
也不一定可靠 —— 所以必须**手动压缩面板**。

### 本轮改动（只动 `AiPanel.java`）

1. **`setTextZoom(100)`** 补进 `configure()`，与上游逐字对齐，
   系统大字体不再撑大 DSH 页面。
2. **键盘高度监听**：`decor.getWindowVisibleDisplayFrame()` 算键盘高度，
   `expanded` 时把面板高度压到
   `root.height − statusBarHeight − keyboardHeight`（顶部让状态栏、底部正好到
   键盘上沿），并对 WebView `requestLayout()`。
   这样**不管 windowSoftInputMode 是什么**，WebView 都真的变矮，触发
   `resize` / `visualViewport` 变化，DSH 的固定输入栏重新贴回键盘上方。
3. **运行时切键盘模式**：`expand()` 期间
   `setSoftInputMode(ADJUST_RESIZE | STATE_UNCHANGED)`，`collapse()` /
   `onDestroy()` 还原成底座原本的 `adjustPan|stateHidden` —— 不影响
   GitHub 客户端自己的搜索框 / 评论框。
4. `panelLp(int keyboardHeight)` 重载；原有的 `OnLayoutChangeListener`
   改为跟随键盘高度，避免键盘期间把面板又改回 68% 抽屉。
5. `onDestroy()` 注销 `OnGlobalLayoutListener`，防泄漏。

### 为什么之前没发现

阶段 W 修「点 AI 放大没反应 / 有延迟」时只处理了**展开动画 + 缩放比例**，
当时键盘没弹起，composer 还在视口内；真机一旦点输入框唤起键盘，
`adjustPan` 的平移就把输入栏吞了。属于「布局尺寸对了、输入法窗口策略没对」。

### 装完怎么验证

覆盖安装 → 完全退出重进 → 点开 AI 球 → **点一下输入区唤起键盘**：
composer 应像图 1 那样浮在键盘上方，能正常打字、选模型、发送。
收起面板后回 GitHub 客户端，搜索框键盘行为应与以前一致。

### 验证与边界（如实）

- 已验证：Java 结构、JS 语法、Manifest、键盘配置三处对照、包身份、
  签名、防护链与源码指纹自洽
- **未真机验证** 键盘弹出时序与 DSH 前端对 `resize` 的响应；
  但手动压缩 WebView 高度是 Android 上最可靠的兜底，不依赖前端配合
- 键盘监听阈值 `dp(90)`：小于它的高度差当导航栏/状态栏差异忽略，
  极少数全面屏手势条机型可能需微调
- 全屏 `⛶` 与 68% 抽屉两种形态都走同一套压缩逻辑；键盘期间面板会
  临时占满可视区域（与上游全屏观感一致），收起键盘即恢复
- 之前明文出现过的 `ghp_` 令牌建议立刻撤销；上游 payload
  为第三方二进制，再分发请确认许可


---

## 三十二、阶段 AB：与上游的完整对照 —— 缺什么、补了什么、什么不补

用户要求「我的软件对于上游还缺少什么，缺少什么就增添什么」。
这轮不再凭印象，对**上游完整壳 / payload / 本包源码**做了逐文件盘点。

### 盘点口径

| 来源 | 规模 |
|---|---|
| 上游 `harness/android-app/src` Java | **28 类**（MainActivity 11179 行、VsreenBridgeService 2072 行、OverlayService 4402 行、AccessibilityService 1987 行、vscreen/ 4 类 1333 行…） |
| 上游 `res/` | 8 个文件（drawable 2 / values 4 / xml 1 / Manifest） |
| 上游 `patches + plugins + skills + selfbuild` | **119 个文件** |
| 上游 Manifest | 17 权限 / 1 Activity / 4 Service / 1 Receiver / 2 Provider |
| 本包 `app/src/main/java` | **73 类**（底座 31 + harness 包 38） |
| 本包 `assets/harness` | **122 文件**（上游 119 个脚本资产已全带） |

### 之前各轮已经补齐的

桌宠同源 4502 行、控制台 ShellBridge 36 动作、KernelStore / BuildEnvInstaller
全量、ScheduleExecutor、ShareInbox、NotificationArchive、UsageStatsHelper、
NfcStore、LogShareProvider、BackupRestore / Validator、RootShell、SafeFiles、
VoiceManager、无障碍服务 1990 行、3081 本地桥、3080→3090 端口迁移、
单图标同屏、双向 `/app/ui/*`、AI 辅助与令牌跟随、键盘与输入框修复。

### 本轮盘点新发现并补齐的 8 项

| # | 缺口（上游有 / 本包无） | 真机后果 | 本轮实现 |
|---|---|---|---|
| 1 | `onShowFileChooser` + 拍照 | 点「添加附件 / @文件」**完全没反应** | `AiPanel` 实现文件选择器 + `MediaStore` 拍照 + `MainActivity` 转发 `onActivityResult`；相机不可用自动退回文件选择 |
| 2 | `fallback.html` 白屏兜底页 | 引擎没起来时纯白屏，什么也点不到 | 新增 `assets/fallback.html`，主框架错误自动切入；后台每 2s 探测端口，通了自动回 AI；带重试 / 安全模式 / 电池 / 诊断日志 6 个按钮 |
| 3 | `diagLog` 落盘环形缓冲 | JS 异常只进 logcat，导出日志啥也看不到 | `UpstreamParity.diagLog` 写 `files/diagnostics.log`（256KiB 环形），`onConsoleMessage` 按上游 DSHJS 口径过滤 |
| 4 | 安全模式 | 插件写坏 `cordis.patch.yml` → 引擎起不来 → 又只能靠引擎去修，**死结** | `enterSafeMode` 旁置 4 个用户层配置到 `web.userlayer-<时间戳>`（**会话/凭据不动**），`exitSafeMode` 还原；连续失败 ≥2 次在开机页给入口；控制台新增 `engine.safe_mode` / `exit_safe_mode` |
| 5 | node 看门狗 | 挂后台被系统杀掉后 AI 一直转圈 / 白屏 | `startWatchdog` 每 5s 探测，`isRunning()` 为假且曾启动过 → `ensureEngine()` 自动重拉；20s 防风暴、用户主动 stop 不自愈；`reviveEpoch` 通知 AiPanel 自动重载 |
| 6 | 电池优化白名单引导 | 只声明了权限，没有任何运行时检查 | `batteryOptimized` / `requestIgnoreBattery`；面板展开时每 24h 最多一次温和弹窗「去设置 / 暂不」 |
| 7 | 401 自动重握手 | cookie 失效后只亮一张错误卡 | `onReceivedHttpError` 主框架 401 → 回引擎根路径重握手（`HarnessBridge.homeUrl`，不重放一次性 token） |
| 8 | Shizuku 声明 + vscreen 源码层 | Manifest 缺权限；`vscreen/` 4 类完全没搬 | 补 `moe.shizuku.manager.permission.API_V23`；搬入 `vscreen/` 四类（FakeContext / LocalHttpFence / Main / Workarounds，共 1333 行），改包名、外部根路径改 `/sdcard/githup` |

附带补齐：`HarnessBridge.homeUrl()`（不带一次性 token 的根路径，401 / 兜底恢复 /
看门狗重拉共用）、`engine.diag` 控制台动作、`engine.start/stop` 同步看门狗标志。

### 逐项对照结果（盘点后的剩余差异）

| 上游能力 | 本包状态 | 说明 |
|---|---|---|
| 28 个 Java 类 | **27 个已等价实现** | 仅 `MainActivity` 用 `AiPanel+HarnessActivity+HarnessBridge` 同屏精简替代（用户明确要单图标同屏，不能搬回独立全屏 Activity） |
| `VsreenBridgeService` 2072 行 | 91 行真实探测占位 | **编译依赖 `rikka.shizuku.Shizuku` + hidden AIDL `IRemoteProcess`/`IShizukuService`**，本包无 Shizuku AAR；且 `vscreen_shizuku.jar` 只在上游发行 APK 里，本版 payload 没有 |
| `vscreen/` 4 类 | ✅ 本轮搬入 | 只用 framework + 反射，可编译；但它们是**以 shell uid 由 app_process 加载的特权端**，运行时仍需 Shizuku 授权 + 服务端 dex |
| 虚拟屏实时画面 | ❌ 仍不可用 | 见上；控制台会明确给「需 Shizuku 授权 / 缺服务端 jar」，不假装成功 |
| 开机动画 `bootFilm` / `BOOT SEQUENCE` | ❌ 按用户要求不搬 | 用户明确要品牌版四句对话（githup ↔ DeepSeek），且不要整屏启动页 |
| 原生冷启动控制台 UI（consoleLayer） | ❌ 按用户要求不搬 | 与「单图标同屏、点开即 GitHub」冲突；DSH 前端控制台已由 3081 桥全量接通 |
| 8 页权限引导向导 `GuidePage` | 精简为运行时温和提示 | 整屏向导与单图标同屏冲突；权限引导做成按需弹窗 |
| 上游包名 `com.deepseek.harness` / 对方签名 | ❌ 不能搬 | 用户定「包名签名以我为准」；也不能覆盖升级上游原版 |
| 上游 res 8 文件 | ✅ 等价 | 本包 `themes.xml` 替 `styles.xml`，`harness_a11y_config.xml` 替 `accessibility_config.xml`，其余同名 |
| 119 个插件 / 技能 / selfbuild 脚本 | ✅ 全带 | `assets/harness` 122 文件 |

### 不补的理由（如实，不是漏做）

1. **包名 / 签名以我为准** → 不能变成上游包，也不能覆盖升级上游原版
2. **单图标同屏** → 不能搬回独立全屏 Activity、原生冷启动控制台、整屏权限向导
3. **品牌四句对话** → 不能换回上游 BOOT SEQUENCE 开机动画
4. **Shizuku hidden AIDL + 服务端 jar 不可得** → 虚拟屏实时画面、特权 shell 需用户手机侧装 Shizuku 授权，且需上游发行 APK 里的 `vscreen_shizuku.jar`；本轮已把**能编译的源码层**与权限声明补齐，运行时缺口如实保留

### 验证与边界（如实）

- 已验证：Java 结构、JS 语法、Manifest、包身份、签名、防护链与源码指纹自洽
- **未真机验证**：`<input type="file">` 拍照链路、fallback 探测时序、看门狗重拉后
  DSH 前端对重新导航的响应；但每一步都有 try/catch 与兜底（相机不可用退回文件
  选择、探测失败继续轮询、重拉失败只记日志不崩）
- 安全模式只旁置**本包自己生成**的 4 个配置，不动会话 / 凭据 / 设置
- 兜底页 `githupFallback` 桥带 `fallbackActive` 守卫，引擎页调不到它
- 之前明文出现过的 `ghp_` 令牌建议立刻撤销；上游 payload 为第三方二进制，
  再分发请确认许可


---

## 三十三、阶段 AC：上一轮输入框修复为什么真机没生效

### 复现截图（用户 16:38）

- **本包**：AI 品牌条 `githup AI · DeepSeek Harness · 复位 · ⛶` 下面
  **直接就是那排灰色工具图标**，再下面就是系统键盘；
  上游那张白色圆角 composer（`发消息或创建任务…` + `DeepSeek-V4…` + 蓝色发送键）
  **完全不见**。上半部分还完整显示着 GitHub 客户端首页（我的仓库 19）。
- **上游**：composer 稳稳浮在键盘上方，对话区、思考气泡都还在。

不是 DSH 没加载，也不是 `setTextZoom` 没补 —— 是**面板被键盘挤到只剩一条**。

### 上一轮（AA）方向对，但两个硬伤让它落不了地

AA 的诊断没错：`MainActivity` 是 `adjustPan|stateHidden`，上游独立 Activity
默认 `adjustResize`，Activity 换了键盘行为没跟着换。但 AA 的实现有两条
在真机上直接失效：

| # | 硬伤 | 后果 |
|---|---|---|
| ① | `MainActivity` 用了 `SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN \| LAYOUT_STABLE`（edge-to-edge 全屏布局） | **Android 在全屏布局窗口下会忽略 `ADJUST_RESIZE`**，decor 根本不缩，`applySoftInput(true)` 切了等于没切 |
| ② | 面板 `panelLp()` 无论什么状态都是 **`Gravity.BOTTOM` 对齐** | 键盘弹出 → `adjustPan` 把**整个 decor 往上平移** → BOTTOM 对齐的面板跟着沉到键盘底下，只剩品牌条 + 一小截工具栏露出来；DSH 的 `position:fixed; bottom:0` composer 正好被吞 |

AA 写的「键盘期间压到 `avail − status − kb`」用的还是 `root.getHeight()`，
而 root 在 pan 之后屏幕可见部分已经变了，算出来的高度和屏幕上的位置
对不上。所以截图里 GitHub 首页还完整显示、AI 面板只占底部一条。

### 阶段 AC：不让系统插手，面板自己锚定可视区

1. **`SOFT_INPUT_ADJUST_NOTHING | STATE_UNCHANGED`**
   面板展开期间系统**完全不 pan、不 resize decor**。键盘就浮在窗口上，
   可视区由我们自己算。
2. **键盘弹出时改 `Gravity.TOP`**
   `topMargin = statusBarHeight()`，
   `height = min(r.bottom, root.height) − statusBarHeight()`
   → 面板正好铺满「状态栏下沿 ↔ 键盘上沿」，与上游独立全屏 Activity 的
   WebView 视口**完全一致**。composer 自然贴回键盘上方。
3. **高度双保险**
   `getWindowVisibleDisplayFrame()` + API 30+ `WindowInsets.Type.ime()`
   取较大值；ADJUST_NOTHING 下部分 ROM 的 visibleFrame 不随 IME 变化时
   insets 仍能拿到键盘高度。
4. **显式派发 `resize`**
   WebView `requestLayout()` 后 `window.dispatchEvent(new Event('resize'))`，
   兼容 DSH 前端 `visualViewport` / `100dvh` 监听。
5. **收起面板**：先 `hideSoftInputFromWindow`，延迟 120ms 再还原底座的
   `adjustPan|stateHidden` —— 否则还原那一帧键盘还开着，窗口会被顶上去。
6. 每次可视区变化写 `IME kb=… visibleBottom=… decor=… expanded=…`
   到 `diagnostics.log`，真机若仍不对，导日志即可定位，不用再猜。
7. 保留 AA 的 `setTextZoom(100)`（系统大字体不撑大整页）。

### 键盘期间会盖住 GitHub 客户端吗

会。键盘弹出时面板 TOP 锚定占满可视区，与上游独立全屏观感一致；
**收起键盘立即恢复原来的 68% 抽屉 / 全屏**，GitHub 客户端上半部分重新可见。
这是「点开 AI 打字」该有的专注态，不改变单图标同屏设计。

### 验证与边界（如实）

- 已验证：Java 结构、JS 语法、Manifest、包身份、签名、防护链与源码指纹自洽
- **未真机验证** `ADJUST_NOTHING` 下各 ROM 的 `getWindowVisibleDisplayFrame`
  与 IME insets 时序；已用双取值 + 显式 resize 派发兜底
- `dp(90)` 阈值：更小的高度差当导航栏差异忽略
- 键盘监听只在 `expanded` 时改布局，不影响纯逛 GitHub
- 之前明文出现过的 `ghp_` 令牌建议立刻撤销；上游 payload 为第三方二进制，
  再分发请确认许可


---

## 三十四、阶段 AD：为什么 AA / AC 两次「修好」真机都没变

### 用户复现（17:37）

上游打开键盘，白色圆角 composer（`发消息或创建任务…` + `DeepSeek-V4…`
+ 蓝色发送键）稳稳浮在键盘上方；本包依旧不行。

### 确凿根因：两轮代码**一次都没执行**

AA 和 AC 都建立在同一个没验证的假设上：**键盘弹出会触发
`ViewTreeObserver.OnGlobalLayoutListener`**。事实相反：

| 轮次 | 键盘模式 | decor 是否变 | OnGlobalLayout | 结果 |
|---|---|---|---|---|
| AA | `ADJUST_RESIZE` | **不变**（窗口是 `LAYOUT_FULLSCREEN`，Android 在全屏布局下忽略 resize） | 不触发 | 压高度代码没跑，面板保持 68% BOTTOM |
| AC | `ADJUST_NOTHING` | **明确不变**（这个模式的定义就是系统不动 decor） | 更不触发 | TOP 锚定代码没跑，键盘直接盖住 composer |

所以两轮写的「键盘期间把面板压到 `avail − status − kb`」在真机上
`lastKeyboardHeight` 一直是 0 / -1，面板从头到尾都是那个 68% 底部抽屉，
键盘一盖，DSH `position:fixed; bottom:0` 的 composer 就被吞掉 ——
与用户截图「品牌条 + 工具栏 + 键盘，输入框没了」完全吻合。

**这不是 ROM 差异，是事件根本没发生**，从源码就能推出来，不需要真机。

### 上游为什么能

上游 `MainActivity` 没有 `SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN`，
是普通窗口 + 默认 `adjustResize`：键盘弹出 → 系统真的把 content 缩到
键盘上沿 → WebView view 高度变化 → CSS viewport 更新 →
`bottom:0` 的 composer 自然贴到键盘上方。**这才是本质差异**。

底座 `MainActivity.boot()` 为了 edge-to-edge 设了
`LAYOUT_FULLSCREEN | LAYOUT_STABLE`，阶段 L 把 DSH 搬进同屏面板时
没把这个差异一起处理。

### 阶段 AD：与上游同构，让系统干活，自己只做兜底

1. **展开 AI 时把窗口切回普通布局**
   摘掉 `SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN` / `FULLSCREEN`，
   保留 `LAYOUT_STABLE | VISIBLE`；`savedSysUi` 存原值，收起时还原
   → `ADJUST_RESIZE` 不再被系统忽略
2. **`SOFT_INPUT_ADJUST_RESIZE | STATE_UNCHANGED`**
   键盘弹出由系统缩 contentRoot，WebView view 真的变矮
3. **键盘检测不再依赖 OnGlobalLayout**
   API 30+ `decor.setOnApplyWindowInsetsListener` 读
   `WindowInsets.Type.ime().bottom`（IME insets 独立于 adjust 模式，
   必定派发）；返回 `v.onApplyWindowInsets(insets)` 不消费，
   系统 resize 继续生效。API 24–29 保留 OnGlobalLayout ——
   在 ADJUST_RESIZE 下窗口真变了，它就会触发
4. **防震荡**：API 30+ 时 layout 通道不许把已检测到的键盘归零，
   归零只由 insets 判定
5. **面板布局双路**
   `decorH − rootH ≈ kb` → 系统已 resize → 面板 `MATCH_PARENT`
   填满缩小的 root；否则 → 回退阶段 AC 的 `Gravity.TOP` 锚定
6. 两路统一走 `onKeyboardChanged()`：刷新布局 + `requestLayout()`
   + 派发 `resize` + 写 `IME kb=… root=… resized=… src=insets/layout`
   到 `diagnostics.log`；窗口切换另写 `SYSUI` 行
7. 收起面板先收键盘，延迟 120ms 还原底座全屏 flag 与
   `adjustPan|stateHidden`
8. 保留 `setTextZoom(100)`

### 这次和前两次的本质区别

前两次都在**跟系统抢键盘**（不让系统动、自己算可视区），
代价是必须依赖一个不会触发的事件。这次**让系统像上游一样干活**，
自己只在系统没干成时兜底，且检测通道换成必定派发的 IME insets。

### 装完怎么验 / 仍不对怎么定位

覆盖安装 → 完全退出重进 → 点开 AI 球 → 点输入区唤起键盘：
白色 composer 应浮在键盘上方，能打字、选模型、发送。

若仍不对，兜底页「查看诊断日志」应能看到：
- `SYSUI AI 展开：摘掉 LAYOUT_FULLSCREEN，切 ADJUST_RESIZE`
- `IME kb=8xx … resized=true src=insets`

**没有 SYSUI 行** = 装的不是这版；**有 SYSUI 但 kb 一直 0** =
insets 没派发（把日志发我）；**resized=false** = ROM 忽略 resize，
应自动走 TOP 兜底。

### 验证与边界（如实）

- 已验证：Java 结构、JS 语法、Manifest、包身份、签名、防护链与源码指纹自洽
- **未真机验证** IME insets 派发时序与全屏 flag 切换时状态栏外观变化；
  键盘期间状态栏会由透明变实心（与上游普通窗口一致），收起后还原
- `dp(90)` 阈值：更小的高度差当导航栏差异忽略
- 键盘监听只在 `expanded` 时改布局，不影响纯逛 GitHub
- 之前明文出现过的 `ghp_` 令牌建议立刻撤销；上游 payload 为第三方二进制，
  再分发请确认许可


---

## 三十五、阶段 AE：桌宠按钮「启动不了」

### 现象

用户反馈桌宠按钮点了起不来（无截图，按前端按钮链路逐段核查源码）。

### 确凿断点：GET /overlay 这条路径**从来没有权限引导，而且回的是假成功**

前端 `dsh-android-console/lib/client.js`：

```js
const OVERLAY_BRIDGE = 'http://127.0.0.1:3081/overlay';
// 「显示桌宠」开关 / 「召回桌宠」按钮
run('show', 'action=show')
run('panel', 'action=panel&show=1')
```

而仓里两处悬浮窗权限引导分别挂在**别的路径**上：

| 位置 | 挂在 | 桌宠按钮走不走 |
|---|---|---|
| `LocalBridgeServer.jumpSetting()` | `/setting` POST（`android_setting_app` 插件） | ❌ |
| `ShellBridge.openSettings()` | `dshshell.ctlAct("setting.open.overlay")` | ❌ |

于是「显示桌宠 / 召回桌宠 / 打开面板」这条 GET 路径在没授权时：

1. **不检查** `Settings.canDrawOverlays()`，也**不拉**
   `ACTION_MANAGE_OVERLAY_PERMISSION` —— 用户根本没被提示过要开权限
   （之前交付说明里写的「点显示桌宠会弹系统提示」与代码不符，是说明错了）
2. 直接 `startForegroundService` → `OverlayService.onCreate()` →
   `addToWindow()` → `wm.addView(TYPE_APPLICATION_OVERLAY)` 抛
   permission denied → `stopSelf()`，**服务当场自杀**
3. `OverlayService.dispatch()` 里 `catch(Throwable ignored){}` 把
   FGS 被系统拒绝的异常整个吞掉，连日志都没有
4. 桥的 `show` 分支**无条件**回
   `{"ok":true,"running":true,"visible":true}` —— **假成功**

前端 `run()` 收到 `ok:true` 先弹「桌宠已召回，正在当前界面显示」，
700ms 后 `reload()` 读真实 `statusJson` 得到 `running:false`，开关弹回、
按钮像没反应。**这就是「启动不了」的完整链条**，不需要真机就能从源码推出来。

Manifest 里 `SYSTEM_ALERT_WINDOW`、`FOREGROUND_SERVICE`、
`OverlayService(enabled=true)` 都声明齐全，不是漏声明。

### 顺带排掉的其它嫌疑

- 前端写死 `3081`，`LocalBridgeServer.PORT = 3081` 一致；引擎已迁 3090 段，
  `originOk()` 允许 3090–3099，CORS 没问题
- `VsreenBridgeService` / `HarnessAccessibilityService` 不绑 3081（后者 3181）
- `overlayWindowType()` 用的是 `TYPE_APPLICATION_OVERLAY`，与上游一致

### 这轮改了什么

**`LocalBridgeServer.java`**

- `overlay()` 新增权限闸门：`show` / `panel` / `dock` 这类要 `addView`
  的动作先查 `canDraw`；没权限就拉
  `ACTION_MANAGE_OVERLAY_PERMISSION`（`FLAG_ACTIVITY_NEW_TASK`，
  10 秒节流防止连点被反复甩到设置页），并回
  `ok:false + needPermission:true + 中文 error` —— 前端 `run()` 会把它
  显示成红色提示，不再静默回弹
- `show` 分支去掉假成功：`dispatch()` 后 `awaitOverlay(1200)` 等
  `OverlayService.get() != null && isRunning`，回报 `statusJson` 的**真实**
  running/visible；服务没起来就回带原因的 `ok:false`
- `status` 分支补 `permission` / `needPermission`
- 新增 `ensure(Context)`：展开 AI 面板时自愈 3081 桥（`start()` 已有
  `isRunning` 短路，重复调用无害），覆盖「桥根本没在听」那一类
- 所有关键分支写 `OVERLAY` 诊断到 `diagnostics.log`

**`OverlayService.java`**

- `dispatch()` 改返回 `boolean`；`startForegroundService` 失败落盘 +
  `startService` 兜底，不再 `catch(ignored)` 吞异常

**`AiPanel.java`**

- `expand()` 开头调 `LocalBridgeServer.ensure()`

### 装完怎么操作

覆盖安装 → 完全退出重进 → AI 面板 → 设置 → 桌宠 → 点「显示桌宠」：

- **第一次**（没授权）：自动跳系统「显示在其他应用上层」页，页面红字提示
  「需要先开启…开完回来再点一次」 → 打开开关 → 返回 App
- **再点一次**：桌宠真的浮起来，`status` 显示 `在线`
- 已授权过但服务仍没起：红字会明确写「系统可能限制了后台启动」，
  日志里有 `OVERLAY show posted=… up=…`

### 验证与边界（如实）

- 已验证：Java 结构、JS 语法、Manifest、包身份、签名、防护链与源码指纹自洽
- **未真机验证** 授权页跳转与各 ROM 的 FGS 后台启动限制；本轮用户未附截图，
  若指的不是 DSH 桌宠页那个开关（例如底座设置里的 AI 悬浮球），需补截图再定位
- `fluid` / `vscreen` 两个开关只改 prefs，不强制要求悬浮窗权限
- 虚拟屏实时画面仍需 Shizuku，与本修复无关
- 之前明文出现过的 `ghp_` 令牌建议立刻撤销；上游 payload 为第三方二进制，
  再分发请确认许可


---

## 三十六、阶段 AF：首次启动 10 步权限向导（与上游 DSH 一致）

### 需求与截图

用户给出上游首次启动页截图：`第 1 / 10 步`、`首次使用 · 配置手机权限`、
`存储权限`、说明「不给的话，AI 无法保存任何文件」、
`当前状态：已授权`（绿）、蓝色主按钮 `已授权 ✓`、
底部 `跳过这页 / 上一步 / 下一步`。要求「第一次启动时先给权限」。

### 上游实现（源码定位，非猜测）

`harness_src/android-app/src/com/deepseek/harness/MainActivity.java`
L2488–L2898，注释写着「v1.13.12 重做」：

- `GuidePage`（title / desc / actionLabel / provider / action）
- `buildGuidePages()` 定义 **9 个权限页**，`renderGuidePage()` 在
  `guideIndex == guidePages.size()` 时渲染**第 10 页（完成页）**
  → `total = pages.size() + 1 = 10`，与截图「第 1 / 10 步」精确对上
- 每页状态行 `PermRow` + `refreshAllStatuses()`（从系统设置授权回来刷新）
- `guideFinish()` 写 `setup_done=true` → `showEngineScreen(); startEngine()`
- 配色在 `res/values/colors.xml`：`accent_brand #4D6BFE`、
  `shell_bg_light #F7F8FB`、`text_on_light #1F2328`、
  `status_green #1F9D6B`、`status_red #D9503F`

### 之前为什么「仍然不补」

第三十二章写过「原生冷启动控制台 / 8 页权限向导：与单图标同屏冲突」。
冲突点很具体：上游 `showPermissionScreen()` 用 **`setContentView(root)`**
把整个 Activity 窗口换掉，走完再 `showEngineScreen()` 重建引擎界面。
本包 `MainActivity.contentRoot` 里同时住着底座 WebView、AiPanel 面板、
悬浮球，直接换 content view 会把 GitHub 客户端整个抹掉，违背「点开就是
githup、AI 同屏」的既定设计。

### 这轮怎么解决

新增 `com.hubmobile.app.harness.PermissionWizard`（638 行），
**逐项移植上游 9 页 + 完成页**，只改四处：

| # | 上游 | 本包 | 原因 |
|---|---|---|---|
| ① | `setContentView(root)` | `host.addView(root, MATCH_PARENT)` 盖在 contentRoot 顶层 | 保留底座 WebView 与 AI 面板，走完只移除这一层 |
| ② | `guideFinish() → showEngineScreen(); startEngine()` | 写 `setup_done`、移除覆盖层 | 引擎已在 `AiPanel.attach()` 无条件后台启动，不因向导暂停 |
| ③ | `showShizukuDialog()` 完整授权链 | 说明弹窗 + 打开 Shizuku | 本版未接入 Shizuku hidden AIDL 服务端，如实标注「可选、不给也完全能用」 |
| ④ | 完成页「AI 工作区」行 | 改成「权限可在设置里再改 / 引擎已后台启动」 | 本包工作区走 3081 的 GitHub 镜像，不是 SAF 目录 |

其余（文案、字号、颜色、圆角按钮、进度文字、`已授权 ✓` 固定白字 +
自绘蓝底所以禁用后仍亮蓝、跳过/上一步/下一步、`setup_done`、
返回系统设置后刷新）全部与上游同构。

### 10 步清单（顺序与上游一致）

1. 存储权限 — READ/WRITE_EXTERNAL_STORAGE 运行时申请
2. 所有文件访问 — Android 11+ `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`
3. 悬浮窗 — `ACTION_MANAGE_OVERLAY_PERMISSION`（正好衔接阶段 AE 桌宠修复）
4. 修改系统设置 — `ACTION_MANAGE_WRITE_SETTINGS`
5. 使用情况访问 — `ACTION_USAGE_ACCESS_SETTINGS`
6. 安装未知来源应用 — `ACTION_MANAGE_UNKNOWN_APP_SOURCES`
7. 忽略电池优化 — `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
8. 通知权限 — `POST_NOTIFICATIONS` / 通知设置页
9. Shizuku / Root 特权（可选）
10. 完成页 —「配置完成」+「开始使用」

### 接入点

- `MainActivity.boot()` 末尾 `PermissionWizard.showIfNeeded()`：
  `perm_wizard/setup_done` 为 false 才显示，**只在第一次启动**
- `MainActivity.onResume()` / `onNewIntent()` → `refreshActive()`：
  从系统设置授权回来，状态行变绿、按钮变 `已授权 ✓`
- `onRequestPermissionsResult()` → `onPermissionsResult()`（运行时权限两页）
- `onBackPressed()` → 向导期间消费返回键 = 跳过这页，不退出、不写 done
- 底座原有的 `TotpService.ensureNotificationPermission()` 在向导期间让路，
  避免两个系统权限框叠一起（通知权限由第 8 步统一申请）
- 向导是 edge-to-edge 窗口上的一层，顶部手动让出状态栏高度并切
  `LIGHT_STATUS_BAR`，收起时还原 `savedSysUi`
- `JsBridge.openPermissionWizard()` + 设置页「首次使用权限向导」一行：
  走完后随时可重开逐项改权限（上游控制台「权限」页的等价入口）

### 装完怎么验

覆盖安装 → **完全退出 App 再重进** → 首屏应是
`第 1 / 10 步 · 首次使用 · 配置手机权限 · 存储权限` 白底蓝按钮页，
与截图一致。逐项点「去授权」→ 系统设置开完返回，本页立刻变
「已授权 ✓」；可「跳过这页」；第 10 页点「开始使用」进入 githup。
**走完一次后不再弹**；想再看去「我的 → 设置 → 首次使用权限向导」。

### 验证与边界（如实）

- 已验证：Java 结构、JS 语法、Manifest、包身份、签名、防护链与源码指纹自洽
- **未真机验证** 各 ROM 的授权页跳转与 `POST_NOTIFICATIONS` 在
  targetSdk 28 下的实际弹框行为；每页都可跳过，不会卡死流程
- 第 9 步 Shizuku 页如实标「可选」，本版不提供特权服务端
- 引擎仍在后台无条件启动，向导只是 UI 层，不阻塞 AI 能力
- 之前明文出现过的 `ghp_` 令牌建议立刻撤销；上游 payload 为第三方二进制，
  再分发请确认许可
