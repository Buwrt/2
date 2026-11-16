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
- APK SHA-256：`ed4841bafe4bcddee9e9f0f3352b0d9afcdd57956b9be00754778d445f8ba414`
- 源码指纹 `src_sha256`：`5ca83fede6a30ee642de0c49fb9de73179a6a11bf45f7c6814e6c9c603b00897`
- 同步基线提交（本清单文件本身是随后一条）：`acbe03f964713f22276ec5b695903630ca96c129`
- 官方签名证书未变，可直接覆盖升级

## 本轮内容

议题 #36 后续：新建议题的「议题分类」改为**默认合拢的一行折叠按钮**，点击向下展开卡片列表、再点收起；合拢时按钮上常显当前已选（含仓库关闭空白议题时自动套用的第一个模板）。选中后保持展开以便连点切换，换分类仍会撤掉上一次自动骨架。纯数字用户 ID 搜索仍走 ID 专用通道（`GET /user/:id` + `/users/:num`），任意存在的账号 ID 均可精确反查。

## 三库关系

- `Buwrt/githup`：公开底座库，源码 + 1.2MB 底座 APK
- `Buwrt/githup-release`：私有构建/签名库，与公开库同源码同包
- `Buwrt/2`：合并版（含 AI 引擎）。154MB 包**超过 GitHub 单文件 100MB 上限**，本体只能挂 Release 附件；文件树里放 `.url` 直链指针 + `.sha256`

---
*以 `version.json` 为哈希权威；本文件不参与 APK 源码指纹计算。*
