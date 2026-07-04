"""RAG answer artifact writer tests."""

import hashlib
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.rag import (
    RAG_ANSWER_ARTIFACT_PAYLOAD_POLICY,
    LocalFileRagAnswerArtifactWriter,
    RagAnswerArtifactWriteInput,
    RagCitation,
    RagPipelineResult,
    S3CompatibleRagAnswerArtifactWriter,
    rag_answer_artifact_writer_from_env,
)


class RagAnswerArtifactWriterTest(unittest.TestCase):
    """验证 RAG 正文已经从控制面摘要迁移到受控 artifact。

    这组测试保护的是商业化 RAG 最容易失控的边界：answer、citation snippet 和 compressedContext 必须能落盘，
    但写入结果、worker receipt 和 checkpoint 只能看到 artifactReference/hash/字节数等低敏字段。
    """

    def test_local_writer_stores_answer_body_and_returns_metadata_only_summary(self) -> None:
        """本地 writer 应写入正文 JSON，但 summary 不能泄露正文内容。"""

        with tempfile.TemporaryDirectory() as temp_dir:
            writer = LocalFileRagAnswerArtifactWriter(temp_dir)
            result = _pipeline_result()

            write_result = writer.write(
                write_input=RagAnswerArtifactWriteInput(
                    command_id="cmd-rag-artifact-001",
                    run_id="run-rag-artifact",
                    session_id="session-rag-artifact",
                    query_ref="rag-query:sha256:artifactqueryref",
                    tenant_id="10",
                    project_id="20",
                    workspace_key="30",
                    actor_id="1001",
                    trace_id="trace-rag-artifact",
                ),
                result=result,
            )

            summary = write_result.to_summary()
            serialized_summary = json.dumps(summary, ensure_ascii=False)
            artifact_files = list(Path(temp_dir).rglob("*.json"))
            self.assertEqual(1, len(artifact_files))
            artifact_body = artifact_files[0].read_text(encoding="utf-8")
            artifact_json = json.loads(artifact_body)

            self.assertTrue(write_result.artifact_reference.startswith("agent-artifact:"))
            self.assertEqual(RAG_ANSWER_ARTIFACT_PAYLOAD_POLICY, summary["payloadPolicy"])
            self.assertEqual(hashlib.sha256(artifact_body.encode("utf-8")).hexdigest(), summary["contentSha256"])
            self.assertIn("模型答案正文 SECRET_ANSWER_BODY", artifact_body)
            self.assertIn("压缩上下文 SECRET_COMPRESSED_CONTEXT", artifact_body)
            self.assertIn("引用片段 SECRET_CITATION_SNIPPET", artifact_body)
            self.assertFalse(artifact_json["bodyPolicy"]["questionStored"])
            self.assertNotIn("SECRET_ANSWER_BODY", serialized_summary)
            self.assertNotIn("SECRET_COMPRESSED_CONTEXT", serialized_summary)
            self.assertNotIn("SECRET_CITATION_SNIPPET", serialized_summary)
            self.assertNotIn(str(artifact_files[0]), serialized_summary)

    def test_rejects_unsafe_requested_artifact_reference(self) -> None:
        """上游传入的 artifactReference 不能是 URL、路径或路径逃逸。"""

        with tempfile.TemporaryDirectory() as temp_dir:
            writer = LocalFileRagAnswerArtifactWriter(temp_dir)
            with self.assertRaises(ValueError):
                writer.write(
                    write_input=RagAnswerArtifactWriteInput(
                        command_id="cmd-rag-artifact-002",
                        run_id="run-rag-artifact",
                        session_id="session-rag-artifact",
                        query_ref="rag-query:sha256:artifactqueryref",
                        requested_artifact_reference="https://internal.example.local/rag-answer?token=secret",
                    ),
                    result=_pipeline_result(),
                )

    def test_s3_writer_puts_object_with_java_locator_compatible_key_and_metadata_only_summary(self) -> None:
        """MinIO/S3 writer 应与 Java locator 使用同一 objectName 规则，并且 summary 不泄露对象定位。"""

        fake_client = FakeS3Client()
        writer = S3CompatibleRagAnswerArtifactWriter(
            bucket="datasmart-agent-artifacts",
            s3_client=fake_client,
            object_key_root_prefix="agent-runtime/artifacts",
        )

        write_result = writer.write(
            write_input=RagAnswerArtifactWriteInput(
                command_id="cmd-rag-s3-001",
                run_id="run-rag-s3",
                session_id="session-rag-s3",
                query_ref="rag-query:sha256:s3queryref",
                tenant_id="10",
                project_id="20",
                workspace_key="30",
                actor_id="1001",
            ),
            result=_pipeline_result(),
        )

        self.assertEqual(1, len(fake_client.put_object_calls))
        call = fake_client.put_object_calls[0]
        body_text = call["Body"].decode("utf-8")
        summary_text = json.dumps(write_result.to_summary(), ensure_ascii=False)

        self.assertEqual("datasmart-agent-artifacts", call["Bucket"])
        self.assertTrue(call["Key"].startswith("agent-runtime/artifacts/agent-artifact/run-rag-s3/cmd-rag-s3-001/"))
        self.assertTrue(call["Key"].endswith(".json"))
        self.assertEqual("application/json; charset=utf-8", call["ContentType"])
        self.assertEqual(hashlib.sha256(call["Body"]).hexdigest(), call["Metadata"]["content-sha256"])
        self.assertIn("模型答案正文 SECRET_ANSWER_BODY", body_text)
        self.assertNotIn("datasmart-agent-artifacts", summary_text)
        self.assertNotIn(call["Key"], summary_text)
        self.assertNotIn("SECRET_ANSWER_BODY", summary_text)
        self.assertEqual("minio-s3-compatible-controlled-artifact", write_result.storage_backend)

    def test_s3_writer_rejects_reference_that_java_locator_cannot_map(self) -> None:
        """MinIO/S3 writer 必须拒绝 URL、bucket/key 明文和 Java locator 不接受的 logical path。"""

        fake_client = FakeS3Client()
        writer = S3CompatibleRagAnswerArtifactWriter(
            bucket="datasmart-agent-artifacts",
            s3_client=fake_client,
        )

        with self.assertRaises(ValueError):
            writer.write(
                write_input=RagAnswerArtifactWriteInput(
                    command_id="cmd-rag-s3-unsafe",
                    run_id="run-rag-s3",
                    session_id="session-rag-s3",
                    query_ref="rag-query:sha256:s3queryref",
                    requested_artifact_reference="agent-artifact:bucket/object-key/secret.json",
                ),
                result=_pipeline_result(),
            )

        self.assertEqual(0, len(fake_client.put_object_calls))

    def test_environment_factory_builds_minio_writer_without_exposing_endpoint(self) -> None:
        """环境变量启用 minio backend 时，应装配 S3 writer，诊断信息仍不暴露 endpoint/bucket 明文。"""

        writer = rag_answer_artifact_writer_from_env(
            {
                "DATASMART_RAG_ARTIFACT_WRITER_ENABLED": "true",
                "DATASMART_RAG_ARTIFACT_STORE_BACKEND": "minio",
                "DATASMART_RAG_ARTIFACT_MINIO_ENDPOINT": "http://minio.internal:9000",
                "DATASMART_RAG_ARTIFACT_MINIO_BUCKET": "datasmart-agent-artifacts",
                "DATASMART_RAG_ARTIFACT_MINIO_ACCESS_KEY": "minio-access",
                "DATASMART_RAG_ARTIFACT_MINIO_SECRET_KEY": "minio-secret",
            }
        )

        self.assertIsInstance(writer, S3CompatibleRagAnswerArtifactWriter)
        diagnostics = writer.diagnostics()
        serialized = json.dumps(diagnostics, ensure_ascii=False)
        self.assertTrue(diagnostics["endpointConfigured"])
        self.assertNotIn("http://minio.internal:9000", serialized)
        self.assertNotIn("datasmart-agent-artifacts", serialized)
        self.assertNotIn("minio-access", serialized)
        self.assertNotIn("minio-secret", serialized)


