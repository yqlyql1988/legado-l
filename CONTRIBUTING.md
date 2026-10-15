# 阅读L开发流程

开始工作先阅读 [CODE_MANAGEMENT.md](CODE_MANAGEMENT.md)。本仓库为个人私有开发仓库，上游为 [LegadoTeam/legado](https://github.com/LegadoTeam/legado)。

## 一个改动的完整流程

1. 从最新 `main` 建立 `codex/功能名称` 分支；在 Issues 中记录问题、期望结果和保留要求。
2. 保留“阅读L”名称、书籍信息同步、Android 8.0 最低版本、原包名与签名，以及 ARM64/ARMv7 精简构建。
3. 完成实现，更新 `CHANGELOG.md` 与 `app/src/main/assets/updateLog.md`。
4. 执行配置检查、管理工具测试和改动所需的 Android 测试；涉及安装包时构建精简 APK 并验证。
5. 提交并推送功能分支，向 `main` 创建 PR，填写验证结果；合并后同步本地 `main`、分支和标签，生成已验证的 Git bundle。
6. 可用版本使用独立标签，在私有仓库 Releases 归档 APK、SHA256 与发布说明。签名文件只留在本机。

紧急修复或用户明确要求直接保存时，可在 `main` 完成相同的检查后提交。当前 GitHub 账号方案不支持私有仓库的强制分支保护，因此 PR 是项目约定；检查失败时应先修复。

## 常用检查

需 Python 3.10+、Git；Android 测试还需 JDK 21、Android SDK 36 和已解析的项目依赖。

```powershell
python tools/reading-l/manage.py check
python -m unittest discover -s tools/reading-l/tests -v
.\gradlew.bat :app:testAppDebugUnitTest --init-script tools/reading-l/slim-release.init.gradle --init-script tools/reading-l/unit-tests.init.gradle '-ParmOnly=true' '-Pksp.incremental=false'
```

`check` 检查已暂存或已跟踪的源码。提交前还应查看 `git status`，避免遗漏未跟踪的新源码。云端检查从提交中检出全部文件再次核验。

单元测试脚本仅为继承的 JVM 测试启用 Android API 桩默认返回值，避免 `android.util.Log.d not mocked`；精简 APK 构建仍只使用原精简脚本。本机依赖镜像与缓存配置继续放在忽略的 `build` 目录。

## 上游同步

继续使用 `upstream-source` 快照方式，基准保存在 `tools/reading-l/upstream-state.json`。不得用官方源码整体覆盖个人分支，也不重写已发布历史。将新的上游快照和个人定制合并后，记录上游提交范围、冲突处理与回归结果。

官方工作流原样保留供上游合并比较；个人仓库只启用阅读L检查与问题分类。官方发布、第三方分发、自动关闭问题和付费代码审查不属于个人流程。
