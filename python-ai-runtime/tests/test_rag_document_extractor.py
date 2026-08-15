"""RAG 异构文档安全提取器测试。"""

from __future__ import annotations

import io
import os
import sys
import unittest
import zipfile


ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.rag.document_extractor import (
    RagDocumentExtractionError,
    extract_rag_document_bytes,
)


class RagDocumentExtractorTest(unittest.TestCase):
    """验证多格式语义保留和 OOXML 的拒绝边界。"""

    def test_text_json_jsonl_and_csv_are_normalized(self) -> None:
        """常见结构化文本应保留字段和值，并产生稳定格式标识。"""

        markdown = extract_rag_document_bytes("# 用户手册\r\n\r\n登录步骤\r\n".encode(), ".md")
        json_document = extract_rag_document_bytes(
            '{"timeout":120,"enabled":true,"steps":["校验","执行"]}'.encode(),
            ".json",
        )
        jsonl_document = extract_rag_document_bytes(
            '{"event":"STARTED"}\n{"event":"SUCCEEDED"}\n'.encode(),
            ".jsonl",
        )
        csv_document = extract_rag_document_bytes(
            "task_id,status,batch_size\nT-100,SUCCEEDED,500\n".encode(),
            ".csv",
        )

        self.assertEqual("md", markdown.format_name)
        self.assertEqual("# 用户手册\n\n登录步骤", markdown.content)
        self.assertIn('"timeout": 120', json_document.content)
        self.assertIn("第2条", jsonl_document.content)
        self.assertIn("第2行：第1列=T-100", csv_document.content)

    def test_docx_extracts_paragraphs_and_table_rows(self) -> None:
        """DOCX 应按正文顺序提取标题、说明和表格，不依赖 Office 程序。"""

        document_xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
    <w:p><w:r><w:t>部署手册 DEP-DOCX-731</w:t></w:r></w:p>
    <w:p><w:r><w:t>先执行健康检查，再切换流量。</w:t></w:r></w:p>
    <w:tbl><w:tr>
      <w:tc><w:p><w:r><w:t>检查项</w:t></w:r></w:p></w:tc>
      <w:tc><w:p><w:r><w:t>Kafka 延迟</w:t></w:r></w:p></w:tc>
    </w:tr></w:tbl>
    <w:sectPr/>
  </w:body>
</w:document>""".encode()

        extracted = extract_rag_document_bytes(
            _zip_bytes({"word/document.xml": document_xml}),
            ".docx",
        )

        self.assertEqual("docx", extracted.format_name)
        self.assertIn("部署手册 DEP-DOCX-731", extracted.content)
        self.assertIn("表格行：检查项 | Kafka 延迟", extracted.content)

    def test_xlsx_preserves_sheet_cells_and_formula_without_execution(self) -> None:
        """XLSX 应保留工作表、坐标、共享字符串和公式文本，但不得执行公式。"""

        workbook_xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets><sheet name="任务参数" sheetId="1" r:id="rId1"/></sheets>
</workbook>""".encode()
        relationships_xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="worksheet" Target="/xl/worksheets/sheet1.xml"/>
</Relationships>""".encode()
        shared_strings_xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="2" uniqueCount="2">
  <si><t>任务编码 XLSX-TASK-518</t></si><si><t>batch_size</t></si>
</sst>""".encode()
        sheet_xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>
    <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
    <row r="2"><c r="A2" t="inlineStr"><is><t>成功值</t></is></c><c r="B2"><f>250*2</f><v>500</v></c></row>
  </sheetData>
</worksheet>""".encode()

        extracted = extract_rag_document_bytes(
            _zip_bytes(
                {
                    "xl/workbook.xml": workbook_xml,
                    "xl/_rels/workbook.xml.rels": relationships_xml,
                    "xl/sharedStrings.xml": shared_strings_xml,
                    "xl/worksheets/sheet1.xml": sheet_xml,
                }
            ),
            ".xlsx",
        )

        self.assertEqual(1, extracted.sheet_count)
        self.assertIn("工作表：任务参数", extracted.content)
        self.assertIn("A1=任务编码 XLSX-TASK-518", extracted.content)
        self.assertIn("B2=公式=250*2; 缓存值=500", extracted.content)

    def test_ooxml_rejects_external_relationship_and_zip_traversal(self) -> None:
        """外部关系和 ZIP 路径穿越不能进入解析阶段。"""

        workbook_xml = """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
 <sheets><sheet name="外部" sheetId="1" r:id="rId1"/></sheets></workbook>""".encode()
        external_relationship = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Id="rId1" TargetMode="External" Target="https://example.invalid/data.xlsx"/>
</Relationships>""".encode()
        with self.assertRaisesRegex(RagDocumentExtractionError, "外部关系"):
            extract_rag_document_bytes(
                _zip_bytes(
                    {
                        "xl/workbook.xml": workbook_xml,
                        "xl/_rels/workbook.xml.rels": external_relationship,
                    }
                ),
                ".xlsx",
            )

        with self.assertRaisesRegex(RagDocumentExtractionError, "路径越界"):
            extract_rag_document_bytes(
                _zip_bytes(
                    {
                        "word/document.xml": b"<document/>",
                        "../escaped.xml": b"<escaped/>",
                    }
                ),
                ".docx",
            )

    def test_invalid_encoding_nonstandard_json_and_unsupported_format_are_rejected(self) -> None:
        """模糊编码、非标准 JSON 数值和未授权格式必须 fail-closed。"""

        with self.assertRaisesRegex(RagDocumentExtractionError, "UTF-8"):
            extract_rag_document_bytes(b"\xff\xfe\x00", ".txt")
        with self.assertRaisesRegex(RagDocumentExtractionError, "非标准数值"):
            extract_rag_document_bytes(b'{"score":NaN}', ".json")
        with self.assertRaisesRegex(RagDocumentExtractionError, "格式不受支持"):
            extract_rag_document_bytes(b"payload", ".xlsm")


def _zip_bytes(entries: dict[str, bytes]) -> bytes:
    """构造单元测试所需的最小内存 ZIP。"""

    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, payload in entries.items():
            archive.writestr(name, payload)
    return output.getvalue()


if __name__ == "__main__":
    unittest.main()
