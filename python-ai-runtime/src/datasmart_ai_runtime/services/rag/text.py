"""RAG 文本处理工具。

RAG 的质量很大程度取决于“文本如何被切块、如何被分词、如何被压缩”。这些步骤如果完全交给框架黑盒，
面试时很容易被追问到答不上来。本文件保留项目自己的轻量实现：

- 中文/英文混合 token 抽取；
- 面向文档的滑窗切块；
- 面向上下文预算的证据压缩；
- 片段摘要和相似度计算。

这些实现不是要替代生产级 tokenizer 或 reranker，而是把核心原理清楚落地，并为后续接入更强模型保留
稳定接口。
"""

from __future__ import annotations

import math
import re
from collections import Counter
from dataclasses import dataclass
from typing import Iterable

from datasmart_ai_runtime.services.rag.models import RagChunk, RagDocument


_SPLIT_PATTERN = re.compile(r"[\s,，、。；;:：/\\|()（）\[\]【】{}<>《》\"'`]+")
_SENTENCE_PATTERN = re.compile(r"(?<=[。！？!?；;])")


def tokenize_for_rag(text: str) -> tuple[str, ...]:
    """抽取适合 RAG 召回的粗粒度 token。

    这里没有直接用英文空格分词，因为 DataSmart 的主要场景包含大量中文治理术语，例如“质量规则”、
    “字段口径”、“权限边界”。实现策略：
    - 先按中英文标点和空白切分；
    - 对较长中文片段额外生成 2-4 字符 n-gram，提高“质量规则/规则生成”这类局部命中的概率；
    - 过滤 1 字符噪音，保留数字、英文和中文混合 token。
    """

    raw_parts = [part.strip().lower() for part in _SPLIT_PATTERN.split(text or "") if part.strip()]
    tokens: list[str] = []
    for part in raw_parts:
        if len(part) >= 2:
            tokens.append(part)
        if _contains_cjk(part) and len(part) >= 4:
            for size in (2, 3, 4):
                tokens.extend(part[index : index + size] for index in range(0, max(len(part) - size + 1, 0)))
    return tuple(token for token in tokens if len(token) >= 2)


