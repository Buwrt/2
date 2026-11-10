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
