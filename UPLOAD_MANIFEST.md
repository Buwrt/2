# 源码与 APK 上传清单 · Buwrt/2

> 本文件由同步流程写入，用于直接核对「源码和 APK 到底传没传」。
> 同步时间：2026-11-16（北京时间）

## 这个库里有什么

| 类别 | 位置 | 状态 |
|---|---|---|
| 源码 | `app/src/main/`、`tools/`、`apk/`、工作流等（仓库共 531 个文件） | ✅ 已上传 |
| 本版 APK | `apk/githup-v1.2.20.apk.url` → Releases/v1.2.20-merged | ✅ 已上传（Release 附件；文件树放直链指针） |
| 校验清单 | `apk/githup-v1.2.20.apk.sha256`、`version.json` | ✅ 已上传 |
| Release 附件 | Releases → `v1.2.20-merged` | ✅ 已上传 |

## 本版产物

- 版本 `1.2.20` / `versionCode 1002020` / 包名 `com.hubmobile.app`
- APK SHA-256：`12b4f7c8b5cef3a84bbd3971016207d8674ef17a53744ef3717d51434e45349a`
- 源码指纹 `src_sha256`：`da85b2714e60b28128aeeec2733ccd049b9771a3474dbb79d336d007bac72647`
- 最新提交：`605e88772fb5bb9593d5bb812c230e4749945f53`
- 官方签名证书未变，可直接覆盖升级

## 三库关系

- `Buwrt/githup`：公开底座库，源码 + 1.2MB 底座 APK
- `Buwrt/githup-release`：私有构建/签名库，与公开库同源码同包
- `Buwrt/2`：合并版（含 AI 引擎）。154MB 包**超过 GitHub 单文件 100MB 上限**，本体只能挂 Release 附件；文件树里放 `.url` 直链指针 + `.sha256`

## 为什么首页可能显示「几天前」

旧版 App 首页显示的是 GitHub 的 `updated_at`（仓库元数据变更：改描述、被点 Star），**push 代码不会更新它**；本版已改为 `pushed_at`（代码最后推送时间），下拉刷新即可看到「几分钟前」。真实推送时间以本文件上方提交号 / `git log` 为准。

---
*以 `version.json` 为哈希权威；本文件不参与 APK 源码指纹计算。*
