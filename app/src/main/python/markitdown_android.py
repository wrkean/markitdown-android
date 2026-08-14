"""Offline MarkItDown bridge used by the Android app.

Only converters that produce useful output with zero network access are
registered:

  * Documents: PDF, DOCX, PPTX, XLSX, EPUB, ZIP, Outlook .msg
  * Text / web: HTML, XML, RSS/Atom, CSV, JSON, plain text, IPYNB

Converters that are no-ops or that depend on network/cloud services or on
external binaries that don't exist on Android are intentionally disabled:

  * ImageConverter      - needs the `exiftool` binary and/or an LLM API
  * AudioConverter      - needs `ffmpeg` + speech recognition
  * YouTubeConverter    - fetches youtube.com
  * WikipediaConverter  - fetches wikipedia.org
  * BingSerpConverter   - fetches bing.com

MarkItDown's built-in XlsxConverter / XlsConverter are also disabled because
they depend on pandas/numpy. .xlsx is handled instead by the pure-Python
`XlsxToMarkdownConverter` bundled in offline_converters.py; legacy .xls is not
supported.
"""

import io
import os

from markitdown import MarkItDown
from markitdown._stream_info import StreamInfo
from markitdown.converters import (
    AudioConverter,
    BingSerpConverter,
    ImageConverter,
    WikipediaConverter,
    XlsxConverter,
    XlsConverter,
    YouTubeConverter,
)
from offline_converters import XlsxToMarkdownConverter

# Converter classes that must never run in this offline app.
_OFFLINE_BLOCKLIST = (
    AudioConverter,
    BingSerpConverter,
    ImageConverter,
    WikipediaConverter,
    XlsxConverter,        # replaced by XlsxToMarkdownConverter (openpyxl-based)
    XlsConverter,         # no longer supported (pandas removed)
    YouTubeConverter,
)

_md = None


def _get_md():
    """Return a lazily-created, offline-only MarkItDown instance."""
    global _md
    if _md is None:
        _md = MarkItDown(enable_plugins=False)
        # MarkItDown registers all built-in converters; keep only the
        # offline-capable ones. (`_converters` is a private attribute, which
        # is fine because we bundle a pinned copy of the MarkItDown source.)
        _md._converters = [
            reg
            for reg in _md._converters
            if not isinstance(reg.converter, _OFFLINE_BLOCKLIST)
        ]
        # Register the lightweight openpyxl-based .xlsx converter.
        # register_converter() inserts at index 0 with default priority,
        # making it the first converter tried for .xlsx files.
        _md.register_converter(XlsxToMarkdownConverter())
    return _md


def convert_bytes(data, filename):
    """Convert a file given as bytes to Markdown.

    Args:
        data: raw file bytes (a Java ``byte[]`` is converted automatically).
        filename: original file name; the extension is what selects a converter
                  now that content sniffing (magika) is unavailable.

    Returns:
        The Markdown text. Raises if the format is unsupported.
    """
    md = _get_md()
    ext = os.path.splitext(filename)[1]
    try:
        result = md.convert_stream(
            io.BytesIO(bytes(data)),
            stream_info=StreamInfo(extension=ext, filename=filename),
        )
    except Exception:
        import traceback
        traceback.print_exc()   # → logcat python.stderr
        raise RuntimeError(traceback.format_exc()) from None
    return result.text_content
