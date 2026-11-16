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
- APK SHA-256：`88865d01f9fda4e94ac9ad55dfced06388e718051085cf64ab8e683b18a67e53`
- 源码指纹 `src_sha256`：`3db6481ef64cb30db8fbed9df26bb3cb08f8df63482a1e8407a41c858af2ffbe`
- 同步基线提交（本清单文件本身是随后一条）：`306be5d82578882b6d0d43b27e1bed7df6c80275`
- 官方签名证书未变，可直接覆盖升级

## 本轮内容

搜索页「用户」页签输入纯数字时，改走 **ID 专用通道**：直接并发 `GET /user/:id`（按 ID，覆盖所有 User / Organization / Bot）与 `GET /users/:num`（按纯数字 login），完全不碰 `/search/users`（恒 0 条且受搜索限流）。任意存在的 GitHub 账号 ID 均可精确反查，结果与行内显示 `#ID`；不存在的明确提示。其它页签输入纯数字时给出「切到用户页签」引导。

## 三库关系

- `Buwrt/githup`：公开底座库，源码 + 1.2MB 底座 APK
- `Buwrt/githup-release`：私有构建/签名库，与公开库同源码同包
- `Buwrt/2`：合并版（含 AI 引擎）。154MB 包**超过 GitHub 单文件 100MB 上限**，本体只能挂 Release 附件；文件树里放 `.url` 直链指针 + `.sha256`

---
*以 `version.json` 为哈希权威；本文件不参与 APK 源码指纹计算。*
