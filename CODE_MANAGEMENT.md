# 阅读L 代码管理

本项目使用本地 Git 和 GitHub 私有仓库共同管理源码、功能分支、PR、问题、测试与版本发布。Git 仓库位于项目的 `.git` 目录，个人仓库为 `yqlyql1988/reading-l`。

## 当前管理方式（2026-10-15）

沿用原有提交历史和版本标签，补齐与上游类似的开发管理流程：

| 内容 | 阅读L约定 |
| --- | --- |
| 稳定源码 | `main`，GitHub 默认分支 |
| 功能开发 | 从 `main` 建立 `codex/*`，完成验证后通过 PR 合入 |
| 上游源码 | `upstream-source`，继续使用已核验快照；不重写历史 |
| 问题与需求 | 使用个人仓库 Issues 的 Bug、功能模板，自动分类 |
| 源码检查 | 推送 `main`、`codex/*` 和提交 PR 时运行“阅读L检查” |
| Android 测试 | Android 代码、资源、Gradle 配置或检查工作流改变时自动执行；也可手动运行 |
| 可用版本 | 使用独立 `reading-l-*` 标签与私有 Releases 保存 APK、SHA256 和发布说明 |
| 本机备份 | 自动验证的 Git bundle；签名、本机配置另行私密备份 |

当前 GitHub 账号方案返回私有仓库分支保护不可用（HTTP 403，要求升级 GitHub Pro 或公开仓库）。项目继续保持私有；使用上述 PR 约定、本地检查及 Actions。此约定并非服务器强制保护，不改变账号付费方案。

开发流程见 `CONTRIBUTING.md`。个人仓库启用“阅读L检查”和上游已有的“Triage labels”分类工作流。其他官方工作流在个人仓库停用，包括官方发布、第三方分发、自动关 Issue 和需要 API 密钥的代码审查；源码文件仍保留，便于继续合并上游。

“阅读L检查”默认只读源码；分类流程仅有 Issues 和 PR 标签写权限。CI 不接收本机签名，原签名精简 APK 在本机生成后归档至私有 Releases。

### 本地管理工具

需 Python 3.10+ 与 Git。在项目目录执行：

```powershell
python tools/reading-l/manage.py check
python -m unittest discover -s tools/reading-l/tests -v
python tools/reading-l/manage.py backup
python tools/reading-l/manage.py sync
```

`check` 校验已跟踪源码的排除规则、各语言与调试资源中的阅读L名称、书籍信息同步入口、精简配置、两份日志日期和上游记录。

`backup` 要求工作区干净，生成带时间戳的 bundle 并执行 `git bundle verify`。`sync` 要求处于 `main`，核对 origin 的获取与推送地址、确认远程分支可以快进后，一次原子推送 `main`、`upstream-source` 和标签，逐一核对远程提交，再生成本机 bundle。不使用强制推送；远程领先、分叉、工作区未提交或推送地址不符时拒绝同步。

### 发布与验证记录

发布前更新两份日志，执行管理检查和适用的 Android 测试，再使用原精简构建命令。用 `Get-FileHash -Algorithm SHA256` 核验 APK，检查应用名称、包名、最低系统版本、双 ARM 架构及签名与既有 APK 一致。

发布说明填写版本名、对应源码提交、测试结果、APK 文件名和 SHA256；将安装包和校验文件附到私有仓库对应标签的 Release。APK 不提交到 Git；每个发布标签固定到该版本，不覆盖旧标签。

## 已保存的版本

| 名称 | 内容 |
| --- | --- |
| `original-source` 标签 | 从用户提供的 `legado-master.zip` 导入的原源码快照 |
| `reading-l-slim-20261014` 标签 | 已完成书籍信息同步功能、名称调整、更新日志并验证过的阅读L源码 |
| `main` 分支 | 后续开发使用的分支 |

原源码快照及当前版本都排除了构建缓存、APK、签名密钥等本机文件。当前标签对应已交付的 Android 8.0 及以上精简构建。

目前 Git 提交者使用本项目专用的“阅读L本地归档”和占位邮箱 `read-l@local.invalid`。使用自己的账号管理时，可仅在本项目内设置：

```powershell
git config --local user.name "你的名字"
git config --local user.email "你的提交邮箱"
```

## 日常保存

每完成一个功能、修复或可用版本，先验证，再保存一次提交。可以直接让代码助手“把这次修改保存为一个 Git 版本”，也可以在项目目录执行：

```powershell
git status
git diff
git add -A
git commit -m "说明这次修改了什么"
```

查看记录、比较当前源码与原源码：

```powershell
git log --oneline --decorate
git diff original-source..main
```

标签用于标记可用版本；历史记录中的源码可随时取回。后续功能在 `codex/*` 分支保存新提交，再通过 PR 合入 `main`。用户明确要求直接保存或紧急修复时，也可以在完成同样验证后提交到 `main`。

## 备份

首次已生成完整 Git 历史备份：

`build/outputs/阅读L源码历史_20261014.bundle`