def _pipeline_result() -> RagPipelineResult:
    """构造包含正文级信息的 RAG 结果，用来验证 writer 的正文/摘要边界。"""

    return RagPipelineResult(
        answer="模型答案正文 SECRET_ANSWER_BODY",
        citations=(
            RagCitation(
                citation_id="C1",
                document_id="doc-001",
                chunk_id="chunk-001",
                title="DataSmart RAG 说明",
                source_uri="minio-object:knowledge-base/doc-001/chunk-001",
                snippet="引用片段 SECRET_CITATION_SNIPPET",
                final_score=0.91,
            ),
        ),
        selected_chunks=(),
        compressed_context="压缩上下文 SECRET_COMPRESSED_CONTEXT",
        retrieval_summary={
            "candidateCount": 3,
            "evidenceAcceptedCount": 1,
            "selectedCount": 1,
            "weakEvidenceRejectedCount": 2,
            "payloadPolicy": "LOW_SENSITIVE_RAG_RETRIEVAL_SUMMARY_ONLY",
        },
        model_summary={"provider": "dry-run"},
        generated=True,
    )


class FakeS3Client:
    """极简 S3 client 替身，用来验证 writer 与 boto3/MinIO SDK 的调用契约。"""

    def __init__(self) -> None:
        self.put_object_calls = []

    def put_object(self, **kwargs):
        """记录 put_object 参数；真实 SDK 会把这些参数发送给 MinIO/S3-compatible endpoint。"""

        self.put_object_calls.append(kwargs)
        return {"ETag": '"fake-etag"'}


if __name__ == "__main__":
    unittest.main()
