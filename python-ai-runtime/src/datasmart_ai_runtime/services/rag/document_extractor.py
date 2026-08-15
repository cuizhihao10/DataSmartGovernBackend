"""RAG 异构文档的受限文本提取器。

企业知识通常分散在 Word、Excel、JSON、CSV、日志和普通文本中。RAG 入库前必须先把这些
文件转换成稳定文本，但“能打开文件”不等于“可以安全执行文件”。本模块遵守以下边界：

1. 只读取本地字节，不访问网络，也不解析外部链接；
2. DOCX/XLSX 只读取 OOXML 压缩包中的文本 XML，不执行宏、公式或嵌入对象；
3. 对文件大小、ZIP 条目数、解压后大小、行列数和最终字符数设置硬上限；
4. 所有格式都返回规范化 UTF-8 文本，便于后续切块、哈希和引用审计。

这里使用 Python 标准库实现基础格式，避免 RAG Runtime 仅为了读取办公文档就引入重量级依赖。
生产上传入口仍应在 Gateway 层完成 MIME 检查、病毒扫描、权限校验和对象存储落盘；本模块只负责
已通过上传治理后的内容提取。
"""

from __future__ import annotations

import csv
import io
import json
import re
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any
from xml.etree import ElementTree


RAG_DOCUMENT_EXTRACTION_VERSION = "datasmart.rag-document-extraction.v1"
SUPPORTED_RAG_DOCUMENT_SUFFIXES = frozenset(
    {".md", ".txt", ".log", ".sql", ".csv", ".tsv", ".json", ".jsonl", ".docx", ".xlsx"}
)

_TEXT_SUFFIXES = frozenset({".md", ".txt", ".log", ".sql"})
_MAX_FILE_BYTES = 16 * 1024 * 1024
_MAX_EXTRACTED_CHARS = 2_000_000
_MAX_ZIP_ENTRIES = 512
_MAX_ZIP_UNCOMPRESSED_BYTES = 64 * 1024 * 1024
_MAX_TABLE_ROWS = 20_000
_MAX_TABLE_COLUMNS = 256
_MAX_CELL_CHARS = 8_000
_XML_FORBIDDEN_MARKERS = (b"<!DOCTYPE", b"<!ENTITY")

_WORD_NAMESPACE = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
_SHEET_NAMESPACE = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
_OFFICE_REL_NAMESPACE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
_PACKAGE_REL_NAMESPACE = "http://schemas.openxmlformats.org/package/2006/relationships"


class RagDocumentExtractionError(ValueError):
    """表示文件格式、大小或内容不满足 RAG 安全提取合同。"""


@dataclass(frozen=True)
class RagExtractedDocument:
    """保存可入库文本和可审计的提取摘要。

    ``content`` 是后续切块的唯一正文；``format_name`` 与 ``media_type`` 用于 Manifest 和诊断；
    ``sheet_count`` 只对 XLSX 有值，帮助测试确认工作簿并非被当作空文本处理。
    """

    content: str
    format_name: str
    media_type: str
    sheet_count: int | None = None


_MEDIA_TYPES = {
    ".md": "text/markdown",
    ".txt": "text/plain",
    ".log": "text/plain",
    ".sql": "application/sql",
    ".csv": "text/csv",
    ".tsv": "text/tab-separated-values",
    ".json": "application/json",
    ".jsonl": "application/x-ndjson",
    ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
}


def extract_rag_document(path: str | Path) -> RagExtractedDocument:
    """读取一个受支持文件并提取稳定文本。

    方法先按磁盘大小拒绝明显超限文件，再读取字节并交给 ``extract_rag_document_bytes``。把读取和
    格式解释拆开后，HTTP 上传入口可以在不创建临时文件的情况下复用同一安全边界，单元测试也能
    直接构造最小 OOXML 字节。
    """

    resolved_path = Path(path)
    try:
        size = resolved_path.stat().st_size
    except OSError as exc:
        raise RagDocumentExtractionError("RAG 文档无法读取。") from exc
    if size > _MAX_FILE_BYTES:
        raise RagDocumentExtractionError("RAG 文档超过允许的原始文件大小。")
    try:
        payload = resolved_path.read_bytes()
    except OSError as exc:
        raise RagDocumentExtractionError("RAG 文档无法读取。") from exc
    return extract_rag_document_bytes(payload, resolved_path.suffix)


