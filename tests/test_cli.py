"""Command-line configuration tests."""

import unittest

from crp_pce.cli import build_parser


class ServeArgumentsTest(unittest.TestCase):

    def test_serve_defaults_to_loopback(self):
        args = build_parser().parse_args(["serve"])
        self.assertEqual("127.0.0.1", args.host)
        self.assertEqual(4189, args.port)

    def test_serve_accepts_an_explicit_container_bind(self):
        args = build_parser().parse_args(["serve", "--host", "0.0.0.0", "--port", "4190"])
        self.assertEqual("0.0.0.0", args.host)
        self.assertEqual(4190, args.port)


if __name__ == "__main__":
    unittest.main()
