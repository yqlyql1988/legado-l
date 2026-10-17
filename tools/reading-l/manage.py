"""阅读L source checks, verified Git backups and repository sync (Python 3.10+)."""

from datetime import datetime
from pathlib import Path, PurePosixPath
import argparse
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ORIGIN = "https://github.com/yqlyql1988/legado-l.git"
BRANCHES = ("main", "upstream-source")
PRIVATE_SUFFIXES = {".apk", ".aab", ".bundle", ".jks", ".keystore", ".p12", ".pfx"}
PRIVATE_FILES = {"local.properties", "keystore.properties", "signing.properties"}
LOCAL_DIRECTORIES = {"build", ".gradle", ".idea", ".kotlin", "node_modules", ".git"}


class ManagementError(RuntimeError):
    pass


def git(*arguments):
    result = subprocess.run(
        ["git", *arguments],
        cwd=ROOT,
        env=dict(os.environ, GIT_TERMINAL_PROMPT="0", GCM_INTERACTIVE="Never"),
        capture_output=True,
        encoding="utf-8",
        errors="replace",
        timeout=180,
    )
    if result.returncode:
        raise ManagementError(result.stderr.strip() or result.stdout.strip())
    return result.stdout.strip()


def forbidden_source(path):
    path = PurePosixPath(path.lower())
    return (
        path.suffix in PRIVATE_SUFFIXES
        or path.name in PRIVATE_FILES
        or (path.name.startswith(".env") and path.name != ".env.example")
        or bool(set(path.parts) & LOCAL_DIRECTORIES)
    )


def check_source_files():
    files = git("ls-files", "-z").split("\0")
    forbidden = [path for path in files if path and forbidden_source(path)]
    if forbidden:
        raise ManagementError("源码中包含本机文件或签名材料：" + ", ".join(forbidden))
    return len([path for path in files if path])


def latest_log_date(text):
    match = re.search(r"^\*\*(\d{4}/\d{2}/\d{2})\*\*", text, re.MULTILINE)
    if not match:
        raise ManagementError("更新日志缺少日期")
    return match[1]


def check_project():
    count = check_source_files()
    resource_files = sorted((ROOT / "app/src").glob("*/res/values*/strings.xml"))
    names = 0
    for resource in resource_files:
        for entry in ET.parse(resource).getroot().findall("string"):
            if entry.get("name") in {"app_name", "app_name_a"}:
                names += 1
                if "".join(entry.itertext()) != "阅读L":
                    raise ManagementError(f"应用名称发生变化：{resource.relative_to(ROOT)}")
    if names < 2:
        raise ManagementError("缺少阅读L应用名称")
    slim = (ROOT / "tools/reading-l/slim-release.init.gradle").read_text(encoding="utf-8")
    for expected in (
        "androidDsl.defaultConfig.minSdk = 26",
        "release.signingConfig = androidDsl.signingConfigs.getByName('debug')",
        "release.applicationIdSuffix = '.debug'",
    ):
        if expected not in slim:
            raise ManagementError("精简构建配置发生变化：" + expected)
    build = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
    for expected in ("minifyEnabled true", "shrinkResources = true",
                     "abiFilters 'arm64-v8a', 'armeabi-v7a'"):
        if expected not in build:
            raise ManagementError("精简构建选项发生变化：" + expected)
    settings = ET.parse(ROOT / "app/src/main/res/xml/pref_config_other.xml")
    android_key = "{http://schemas.android.com/apk/res/android}key"
    if not any(entry.get(android_key) == "bookMetadataSync" for entry in settings.iter()):
        raise ManagementError("缺少书籍信息同步设置")
    dates = [
        latest_log_date((ROOT / path).read_text(encoding="utf-8"))
        for path in ("CHANGELOG.md", "app/src/main/assets/updateLog.md")
    ]
    if dates[0] != dates[1]:
        raise ManagementError("两份更新日志的最新日期不一致")
    state = json.loads((ROOT / "tools/reading-l/upstream-state.json").read_text(encoding="utf-8"))
    for field in ("original_upstream_commit", "synced_upstream_commit", "snapshot_commit"):
        if not re.fullmatch(r"[0-9a-f]{40}", state.get(field, "")):
            raise ManagementError("上游记录中的提交号无效：" + field)
    if state.get("snapshot_branch") != "upstream-source":
        raise ManagementError("上游快照分支发生变化")
    git("merge-base", "--is-ancestor", state["snapshot_commit"], "HEAD")
    return {"tracked_files": count, "app_name": "阅读L", "log_date": dates[0],
            "upstream": state["synced_upstream_commit"], "status": "passed"}


def require_clean():
    if git("status", "--porcelain"):
        raise ManagementError("请先提交或处理工作区和暂存区的修改")


def backup():
    check_source_files()
    require_clean()
    output = ROOT / "build/outputs"
    output.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().astimezone().strftime("%Y%m%d-%H%M%S-%f")
    target = output / f"阅读L源码历史_{stamp}.bundle"
    git("bundle", "create", str(target), "--all")
    git("bundle", "verify", str(target))
    return {"bundle": str(target), "bytes": target.stat().st_size, "status": "verified"}


def sync():
    check_project()
    require_clean()
    if git("branch", "--show-current") != "main":
        raise ManagementError("仅在 main 分支执行同步；功能分支请先通过 PR 合入")
    if git("remote", "get-url", "origin") != ORIGIN:
        raise ManagementError("origin 与阅读L仓库不一致")
    if git("remote", "get-url", "--push", "--all", "origin").splitlines() != [ORIGIN]:
        raise ManagementError("origin 推送地址与阅读L仓库不一致")
    git("fetch", "--no-tags", "origin", *BRANCHES)
    for branch in BRANCHES:
        git("merge-base", "--is-ancestor", f"refs/remotes/origin/{branch}", branch)
    # An atomic push either updates all requested branches and tags or updates none.
    git("push", "--atomic", "origin", *BRANCHES, "--tags")
    local = dict(
        line.split(" ", 1)[::-1]
        for line in git(
            "for-each-ref", "--format=%(objectname) %(refname)",
            "refs/heads/main", "refs/heads/upstream-source", "refs/tags/",
        ).splitlines()
    )
    remote = dict(line.split("\t", 1)[::-1] for line in git("ls-remote", "--refs", "origin").splitlines())
    for ref, sha in local.items():
        if remote.get(ref) != sha:
            raise ManagementError("远程版本核验失败：" + ref)
    return {"repository": ORIGIN, "refs_verified": len(local), "backup": backup(),
            "status": "synced"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("check", "backup", "sync"))
    args = parser.parse_args()
    try:
        report = {"check": check_project, "backup": backup, "sync": sync}[args.command]()
        print(json.dumps(report, ensure_ascii=False, indent=2))
    except (ManagementError, OSError, ValueError, ET.ParseError, subprocess.TimeoutExpired) as error:
        print(f"阅读L：{error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    raise SystemExit(main())
