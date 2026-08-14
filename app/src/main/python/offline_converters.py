"""Lightweight offline converters that replace pandas-dependent MarkItDown ones.

MarkItDown's built-in XlsxConverter relies on `pandas` (and transitively
`numpy`), which would add ~30 MB per ABI to the APK and have no Android-native
build on some toolchains. `openpyxl` is pure Python and already bundled, so we
re-implement the same behavior -- "every sheet becomes a Markdown table" --
with it.
"""

import html
from typing import Any, BinaryIO

from markitdown._base_converter import DocumentConverter, DocumentConverterResult
from markitdown._stream_info import StreamInfo
from markitdown.converters._html_converter import HtmlConverter

ACCEPTED_MIME_TYPE_PREFIXES = [
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
]
ACCEPTED_FILE_EXTENSIONS = [".xlsx"]


def _cell_to_text(value: Any) -> str:
    """Render a cell value the way pandas/openpyxl would in a Markdown table."""
    if value is None:
        return ""
    if isinstance(value, float) and value.is_integer():
        # pandas prints 89.0 as "89" in tables.
        return str(int(value))
    return str(value)


class XlsxToMarkdownConverter(DocumentConverter):
    """Converts .xlsx files to Markdown, one Markdown table per sheet."""

    def __init__(self):
        super().__init__()
        self._html_converter = HtmlConverter()

    def accepts(
        self,
        file_stream: BinaryIO,
        stream_info: StreamInfo,
        **kwargs: Any,
    ) -> bool:
        extension = (stream_info.extension or "").lower()
        mimetype = (stream_info.mimetype or "").lower()
        if extension in ACCEPTED_FILE_EXTENSIONS:
            return True
        return any(mimetype.startswith(p) for p in ACCEPTED_MIME_TYPE_PREFIXES)

    def convert(
        self,
        file_stream: BinaryIO,
        stream_info: StreamInfo,
        **kwargs: Any,
    ) -> DocumentConverterResult:
        import openpyxl

        wb = openpyxl.load_workbook(file_stream, read_only=True, data_only=True)
        md_parts = []
        try:
            for sheet in wb.worksheets:
                html_rows = []
                first_row = True
                for row in sheet.iter_rows(values_only=True):
                    # Trim trailing empty cells so ragged sheets don't produce
                    # uneven Markdown tables.
                    values = list(row)
                    while values and values[-1] is None:
                        values.pop()
                    if not values:
                        continue

                    cells = []
                    for value in values:
                        text = html.escape(_cell_to_text(value))
                        cells.append(
                            f"<th>{text}</th>" if first_row else f"<td>{text}</td>"
                        )
                    html_rows.append("<tr>" + "".join(cells) + "</tr>")
                    first_row = False

                if not html_rows:
                    continue

                table_html = "<table>" + "".join(html_rows) + "</table>"
                sheet_md = (
                    self._html_converter.convert_string(table_html, **kwargs)
                    .markdown.strip()
                )
                md_parts.append(f"## {sheet.title}\n{sheet_md}")
        finally:
            wb.close()

        return DocumentConverterResult(markdown="\n\n".join(md_parts).strip())
