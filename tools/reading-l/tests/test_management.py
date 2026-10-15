"""Exercise file exclusions and actual Git backup/push behavior without network access."""

import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("manage", Path(__file__).parents[1] / "manage.py")
manage = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(manage)


class SourceChecks(unittest.TestCase):
    def test_private_files_are_rejected(self):
        for name in (
            "app/release.jks", "app/DEBUG.KEYSTORE", "local.properties",
            ".env", ".env.production", "app/build/output.txt", ".gradle/cache",
            "build/backup.bundle", "release.apk", "secrets/key.p12", "node_modules/a.js",
        ):
            with self.subTest(name=name):
                self.assertTrue(manage.forbidden_source(name))

    def test_project_dependencies_and_examples_are_allowed(self):
        for name in (
            ".env.example", "gradle/wrapper/gradle-wrapper.jar",
            "third_party/maven/library.jar", "app/src/main/assets/example.json",
            "tools/reading-l/manage.py", ".github/workflows/reading-l-check.yml",
        ):
            with self.subTest(name=name):
                self.assertFalse(manage.forbidden_source(name))

    def test_latest_date_uses_first_entry(self):
        self.assertEqual(
            manage.latest_log_date("# 更新日志\n**2026/10/15**\n* 内容\n**2026/10/14**"),
            "2026/10/15",
        )

    def test_missing_log_date_fails(self):
        with self.assertRaises(manage.ManagementError):
            manage.latest_log_date("# 更新日志\n无日期")


class GitOperations(unittest.TestCase):
    def setUp(self):
        # Keep all temporary checkouts inside the ignored workspace build directory.
        output = manage.ROOT / "build/management-tests"
        output.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=output)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "source"
        self.root.mkdir()
        self.root_patch = patch.object(manage, "ROOT", self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.run_git("init", "-b", "main")
        self.run_git("config", "user.name", "Management tests")
        self.run_git("config", "user.email", "tests@local.invalid")
        (self.root / ".gitignore").write_text("build/\n", encoding="utf-8")
        (self.root / "source.txt").write_text("first\n", encoding="utf-8")
        self.run_git("add", ".")
        self.run_git("commit", "-m", "initial")
        self.run_git("branch", "upstream-source")
        self.run_git("tag", "-a", "test-version", "-m", "version")

    def run_git(self, *args):
        return subprocess.run(
            ["git", *args], cwd=self.root, check=True, capture_output=True
        ).stdout.decode().strip()

    def create_remote(self):
        remote = Path(self.temp.name) / "remote.git"
        self.run_git("init", "--bare", str(remote))
        self.run_git("remote", "add", "origin", str(remote))
        self.run_git("push", "origin", "main", "upstream-source", "--tags")
        return str(remote)

    def test_backup_can_restore_branches_tags_and_source(self):
        report = manage.backup()
        target = Path(self.temp.name) / "restored"
        self.run_git("clone", report["bundle"], str(target))
        self.assertEqual((target / "source.txt").read_text(), "first\n")
        restored_refs = subprocess.check_output(["git", "-C", str(target), "show-ref"]).decode()
        self.assertIn("refs/remotes/origin/upstream-source", restored_refs)
        self.assertIn("refs/tags/test-version", restored_refs)

    def test_backup_rejects_uncommitted_files(self):
        (self.root / "source.txt").write_text("not committed\n")
        with self.assertRaises(manage.ManagementError):
            manage.backup()

    def test_backup_rejects_staged_signature(self):
        (self.root / "private.jks").write_text("fixture")
        self.run_git("add", "private.jks")
        with self.assertRaisesRegex(manage.ManagementError, "签名材料"):
            manage.backup()

    def test_sync_verifies_branches_tags_and_creates_backup(self):
        remote = self.create_remote()
        (self.root / "source.txt").write_text("second\n")
        self.run_git("commit", "-am", "second")
        with patch.object(manage, "ORIGIN", remote), patch.object(manage, "check_project"):
            report = manage.sync()
        self.assertEqual(report["refs_verified"], 3)
        self.assertTrue(Path(report["backup"]["bundle"]).is_file())
        self.assertEqual(
            self.run_git("rev-parse", "HEAD"),
            subprocess.check_output(["git", "--git-dir", remote, "rev-parse", "main"]).decode().strip(),
        )

    def test_sync_rejects_wrong_repository(self):
        self.create_remote()
        with patch.object(manage, "check_project"), self.assertRaisesRegex(
            manage.ManagementError, "origin"
        ):
            manage.sync()

    def test_sync_rejects_different_push_address(self):
        remote = self.create_remote()
        self.run_git("remote", "set-url", "--push", "origin", "https://example.invalid/repo.git")
        with patch.object(manage, "ORIGIN", remote), patch.object(manage, "check_project"):
            with self.assertRaisesRegex(manage.ManagementError, "推送地址"):
                manage.sync()

    def test_sync_rejects_feature_branch(self):
        self.run_git("checkout", "-b", "codex/example")
        with patch.object(manage, "check_project"), self.assertRaisesRegex(
            manage.ManagementError, "main"
        ):
            manage.sync()

    def test_sync_rejects_remote_ahead_without_overwriting_it(self):
        remote = self.create_remote()
        (self.root / "source.txt").write_text("remote change\n")
        self.run_git("commit", "-am", "remote change")
        remote_head = self.run_git("rev-parse", "HEAD")
        self.run_git("push", "origin", "main")
        self.run_git("checkout", "-B", "main", "HEAD~1")
        with patch.object(manage, "ORIGIN", remote), patch.object(manage, "check_project"):
            with self.assertRaises(manage.ManagementError):
                manage.sync()
        self.assertEqual(
            remote_head,
            subprocess.check_output(["git", "--git-dir", remote, "rev-parse", "main"]).decode().strip(),
        )

    def test_tag_conflict_leaves_remote_main_unchanged(self):
        remote = self.create_remote()
        before = self.run_git("rev-parse", "HEAD")
        (self.root / "source.txt").write_text("local change\n")
        self.run_git("commit", "-am", "local change")
        self.run_git("tag", "-f", "-a", "test-version", "-m", "different version")
        with patch.object(manage, "ORIGIN", remote), patch.object(manage, "check_project"):
            with self.assertRaises(manage.ManagementError):
                manage.sync()
        self.assertEqual(
            before,
            subprocess.check_output(["git", "--git-dir", remote, "rev-parse", "main"]).decode().strip(),
        )


if __name__ == "__main__":
    unittest.main()
