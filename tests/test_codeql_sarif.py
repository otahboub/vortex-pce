"""Tests for the local CodeQL SARIF enforcement gate."""

import json
import io
import pathlib
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from tools.check_codeql_sarif import findings, main, sarif_files  # noqa: E402


def write_sarif(path: pathlib.Path, results: list[dict]) -> None:
    path.write_text(json.dumps({"version": "2.1.0", "runs": [{"results": results}]}))


class CodeqlSarifGateTest(unittest.TestCase):

    def test_a_clean_analysis_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "clean.sarif"
            write_sarif(path, [])
            self.assertEqual([], findings(path))
            with redirect_stdout(io.StringIO()):
                self.assertEqual(0, main([str(path)]))

    def test_any_finding_fails_and_keeps_its_location(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "finding.sarif"
            write_sarif(path, [{
                "ruleId": "java/example",
                "message": {"text": "unsafe value"},
                "locations": [{"physicalLocation": {
                    "artifactLocation": {"uri": "src/Example.java"},
                    "region": {"startLine": 17},
                }}],
            }])
            reported = findings(path)
            self.assertEqual(1, len(reported))
            self.assertIn("java/example at src/Example.java:17", reported[0])
            with redirect_stderr(io.StringIO()):
                self.assertEqual(1, main([str(path)]))

    def test_a_directory_is_searched_recursively(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            nested = root / "nested"
            nested.mkdir()
            write_sarif(nested / "result.sarif", [])
            self.assertEqual([nested / "result.sarif"], sarif_files([str(root)]))

    def test_missing_or_malformed_output_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            with redirect_stderr(io.StringIO()):
                self.assertEqual(2, main([str(root)]))
            malformed = root / "bad.sarif"
            malformed.write_text("not-json")
            with redirect_stderr(io.StringIO()):
                self.assertEqual(2, main([str(malformed)]))


if __name__ == "__main__":
    unittest.main()