把该文件复制到另一块磁盘或自己的备份空间，可以保留源码、提交历史、分支和标签。这个备份对应生成时的版本，之后新增提交需要重新生成：

```powershell
git bundle create "build/outputs/阅读L源码历史_最新.bundle" --all
git bundle verify "build/outputs/阅读L源码历史_最新.bundle"
```

恢复到新的文件夹：

```powershell
git clone "备份文件的完整路径.bundle" "新的项目目录"
```

APK 单独保存在 `build/outputs`，不进入源码历史。签名密钥、`local.properties` 和本机配置需要另行私密备份。

云端备份已关联到个人私有仓库 `yqlyql1988/reading-l`，远程名称为 `origin`。

后续修改先保存本地提交，再同步源码与版本标签：

```powershell
git push origin main --tags
```

仓库现同时用于个人开发与备份，GitHub Actions 按上面的个人工作流启用范围管理。源码中的官方工作流继续保留在版本历史中。

## 同步上游更新

原仓库为 `LegadoTeam/legado`，上游分支为 `master`，本机远程名称为 `upstream`。自己的开发和云端备份继续使用 `main` 和 `origin`。

用户提供的原源码 ZIP 对应上游提交 `5198645f0a504b1f6d1259885317e056ff6a4a02`。2026-10-15 已合入上游提交 `f5467141c4c5d668b24319b0701a28011e255236`，共包含 11 次上游提交；同步前版本保存在 `reading-l-before-upstream-20261015` 标签。

本项目从 ZIP 建立 Git 历史，使用 `upstream-source` 分支保存经过文件核验的上游源码快照，并将快照合并进 `main`。快照沿用源码备份的排除规则。原源码标签、个人功能和精简构建脚本都保留在历史中。

下次同步时，先读取 `tools/reading-l/upstream-state.json` 确认上次合入的上游版本及快照提交，再获取 `master` 的新版本，以当前快照为基准建立下一份上游快照并合并。检查冲突和自定义功能、完成编译测试后，同时更新两个更新日志和版本记录。

可直接告诉代码助手“同步上游 master 更新，保留我的功能并更新日志”。完成后备份自己的分支及版本标签：

```powershell
git push origin main upstream-source --tags
```

## 2026-10-15 上游同步的构建和验证记录

同步后的精简包为 `build/outputs/阅读L精简版_上游同步20261015.apk`，版本名 `3.26101510`，大小为 22,473,621 字节（21.43 MiB）。沿用 `com.legado.app.debug` 包名及既有本机签名，支持 Android 8.0 及以上，包含 ARM64 和 ARMv7 两种架构。

1973 项单元测试全部通过，包括新增的朗读分段、文字选择手柄、高亮样式及 RSS 视频请求回归测试。实际优化后的 APK 在 MuMu 上另通过 5 项设置、书籍信息同步、数据库记录及阅读翻页测试。原有备份和恢复代码保持一致。

APK SHA256：

`AF8011FA271F38BE8688B05DA8876040743AF2B2A05621615B20ECC4263AA021`

构建、测试及核验报告保存在 `build/upstream-sync`；同步前后两份混淆映射也在该目录保留，便于分析相应版本的崩溃日志。

## 2026-10-14 精简版的构建和验证记录

应用名称为“阅读L”，全局设置位于“我的 → 其他设置 → 书籍信息同步”，提供同步、不同步、每次提醒，默认每次提醒。修改书名或作者后，可同步对应书签和各设备阅读记录，保留位置、时长和进度。更新日志位于 `CHANGELOG.md` 及 `app/src/main/assets/updateLog.md`。

个人精简构建使用源码管理内的 `tools/reading-l/slim-release.init.gradle`，配合项目原有的 Release 代码优化、资源压缩及 `-ParmOnly=true`。在已配置 JDK、Android SDK 和 Gradle 依赖的环境中，可从项目目录执行：

```powershell
.\gradlew.bat :app:assembleAppRelease `
  --init-script tools/reading-l/slim-release.init.gradle `
  '-ParmOnly=true' '-Pksp.incremental=false'
```

该初始化脚本将最低系统版本设为 API 26，使用 `com.legado.app.debug` 包名及本机调试签名。默认产物为 `app/build/outputs/apk/app/release/app-app-release.apk`。换电脑构建时，应配置新的本机 SDK 路径，并保留自己使用的签名文件。

2026-10-14 的交付包为 22,456,245 字节（21.42 MiB），仅包含 `arm64-v8a` 和 `armeabi-v7a`。SHA256 为：

`F6E5B18DCD219C481DC0CE4850221718E42ECFC4EE621572CDAFD1F06B8C342B`

功能开发时通过 1954 项单元测试、27 项 MuMu 设备测试；优化后的交付包另通过 5 项界面及数据库验证，包括同步选项、提示处理、书签和各设备记录保留，以及正文显示和翻页后进度保存。

本机完整验证报告保存在 `build/book-metadata-sync-verification/verification.md`。其中的临时缓存、日志及依赖镜像配置留在本机，不作为源码提交。