def extract_rag_document_bytes(payload: bytes, suffix: str) -> RagExtractedDocument:
    """从字节提取文本，同时确保扩展名、大小和最终字符预算合法。"""

    normalized_suffix = str(suffix or "").strip().lower()
    if normalized_suffix not in SUPPORTED_RAG_DOCUMENT_SUFFIXES:
        raise RagDocumentExtractionError("RAG 文档格式不受支持。")
    if len(payload) > _MAX_FILE_BYTES:
        raise RagDocumentExtractionError("RAG 文档超过允许的原始文件大小。")

    sheet_count: int | None = None
    if normalized_suffix in _TEXT_SUFFIXES:
        content = _decode_utf8(payload)
    elif normalized_suffix in {".csv", ".tsv"}:
        content = _extract_delimited(payload, delimiter="," if normalized_suffix == ".csv" else "\t")
    elif normalized_suffix == ".json":
        content = _extract_json(payload)
    elif normalized_suffix == ".jsonl":
        content = _extract_jsonl(payload)
    elif normalized_suffix == ".docx":
        content = _extract_docx(payload)
    else:
        content, sheet_count = _extract_xlsx(payload)

    normalized_content = _normalize_content(content)
    if not normalized_content:
        raise RagDocumentExtractionError("RAG 文档没有可检索文本。")
    if len(normalized_content) > _MAX_EXTRACTED_CHARS:
        raise RagDocumentExtractionError("RAG 文档提取文本超过允许的字符数。")
    return RagExtractedDocument(
        content=normalized_content,
        format_name=normalized_suffix.removeprefix("."),
        media_type=_MEDIA_TYPES[normalized_suffix],
        sheet_count=sheet_count,
    )


def _decode_utf8(payload: bytes) -> str:
    """只接受 UTF-8/UTF-8 BOM，避免依赖操作系统区域设置产生不同文本。"""

    try:
        return payload.decode("utf-8-sig")
    except UnicodeDecodeError as exc:
        raise RagDocumentExtractionError("RAG 文本文档必须使用 UTF-8 编码。") from exc


def _extract_delimited(payload: bytes, *, delimiter: str) -> str:
    """把 CSV/TSV 规范化为带行号的表格文本，并限制恶意超宽表格。"""

    source = io.StringIO(_decode_utf8(payload), newline="")
    output: list[str] = []
    try:
        for row_number, row in enumerate(csv.reader(source, delimiter=delimiter), start=1):
            if row_number > _MAX_TABLE_ROWS:
                raise RagDocumentExtractionError("RAG 表格超过允许的行数。")
            if len(row) > _MAX_TABLE_COLUMNS:
                raise RagDocumentExtractionError("RAG 表格超过允许的列数。")
            cells = [f"第{index}列={_bounded_cell(value)}" for index, value in enumerate(row, start=1)]
            output.append(f"第{row_number}行：" + " | ".join(cells))
    except csv.Error as exc:
        raise RagDocumentExtractionError("RAG 表格文本无法解析。") from exc
    return "\n".join(output)


def _extract_json(payload: bytes) -> str:
    """解析 JSON 后再稳定序列化，拒绝 NaN/Infinity 等非标准数值。"""

    value = _parse_json(_decode_utf8(payload), context="JSON")
    return json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False)


def _extract_jsonl(payload: bytes) -> str:
    """逐行校验 JSONL，并保留稳定行号供日志式证据引用。"""

    output: list[str] = []
    for line_number, line in enumerate(_decode_utf8(payload).splitlines(), start=1):
        if not line.strip():
            continue
        if line_number > _MAX_TABLE_ROWS:
            raise RagDocumentExtractionError("RAG JSONL 超过允许的记录数。")
        value = _parse_json(line, context=f"JSONL 第 {line_number} 行")
        serialized = json.dumps(value, ensure_ascii=False, sort_keys=True, allow_nan=False)
        output.append(f"第{line_number}条：{serialized}")
    return "\n".join(output)


