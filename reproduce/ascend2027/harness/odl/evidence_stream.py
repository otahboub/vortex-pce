"""Bounded-memory writers for retained benchmark evidence."""
from __future__ import annotations

import gzip
import io
import json
from pathlib import Path
from typing import Any


class CompressedJsonArrayWriter:
    """Write one deterministic gzip-compressed JSON array without retaining its rows."""

    def __init__(self, path: Path, compresslevel: int = 1):
        self.path = path
        self.count = 0
        self._closed = False
        self._file = path.open("wb")
        self._gzip = gzip.GzipFile(
            filename="", mode="wb", fileobj=self._file,
            compresslevel=compresslevel, mtime=0,
        )
        self._text = io.TextIOWrapper(self._gzip, encoding="utf-8", newline="\n")
        self._text.write("[")

    def append(self, value: Any) -> None:
        if self._closed:
            raise RuntimeError("cannot append to closed evidence stream")
        if self.count:
            self._text.write(",")
        json.dump(value, self._text, separators=(",", ":"), sort_keys=True)
        self.count += 1

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        try:
            self._text.write("]\n")
            self._text.close()
        finally:
            self._file.close()

    def __enter__(self) -> "CompressedJsonArrayWriter":
        return self

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        self.close()
