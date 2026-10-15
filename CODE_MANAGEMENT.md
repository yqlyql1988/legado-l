# 阅读L 代码管理

本项目使用本地 Git 保存源码版本。Git 仓库位于项目的 `.git` 目录。

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

标签用于标记可用版本；历史记录中的源码可随时取回。后续增加功能时继续在 `main` 上保存新提交。

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

该仓库用于备份，GitHub Actions 已停用；源码中的工作流文件仍保留在版本历史中。

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
