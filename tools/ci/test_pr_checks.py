import importlib.util
from pathlib import Path
import tempfile
import subprocess
import textwrap
import shutil
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("pr_checks", Path(__file__).with_name("pr_checks.py"))
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)


class RoutingTest(unittest.TestCase):
    def test_docs_and_screenshots_skip_android(self):
        self.assertTrue(checks.documentation_only(["README.md", "ROADMAP.md", "tools/README.md", "docs/DEVICE_TESTING.md", "docs/images/home.png"]))

    def test_unknown_code_and_ci_always_require_android(self):
        for path in ("app/build.gradle.kts", "app/src/main/Foo.kt", ".github/workflows/pr-checks.yml",
                     "tools/ci/pr_checks.py", "gradle.properties", "docs/run.sh", "new-file", "software-audio/CMakeLists.txt"):
            self.assertFalse(checks.documentation_only(["README.md", path]), path)
        self.assertFalse(checks.documentation_only([]))

    def test_renamed_code_keeps_deleted_path_and_uses_exact_shas(self):
        with patch.object(checks.subprocess, "check_output", return_value=b"app/old.kt\0docs/old.md\0") as command:
            paths = checks.changed_paths("a" * 40, "b" * 40)
            self.assertFalse(checks.documentation_only(paths))
            self.assertIn("--no-renames", command.call_args.args[0])
        with self.assertRaises(ValueError):
            checks.changed_paths("main", "b" * 40)


class DocumentationTest(unittest.TestCase):
    def test_links_images_and_external_urls_without_network(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "docs").mkdir()
            (root / "docs/a b.md").write_text("# Example")
            (root / "README.md").write_text('[Local](docs/a%20b.md#example) [Web](https://example.invalid) [Here](#here)')
            self.assertEqual([], checks.check_docs(["README.md"], root))

    def test_missing_image_fails_even_if_only_image_deleted(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "README.md").write_text('<img src="docs/deleted.png">')
            self.assertTrue(checks.check_docs(["docs/deleted.png"], root))

    def test_examples_and_archives_are_not_live_documentation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "docs/history").mkdir(parents=True)
            (root / "docs/history/old.md").write_text('[Old](missing.md)')
            (root / "README.md").write_text('```md\n[Example](not-a-real-path)\n```\n')
            self.assertEqual([], checks.check_docs(["README.md"], root))

    def test_escaping_links_and_invalid_images_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "README.md").write_text('[Bad](../../outside)')
            (root / "image.png").write_bytes(b"not a png")
            self.assertEqual(2, len(checks.check_docs(["image.png"], root)))

    def test_skipped_and_failed_android_cannot_pass_final_gate(self):
        # Validate the literal fail-closed gate without importing YAML dependencies.
        root = Path(__file__).resolve().parents[2]
        text = (root / ".github/workflows/pr-checks.yml").read_text()
        self.assertIn("if: always()", text)
        self.assertIn("true:success)", text)
        self.assertIn("false:skipped)", text)
        self.assertIn('test "$CHANGES_RESULT" = success', text)
        self.assertIn('test "$DOCS_RESULT" = success', text)
        self.assertNotRegex(text, r"\$\{\{\s*secrets\.")
        self.assertNotIn("pull_request_target", text)

    def test_gate_exit_status_for_success_failure_cancellation_and_skips(self):
        root = Path(__file__).resolve().parents[2]
        text = (root / ".github/workflows/pr-checks.yml").read_text()
        script = textwrap.dedent(text.split("        run: |\n", 1)[1])
        bash = shutil.which("bash")
        self.assertIsNotNone(bash)
        for needed in ("true", "false", ""):
            for android in ("success", "failure", "cancelled", "skipped"):
                for docs in ("success", "failure", "cancelled", "skipped"):
                    env = {"CHANGES_RESULT": "success", "DOCS_RESULT": docs,
                           "ANDROID_NEEDED": needed, "ANDROID_RESULT": android}
                    result = subprocess.run([bash, "-c", script], env=env, capture_output=True)
                    expected = docs == "success" and ((needed == "true" and android == "success") or
                                                      (needed == "false" and android == "skipped"))
                    self.assertEqual(expected, result.returncode == 0, env)
        result = subprocess.run([bash, "-c", script], env={"CHANGES_RESULT": "failure", "DOCS_RESULT": "success",
                                "ANDROID_NEEDED": "false", "ANDROID_RESULT": "skipped"}, capture_output=True)
        self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
