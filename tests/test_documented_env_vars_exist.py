"""Every VORTEX_* variable named in documentation must exist in the source.

Written after a documentation *correction* introduced `VORTEX_PCEP_INSTALL_POLICY`, a variable
that has never existed -- the real one is `VORTEX_PCEP_AUTO_INSTALL`. It appeared twice in the
README and once in a javadoc, so anyone following the instructions would have configured nothing
and seen no error: an unrecognised name is simply absent from the environment, and the controller
starts with the default.

That failure is silent in both directions, which is why it needs a test rather than review
attention. A reader cannot tell a real setting from an invented one, and neither can the process.
"""
import pathlib
import re
import unittest

REPO = pathlib.Path(__file__).resolve().parent.parent
NAME = re.compile(r"VORTEX_[A-Z0-9_]+")

# Documentation and deployment descriptions: anything a reader might copy a name out of.
DOC_GLOBS = ("README.md", "SECURITY.md", "CONTRIBUTING.md", "docs/*.md", "tests/interop/README.md",
             "docker-compose.yml", "docker-entrypoint.sh", ".github/workflows/*.yml")
# Where a name is real: a string literal in code the controller actually runs.
SOURCE_GLOBS = ("src/main/java/**/*.java", "crp_pce/**/*.py", "tools/*.py")


def _names(globs):
    found = {}
    for pattern in globs:
        for path in REPO.glob(pattern):
            if not path.is_file():
                continue
            for name in NAME.findall(path.read_text(encoding="utf-8", errors="replace")):
                found.setdefault(name, set()).add(path.relative_to(REPO).as_posix())
    return found


class DocumentedEnvironmentVariablesExistTest(unittest.TestCase):
    def test_every_documented_variable_is_defined_in_source(self):
        documented = _names(DOC_GLOBS)
        defined = set(_names(SOURCE_GLOBS))
        self.assertTrue(defined, "no VORTEX_* names found in source; the globs are wrong")

        invented = {name: sorted(where) for name, where in documented.items()
                    if name not in defined}
        self.assertEqual({}, invented,
                         "documentation names variables that do not exist in any source file: "
                         + "; ".join(f"{n} ({', '.join(w)})" for n, w in sorted(invented.items())))

    def test_the_guard_catches_an_invented_name(self):
        # The check is only worth having if it fails on the shape that got through, so prove it
        # rather than trusting that an empty diff means the logic ran.
        defined = set(_names(SOURCE_GLOBS))
        self.assertNotIn("VORTEX_PCEP_INSTALL_POLICY", defined,
                         "the invented name is back in source")
        self.assertIn("VORTEX_PCEP_AUTO_INSTALL", defined,
                      "the real automatic-install variable should be defined in source")


if __name__ == "__main__":
    unittest.main()
