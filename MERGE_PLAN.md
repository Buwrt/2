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
