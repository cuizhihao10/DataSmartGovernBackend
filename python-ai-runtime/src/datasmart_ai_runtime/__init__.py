"""DataSmart Govern Python AI Runtime。

这个包承载项目后续的智能运行时能力。当前版本先提供不依赖外部框架的核心领域对象、
模型路由、工具规划和 Agent 编排骨架，便于在 Java 控制面稳定后逐步接入 LangGraph、
OpenClaw Runtime、vLLM、SGLang、RAG/GraphRAG 等真实生产组件。
"""

from dataclasses import asdict

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest

__all__ = ["AgentPlan", "AgentRequest", "asdict"]
