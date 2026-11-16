# 源码与 APK 上传清单 · Buwrt/2

> 本文件由同步流程写入，用于直接核对「源码和 APK 到底传没传」。
> 同步时间：2026-11-17（北京时间）

## 这个库里有什么

| 类别 | 位置 | 状态 |
|---|---|---|
| 源码 | `app/src/main/`、`tools/`、`apk/`、工作流等（仓库共 532 个文件） | ✅ 已上传 |
| 本版 APK | `apk/githup-v1.2.20.apk.url` → Releases/v1.2.20-merged | ✅ 已上传（Release 附件；文件树放直链指针） |
| 校验清单 | `apk/githup-v1.2.20.apk.sha256`、`version.json` | ✅ 已上传 |
| Release 附件 | Releases → `v1.2.20-merged` | ✅ 已上传 |

## 本版产物

- 版本 `1.2.20` / `versionCode 1002020` / 包名 `com.hubmobile.app`
- APK SHA-256：`32e91e5f61c4abbd39437964bb7657edce55b1868a27ff505ae77be2b0516384`
- 源码指纹 `src_sha256`：`145cf86f7a4b504ae6cda9352c9150f589e70387d7bca7ba47f9004edbe69a7b`
- 同步基线提交（本清单文件本身是随后一条）：`03eb6e6f3fb55e877171de00a17c13869500eb18`
- 官方签名证书未变，可直接覆盖升级

## 本轮内容

搜索页「用户」页签支持按**数字用户 ID** 查找：`/search/users` 只匹配 login/name/email，纯数字恒 0 条；本版并行走 `GET /user/:id` 精确反查并置顶显示。其它页签输入纯数字时给出「切到用户页签」引导。

## 三库关系

- `Buwrt/githup`：公开底座库，源码 + 1.2MB 底座 APK
- `Buwrt/githup-release`：私有构建/签名库，与公开库同源码同包
- `Buwrt/2`：合并版（含 AI 引擎）。154MB 包**超过 GitHub 单文件 100MB 上限**，本体只能挂 Release 附件；文件树里放 `.url` 直链指针 + `.sha256`

---
*以 `version.json` 为哈希权威；本文件不参与 APK 源码指纹计算。*