def _parse_json(value: str, *, context: str) -> Any:
    """使用严格常量钩子解析 JSON，防止非标准浮点值进入向量语料。"""

    def reject_constant(constant: str) -> None:
        raise RagDocumentExtractionError(f"RAG {context} 包含非标准数值。")

    try:
        return json.loads(value, parse_constant=reject_constant)
    except RagDocumentExtractionError:
        raise
    except (json.JSONDecodeError, RecursionError) as exc:
        raise RagDocumentExtractionError(f"RAG {context} 无法解析。") from exc


def _extract_docx(payload: bytes) -> str:
    """按 Word 正文顺序提取段落和表格，不读取宏、批注或外部对象。"""

    with _open_safe_ooxml(payload) as archive:
        document_xml = _read_zip_member(archive, "word/document.xml")
    root = _parse_xml(document_xml, context="DOCX 主文档")
    body = root.find(f".//{{{_WORD_NAMESPACE}}}body")
    if body is None:
        raise RagDocumentExtractionError("RAG DOCX 缺少正文节点。")

    blocks: list[str] = []
    for child in body:
        if child.tag == f"{{{_WORD_NAMESPACE}}}p":
            paragraph = _word_paragraph_text(child)
            if paragraph:
                blocks.append(paragraph)
        elif child.tag == f"{{{_WORD_NAMESPACE}}}tbl":
            for row in child.findall(f"{{{_WORD_NAMESPACE}}}tr"):
                cells = []
                for cell in row.findall(f"{{{_WORD_NAMESPACE}}}tc"):
                    paragraphs = [
                        _word_paragraph_text(item)
                        for item in cell.findall(f".//{{{_WORD_NAMESPACE}}}p")
                    ]
                    cells.append(_bounded_cell(" / ".join(item for item in paragraphs if item)))
                if cells:
                    blocks.append("表格行：" + " | ".join(cells))
    return "\n".join(blocks)


def _word_paragraph_text(paragraph: ElementTree.Element) -> str:
    """提取一个 Word 段落，保留制表符和软换行的可读语义。"""

    fragments: list[str] = []
    for element in paragraph.iter():
        if element.tag == f"{{{_WORD_NAMESPACE}}}t" and element.text:
            fragments.append(element.text)
        elif element.tag == f"{{{_WORD_NAMESPACE}}}tab":
            fragments.append("\t")
        elif element.tag in {f"{{{_WORD_NAMESPACE}}}br", f"{{{_WORD_NAMESPACE}}}cr"}:
            fragments.append("\n")
    return "".join(fragments).strip()


def _extract_xlsx(payload: bytes) -> tuple[str, int]:
    """提取 XLSX 的工作表名称、单元格坐标、公式和缓存值。

    公式只作为文本证据保存，绝不计算；外部工作簿关系会被安全 ZIP 校验和关系目标检查拒绝。
    单元格坐标被保留后，RAG 引用可以回答“哪个工作表、哪个参数”而不只得到一串失去结构的值。
    """

    with _open_safe_ooxml(payload) as archive:
        workbook_root = _parse_xml(
            _read_zip_member(archive, "xl/workbook.xml"),
            context="XLSX 工作簿",
        )
        relationships = _xlsx_relationships(archive)
        shared_strings = _xlsx_shared_strings(archive)
        output: list[str] = []
        sheet_count = 0
        for sheet in workbook_root.findall(f".//{{{_SHEET_NAMESPACE}}}sheet"):
            sheet_count += 1
            if sheet_count > 128:
                raise RagDocumentExtractionError("RAG XLSX 超过允许的工作表数量。")
            name = str(sheet.attrib.get("name") or f"Sheet{sheet_count}").strip()
            relationship_id = sheet.attrib.get(f"{{{_OFFICE_REL_NAMESPACE}}}id")
            target = relationships.get(str(relationship_id or ""))
            if target is None:
                raise RagDocumentExtractionError("RAG XLSX 工作表关系缺失。")
            sheet_root = _parse_xml(_read_zip_member(archive, target), context="XLSX 工作表")
            output.append(f"工作表：{name}")
            row_count = 0
            for row in sheet_root.findall(f".//{{{_SHEET_NAMESPACE}}}row"):
                row_count += 1
                if row_count > _MAX_TABLE_ROWS:
                    raise RagDocumentExtractionError("RAG XLSX 工作表超过允许的行数。")
                rendered_cells = []
                cells = row.findall(f"{{{_SHEET_NAMESPACE}}}c")
                if len(cells) > _MAX_TABLE_COLUMNS:
                    raise RagDocumentExtractionError("RAG XLSX 工作表超过允许的列数。")
                for position, cell in enumerate(cells, start=1):
                    reference = str(cell.attrib.get("r") or f"C{position}")
                    rendered = _xlsx_cell_text(cell, shared_strings)
                    if rendered:
                        rendered_cells.append(f"{reference}={_bounded_cell(rendered)}")
                if rendered_cells:
                    output.append(" | ".join(rendered_cells))
        if sheet_count == 0:
            raise RagDocumentExtractionError("RAG XLSX 没有工作表。")
    return "\n".join(output), sheet_count