def chunk_document(document: RagDocument, *, max_chars: int = 700, overlap_chars: int = 120) -> tuple[RagChunk, ...]:
    """把文档切成带重叠的 chunk。

    切块原则：
    - 先按段落拆分，尽量保持语义完整；
    - 单个段落过长时再滑窗切分；
    - 相邻 chunk 保留少量 overlap，避免答案所需信息刚好被切在边界两侧；
    - chunkId 使用 documentId + 序号，便于幂等更新和引用。
    """

    max_chars = max(200, min(max_chars, 4000))
    overlap_chars = max(0, min(overlap_chars, max_chars // 2))
    paragraphs = [part.strip() for part in re.split(r"\n\s*\n", document.content or "") if part.strip()]
    if not paragraphs:
        return ()

    chunks: list[str] = []
    current = ""
    for paragraph in paragraphs:
        if len(paragraph) > max_chars:
            if current:
                chunks.append(current.strip())
                current = ""
            chunks.extend(_sliding_windows(paragraph, max_chars=max_chars, overlap_chars=overlap_chars))
            continue
        candidate = f"{current}\n\n{paragraph}".strip() if current else paragraph
        if len(candidate) <= max_chars:
            current = candidate
        else:
            chunks.append(current.strip())
            current = _tail_overlap(current, overlap_chars)
            current = f"{current}\n\n{paragraph}".strip() if current else paragraph
    if current:
        chunks.append(current.strip())

    return tuple(
        RagChunk(
            chunk_id=f"{document.document_id}#chunk-{index + 1}",
            document_id=document.document_id,
            chunk_index=index,
            title=document.title,
            text=chunk,
            source_uri=document.source_uri,
            tenant_id=document.tenant_id,
            project_id=document.project_id,
            workspace_key=document.workspace_key,
            source_type=document.source_type,
            tags=document.tags,
            sensitivity_level=document.sensitivity_level,
            metadata=document.metadata,
        )
        for index, chunk in enumerate(chunks)
    )


def compress_chunk_text(text: str, query_terms: Iterable[str], *, max_chars: int) -> str:
    """按问题相关性压缩单个 chunk。

    压缩不是简单从头截断。更好的做法是优先保留包含查询词的句子，再用原文前部补足上下文。这样既能
    控制 prompt 长度，也能提高证据与问题的贴合度。
    """

    max_chars = max(80, max_chars)
    normalized_terms = {term.lower() for term in query_terms if len(term) >= 2}
    sentences = [item.strip() for item in _SENTENCE_PATTERN.split(text or "") if item.strip()]
    selected: list[str] = []
    for sentence in sentences:
        lowered = sentence.lower()
        if any(term in lowered for term in normalized_terms):
            selected.append(sentence)
    if not selected:
        selected = sentences[:2] if sentences else [text.strip()]
    compressed = " ".join(selected).strip()
    if len(compressed) < max_chars // 3 and text:
        compressed = f"{compressed} {text[: max_chars // 2]}".strip()
    return _clip(compressed, max_chars)


def cosine_similarity(left: tuple[float, ...], right: tuple[float, ...]) -> float:
    """计算向量余弦相似度。"""

    if not left or not right or len(left) != len(right):
        return 0.0
    dot = sum(a * b for a, b in zip(left, right))
    left_norm = math.sqrt(sum(value * value for value in left))
    right_norm = math.sqrt(sum(value * value for value in right))
    if left_norm <= 0 or right_norm <= 0:
        return 0.0
    return dot / (left_norm * right_norm)


def jaccard_similarity(left_tokens: Iterable[str], right_tokens: Iterable[str]) -> float:
    """计算 token 集合 Jaccard 相似度，用于 MMR 去冗余。"""

    left = set(left_tokens)
    right = set(right_tokens)
    if not left or not right:
        return 0.0
    return len(left & right) / len(left | right)


@dataclass(frozen=True)
class LexicalScore:
    """词项分数结果。"""

    score: float
    match_terms: tuple[str, ...]


def lexical_score(query_terms: tuple[str, ...], chunk: RagChunk) -> LexicalScore:
    """计算 BM25 风格的轻量词项分。

    这里不完整实现 BM25 的 IDF，因为内存知识库 V1 没有维护全局文档频次。但它仍体现核心思想：
    - 标题命中权重大于正文；
    - 标签命中有额外加分；
    - 同一词重复出现有边际递减；
    - 文本越长，分数会被轻微归一化，避免长文天然占优。
    """

    if not query_terms:
        return LexicalScore(score=0.0, match_terms=())
    title = (chunk.title or "").lower()
    tags = " ".join(chunk.tags).lower()
    body_tokens = Counter(tokenize_for_rag(chunk.text))
    body_length_norm = math.sqrt(max(sum(body_tokens.values()), 1))
    score = 0.0
    matches: set[str] = set()
    for term in set(query_terms):
        term_score = 0.0
        if term in title:
            term_score += 2.0
        if term in tags:
            term_score += 1.5
        if term in body_tokens:
            term_score += min(3.0, 1.0 + math.log1p(body_tokens[term]))
        if term_score > 0:
            matches.add(term)
            score += term_score
    return LexicalScore(score=score / body_length_norm, match_terms=tuple(sorted(matches)))


def _sliding_windows(text: str, *, max_chars: int, overlap_chars: int) -> list[str]:
    """对超长段落做滑窗切块。"""

    windows: list[str] = []
    step = max(1, max_chars - overlap_chars)
    for start in range(0, len(text), step):
        window = text[start : start + max_chars].strip()
        if window:
            windows.append(window)
        if start + max_chars >= len(text):
            break
    return windows


def _tail_overlap(text: str, overlap_chars: int) -> str:
    """返回上一块尾部 overlap 文本。"""

    if overlap_chars <= 0:
        return ""
    return text[-overlap_chars:].strip()


def _clip(text: str, max_chars: int) -> str:
    """裁剪文本并保留截断标记。"""

    normalized = str(text or "").strip()
    if len(normalized) <= max_chars:
        return normalized
    return normalized[:max_chars].rstrip() + "...[TRUNCATED]"


def _contains_cjk(value: str) -> bool:
    """判断字符串中是否包含 CJK 字符。"""

    return any("\u4e00" <= char <= "\u9fff" for char in value)


__all__ = [
    "LexicalScore",
    "chunk_document",
    "compress_chunk_text",
    "cosine_similarity",
    "jaccard_similarity",
    "lexical_score",
    "tokenize_for_rag",
]
