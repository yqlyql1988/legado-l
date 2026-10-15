# 阅读L

[![阅读L检查](https://github.com/yqlyql1988/reading-l/actions/workflows/reading-l-check.yml/badge.svg)](https://github.com/yqlyql1988/reading-l/actions/workflows/reading-l-check.yml)

阅读L是基于 [LegadoTeam/legado](https://github.com/LegadoTeam/legado) 的个人 Android 阅读器，源码和发布文件保存在本 GitHub 私有仓库。

保留上游阅读、书源、朗读、漫画、Web 服务、备份与恢复功能；个人版本提供全局书籍信息同步，可在修改书名或作者后更新书签及各设备的阅读记录，保留位置、时长和进度。

## 入口

| 内容 | 位置 |
| --- | --- |
| 代码管理、备份与上游同步 | [CODE_MANAGEMENT.md](CODE_MANAGEMENT.md) |
| 功能开发、测试与 PR 流程 | [CONTRIBUTING.md](CONTRIBUTING.md) |
| 更新记录 | [CHANGELOG.md](CHANGELOG.md) |
| 可安装版本与校验值 | [Releases](https://github.com/yqlyql1988/reading-l/releases) |
| 问题与需求 | [Issues](https://github.com/yqlyql1988/reading-l/issues) |
| 自动检查与测试报告 | [Actions](https://github.com/yqlyql1988/reading-l/actions) |
| 上游项目介绍与鸣谢 | [原 README](docs/UPSTREAM_README.md) |

私有仓库的徽章、问题和发布页面需要登录具有访问权限的 GitHub 账号。

## 精简版本

应用名称：**阅读L**。最低 Android 8.0；包名 `com.legado.app.debug`；ARM64/ARMv7 双架构；保留 Release 代码优化与资源压缩，沿用本机签名以支持覆盖安装。

构建需要 JDK 21、Android SDK 36 和项目依赖：

```powershell
.\gradlew.bat :app:assembleAppRelease `
  --init-script tools/reading-l/slim-release.init.gradle `
  '-ParmOnly=true' '-Pksp.incremental=false'
```

签名密钥、本机路径、APK 和缓存不进入源码历史。换电脑前另行私密备份签名；CI 只测试源码，本机负责使用原签名生成可安装版本。

## 分支与归档

- `main`：个人版本的稳定源码。
- `codex/*`：功能、修复和上游同步的工作分支，通过 PR 合入 `main`。
- `upstream-source`：经过核验和过滤的上游源码快照。
- `reading-l-*` 标签与 Releases：可用版本、安装包、更新说明与 SHA256。

源码继续保留原有历史和版本标签。上游版本记录在 [upstream-state.json](tools/reading-l/upstream-state.json)，后续同步继续合并快照。