def _xlsx_relationships(archive: zipfile.ZipFile) -> dict[str, str]:
    """解析工作表内部关系，只允许指向当前 OOXML 包内的 worksheet XML。"""

    root = _parse_xml(
        _read_zip_member(archive, "xl/_rels/workbook.xml.rels"),
        context="XLSX 工作簿关系",
    )
    relationships: dict[str, str] = {}
    for item in root.findall(f"{{{_PACKAGE_REL_NAMESPACE}}}Relationship"):
        relationship_id = str(item.attrib.get("Id") or "")
        target = str(item.attrib.get("Target") or "").replace("\\", "/")
        if item.attrib.get("TargetMode") == "External":
            raise RagDocumentExtractionError("RAG XLSX 不允许外部关系。")
        # OOXML 允许关系目标写成相对路径（``worksheets/sheet1.xml``），也允许从包根开始的
        # 绝对包路径（``/xl/worksheets/sheet1.xml``）。后者不是操作系统绝对路径，去掉前导
        # 斜杠后仍必须落在 ``xl/worksheets``；不能简单拼接 ``xl``，否则会得到重复目录。
        normalized = (
            PurePosixPath(target.lstrip("/"))
            if target.startswith("/")
            else PurePosixPath("xl") / PurePosixPath(target)
        )
        normalized_parts = []
        for part in normalized.parts:
            if part == "..":
                if not normalized_parts:
                    raise RagDocumentExtractionError("RAG XLSX 关系路径越界。")
                normalized_parts.pop()
            elif part not in {"", "."}:
                normalized_parts.append(part)
        member = "/".join(normalized_parts)
        if not member.startswith("xl/worksheets/") or not member.endswith(".xml"):
            continue
        relationships[relationship_id] = member
    return relationships


def _xlsx_shared_strings(archive: zipfile.ZipFile) -> tuple[str, ...]:
    """读取共享字符串表；没有共享字符串时返回空元组。"""

    if "xl/sharedStrings.xml" not in archive.namelist():
        return ()
    root = _parse_xml(
        _read_zip_member(archive, "xl/sharedStrings.xml"),
        context="XLSX 共享字符串",
    )
    return tuple(
        "".join(node.text or "" for node in item.iter(f"{{{_SHEET_NAMESPACE}}}t"))
        for item in root.findall(f"{{{_SHEET_NAMESPACE}}}si")
    )


def _xlsx_cell_text(cell: ElementTree.Element, shared_strings: tuple[str, ...]) -> str:
    """把一个 XLSX 单元格转换为可检索文本，不执行其中的公式。"""

    cell_type = str(cell.attrib.get("t") or "n")
    formula = cell.findtext(f"{{{_SHEET_NAMESPACE}}}f")
    value = cell.findtext(f"{{{_SHEET_NAMESPACE}}}v")
    if cell_type == "inlineStr":
        value = "".join(
            item.text or "" for item in cell.iter(f"{{{_SHEET_NAMESPACE}}}t")
        )
    elif cell_type == "s" and value is not None:
        try:
            value = shared_strings[int(value)]
        except (ValueError, IndexError) as exc:
            raise RagDocumentExtractionError("RAG XLSX 共享字符串索引非法。") from exc
    elif cell_type == "b" and value is not None:
        value = "是" if value == "1" else "否"
    elif cell_type == "e" and value is not None:
        value = f"错误:{value}"
    if formula:
        return f"公式={formula}; 缓存值={value or ''}"
    return str(value or "").strip()


def _open_safe_ooxml(payload: bytes) -> zipfile.ZipFile:
    """打开并审计 OOXML ZIP，阻断路径穿越、加密条目和异常解压规模。"""

    try:
        archive = zipfile.ZipFile(io.BytesIO(payload))
    except (zipfile.BadZipFile, OSError) as exc:
        raise RagDocumentExtractionError("RAG OOXML 文件不是有效 ZIP 包。") from exc
    try:
        entries = archive.infolist()
        if len(entries) > _MAX_ZIP_ENTRIES:
            raise RagDocumentExtractionError("RAG OOXML 文件包含过多 ZIP 条目。")
        uncompressed_total = 0
        for entry in entries:
            path = PurePosixPath(entry.filename.replace("\\", "/"))
            if path.is_absolute() or ".." in path.parts:
                raise RagDocumentExtractionError("RAG OOXML ZIP 条目路径越界。")
            if entry.flag_bits & 0x1:
                raise RagDocumentExtractionError("RAG OOXML 不接受加密 ZIP 条目。")
            uncompressed_total += entry.file_size
            if uncompressed_total > _MAX_ZIP_UNCOMPRESSED_BYTES:
                raise RagDocumentExtractionError("RAG OOXML 解压后大小超过限制。")
    except Exception:
        archive.close()
        raise
    return archive


def _read_zip_member(archive: zipfile.ZipFile, name: str) -> bytes:
    """读取固定 OOXML 成员，并把底层 ZIP 异常转换成稳定业务错误。"""

    try:
        return archive.read(name)
    except (KeyError, RuntimeError, zipfile.BadZipFile) as exc:
        raise RagDocumentExtractionError(f"RAG OOXML 缺少必要成员：{name}") from exc


def _parse_xml(payload: bytes, *, context: str) -> ElementTree.Element:
    """解析禁止 DTD/ENTITY 的 OOXML XML，避免实体扩展或外部实体语义。"""

    upper_payload = payload.upper()
    if any(marker in upper_payload for marker in _XML_FORBIDDEN_MARKERS):
        raise RagDocumentExtractionError(f"RAG {context} 包含禁止的 XML 声明。")
    try:
        return ElementTree.fromstring(payload)
    except ElementTree.ParseError as exc:
        raise RagDocumentExtractionError(f"RAG {context} XML 无法解析。") from exc


def _bounded_cell(value: Any) -> str:
    """规范化单元格文本并限制单格字符数，防止少量巨型单元格占满上下文。"""

    text = re.sub(r"\s+", " ", str(value or "")).strip()
    return text[:_MAX_CELL_CHARS]


def _normalize_content(content: str) -> str:
    """统一换行和尾部空白，同时保留段落与表格的行结构。"""

    normalized_lines = [line.rstrip() for line in content.replace("\r\n", "\n").replace("\r", "\n").split("\n")]
    compact: list[str] = []
    previous_blank = False
    for line in normalized_lines:
        blank = not line.strip()
        if blank and previous_blank:
            continue
        compact.append(line)
        previous_blank = blank
    return "\n".join(compact).strip()


__all__ = [
    "RAG_DOCUMENT_EXTRACTION_VERSION",
    "SUPPORTED_RAG_DOCUMENT_SUFFIXES",
    "RagDocumentExtractionError",
    "RagExtractedDocument",
    "extract_rag_document",
    "extract_rag_document_bytes",
]
