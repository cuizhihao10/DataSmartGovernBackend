"""模型工具结果上下文字段过滤器。

`ModelToolResultFeedbackBuilder` 负责把 Java 控制面工具结果转换成下一轮模型消息。4.35/4.36 已经
完成资源级准入：如果整个输出资源是 `audit_only` 或 workspace 越界，就不会把结构化 result 放入模型。

但真实商业化场景里，经常不是“整个 result 都能看”或“整个 result 都不能看”这么简单。例如：
- 数据源元数据里，表名、字段数量、质量规则数量可以进入模型；
- 样本行、连接串、账号、SQL 原文、敏感字段取值不能进入模型；
- 列表可能很长，只能给模型前 N 项摘要；
- 嵌套对象中某些路径可见，某些路径需要遮蔽。

本文件实现一个轻量、无第三方依赖的字段级过滤器。它不是完整 JSONPath 引擎，而是先支持当前最需要的
路径表达：
- `field`：顶层字段；
- `metadata.tableCount`：点号嵌套字段；
- `tables[].name`：列表中每个对象的字段；
- `tables[]`：整个列表字段。

这样既能满足现阶段“安全字段可进模型、敏感字段被裁剪”的需求，也为后续接入 permission-admin 字段级
策略、工具 schema 的 `sensitive=true`、数据分级分类和完整 JSONPath 引擎保留清晰扩展点。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class ModelResultContextFilterPolicy:
    """工具结果进入模型上下文前的字段过滤策略。

    字段说明：
    - `include_paths`：白名单路径。为空表示先复制整个 result，再执行排除、遮蔽和大小保护；
    - `exclude_paths`：黑名单路径。命中后直接从模型上下文结果中删除；
    - `sensitive_paths`：遮蔽路径。命中后保留字段名，但把值替换为 `***MASKED***`；
    - `max_string_length`：单个字符串允许进入模型的最大长度，避免 SQL、日志或大文本撑爆 token；
    - `max_list_items`：单个列表允许进入模型的最大元素数量，避免样本行、字段列表过长；
    - `max_depth`：递归处理最大深度，避免异常嵌套对象造成过大的上下文和递归风险。
    """

    include_paths: tuple[str, ...] = ()
    exclude_paths: tuple[str, ...] = ()
    sensitive_paths: tuple[str, ...] = ()
    max_string_length: int = 512
    max_list_items: int = 20
    max_depth: int = 8


@dataclass(frozen=True)
class ModelResultContextFilterReport:
    """字段过滤诊断报告。

    这个报告可以安全进入 role=tool payload 或 runtime event，因为它只描述字段路径和过滤动作，不包含
    原始字段值。前端和审计台可以用它解释“哪些字段进入模型、哪些字段被删除/遮蔽/截断”。
    """

    mode: str
    include_paths: tuple[str, ...] = ()
    exclude_paths: tuple[str, ...] = ()
    sensitive_paths: tuple[str, ...] = ()
    missing_paths: tuple[str, ...] = ()
    masked_paths: tuple[str, ...] = ()
    removed_paths: tuple[str, ...] = ()
    truncated_paths: tuple[str, ...] = ()
    output_top_level_keys: tuple[str, ...] = ()

    def to_summary(self) -> dict[str, Any]:
        """转换为 API、事件和模型消息可直接序列化的摘要。"""

        return {
            "mode": self.mode,
            "includePaths": self.include_paths,
            "excludePaths": self.exclude_paths,
            "sensitivePaths": self.sensitive_paths,
            "missingPaths": self.missing_paths,
            "maskedPaths": self.masked_paths,
            "removedPaths": self.removed_paths,
            "truncatedPaths": self.truncated_paths,
            "outputTopLevelKeys": self.output_top_level_keys,
        }


@dataclass(frozen=True)
class ModelResultContextFilterResult:
    """过滤后的模型上下文 result 与诊断报告。"""

    result: dict[str, Any]
    report: ModelResultContextFilterReport


@dataclass
class _MutableFilterReport:
    """过滤过程中的可变诊断收集器。

    对外暴露的 `ModelResultContextFilterReport` 是不可变对象，适合在消息包和事件中传递；内部执行过滤时
    需要逐步追加缺失、遮蔽、删除、截断路径，因此使用一个私有可变结构收集过程信息。
    """

    missing_paths: list[str] = field(default_factory=list)
    masked_paths: list[str] = field(default_factory=list)
    removed_paths: list[str] = field(default_factory=list)
    truncated_paths: list[str] = field(default_factory=list)


class ModelResultContextFilter:
    """根据字段路径策略过滤工具结果。

    过滤顺序刻意固定为：
    1. include：先决定哪些字段有资格进入模型；
    2. exclude：再删除明确禁止的字段；
    3. sensitive：对保留但敏感的字段做遮蔽；
    4. limits：最后执行字符串长度、列表长度和最大深度保护。

    这个顺序贴近企业权限系统的常见语义：白名单决定可见范围，黑名单/敏感策略作为更高优先级保护，
    大小限制作为成本和稳定性保护。
    """

    MASKED_VALUE = "***MASKED***"
    DEPTH_TRUNCATED_VALUE = "***TRUNCATED_BY_DEPTH***"

    def filter(
        self,
        result: dict[str, Any],
        policy: ModelResultContextFilterPolicy,
    ) -> ModelResultContextFilterResult:
        """执行字段过滤并返回过滤后的 result。

        当 `include_paths` 为空时，表示调用方暂未提供字段白名单，此时沿用旧行为：复制整个 result，
        再执行 exclude/sensitive/limits。这样能保持历史兼容，同时允许 Java 控制面逐步补充白名单策略。
        """

        collector = _MutableFilterReport()
        if not result:
            return self._build_result({}, policy, collector, mode="empty")

        if policy.include_paths:
            output: dict[str, Any] = {}
            for path in policy.include_paths:
                self._include_path(result, output, self._parse_path(path), path, collector)
            mode = "include_paths"
        else:
            output = self._safe_copy(result)
            mode = "full_result"

        for path in policy.exclude_paths:
            if self._remove_path(output, self._parse_path(path), path):
                collector.removed_paths.append(path)
            else:
                collector.missing_paths.append(path)

        for path in policy.sensitive_paths:
            if self._mask_path(output, self._parse_path(path), path):
                collector.masked_paths.append(path)
            else:
                collector.missing_paths.append(path)

        limited = self._apply_limits(
            output,
            path="$",
            depth=0,
            policy=policy,
            collector=collector,
        )
        return self._build_result(limited if isinstance(limited, dict) else {}, policy, collector, mode=mode)

    def _build_result(
        self,
        result: dict[str, Any],
        policy: ModelResultContextFilterPolicy,
        collector: _MutableFilterReport,
        *,
        mode: str,
    ) -> ModelResultContextFilterResult:
        """把可变过滤过程收口为不可变结果对象。"""

        report = ModelResultContextFilterReport(
            mode=mode,
            include_paths=policy.include_paths,
            exclude_paths=policy.exclude_paths,
            sensitive_paths=policy.sensitive_paths,
            missing_paths=tuple(dict.fromkeys(collector.missing_paths)),
            masked_paths=tuple(dict.fromkeys(collector.masked_paths)),
            removed_paths=tuple(dict.fromkeys(collector.removed_paths)),
            truncated_paths=tuple(dict.fromkeys(collector.truncated_paths)),
            output_top_level_keys=tuple(result.keys()),
        )
        return ModelResultContextFilterResult(result=result, report=report)

    def _include_path(
        self,
        source: Any,
        target: Any,
        segments: tuple[str, ...],
        original_path: str,
        collector: _MutableFilterReport,
    ) -> bool:
        """按路径把 source 中的字段复制到 target。

        返回值表示路径是否命中。未命中的路径会记录到 `missing_paths`，便于后续发现 Java 控制面或工具
        schema 配置了已经不存在的字段。
        """

        if not segments:
            return False
        segment = segments[0]
        key, wildcard = self._split_segment(segment)
        is_last = len(segments) == 1

        if not isinstance(source, dict) or key not in source:
            collector.missing_paths.append(original_path)
            return False

        value = source[key]
        if wildcard:
            if not isinstance(value, (list, tuple)):
                collector.missing_paths.append(original_path)
                return False
            target_list = self._ensure_target_list(target, key)
            if is_last:
                target[key] = self._safe_copy(value)
                return True
            matched = False
            for index, item in enumerate(value):
                while len(target_list) <= index:
                    target_list.append({})
                if not isinstance(target_list[index], dict):
                    target_list[index] = {}
                matched = self._include_path(item, target_list[index], segments[1:], original_path, collector) or matched
            return matched

        if is_last:
            target[key] = self._safe_copy(value)
            return True
        child_target = self._ensure_target_dict(target, key)
        return self._include_path(value, child_target, segments[1:], original_path, collector)

    def _remove_path(self, current: Any, segments: tuple[str, ...], original_path: str) -> bool:
        """按路径删除字段。"""

        if not segments:
            return False
        segment = segments[0]
        key, wildcard = self._split_segment(segment)
        is_last = len(segments) == 1
        if not isinstance(current, dict) or key not in current:
            return False
        if wildcard:
            value = current.get(key)
            if not isinstance(value, list):
                return False
            if is_last:
                current.pop(key, None)
                return True
            matched = False
            for item in value:
                matched = self._remove_path(item, segments[1:], original_path) or matched
            return matched
        if is_last:
            current.pop(key, None)
            return True
        return self._remove_path(current.get(key), segments[1:], original_path)

    def _mask_path(self, current: Any, segments: tuple[str, ...], original_path: str) -> bool:
        """按路径遮蔽字段值。"""

        if not segments:
            return False
        segment = segments[0]
        key, wildcard = self._split_segment(segment)
        is_last = len(segments) == 1
        if not isinstance(current, dict) or key not in current:
            return False
        if wildcard:
            value = current.get(key)
            if not isinstance(value, list):
                return False
            if is_last:
                current[key] = self.MASKED_VALUE
                return True
            matched = False
            for item in value:
                matched = self._mask_path(item, segments[1:], original_path) or matched
            return matched
        if is_last:
            current[key] = self.MASKED_VALUE
            return True
        return self._mask_path(current.get(key), segments[1:], original_path)

    def _apply_limits(
        self,
        value: Any,
        *,
        path: str,
        depth: int,
        policy: ModelResultContextFilterPolicy,
        collector: _MutableFilterReport,
    ) -> Any:
        """递归应用字符串、列表和深度限制。"""

        if depth > policy.max_depth:
            collector.truncated_paths.append(path)
            return self.DEPTH_TRUNCATED_VALUE
        if isinstance(value, str):
            if len(value) > policy.max_string_length:
                collector.truncated_paths.append(path)
                return value[: policy.max_string_length] + "...(truncated)"
            return value
        if isinstance(value, dict):
            return {
                key: self._apply_limits(
                    item,
                    path=f"{path}.{key}" if path != "$" else key,
                    depth=depth + 1,
                    policy=policy,
                    collector=collector,
                )
                for key, item in value.items()
            }
        if isinstance(value, (list, tuple)):
            items = list(value)
            if len(items) > policy.max_list_items:
                collector.truncated_paths.append(path)
                items = items[: policy.max_list_items]
            return [
                self._apply_limits(
                    item,
                    path=f"{path}[{index}]",
                    depth=depth + 1,
                    policy=policy,
                    collector=collector,
                )
                for index, item in enumerate(items)
            ]
        return value

    def _safe_copy(self, value: Any) -> Any:
        """复制进入模型上下文的值，并把 tuple 归一化为 list。

        不直接使用 `copy.deepcopy` 是为了顺手把 tuple 转换为 JSON 更友好的 list，同时保持实现显式可读。
        """

        if isinstance(value, dict):
            return {key: self._safe_copy(item) for key, item in value.items()}
        if isinstance(value, (list, tuple)):
            return [self._safe_copy(item) for item in value]
        return value

    @staticmethod
    def _parse_path(path: str) -> tuple[str, ...]:
        """把轻量路径表达式解析为片段。"""

        return tuple(segment.strip() for segment in str(path).split(".") if segment.strip())

    @staticmethod
    def _split_segment(segment: str) -> tuple[str, bool]:
        """识别 `tables[]` 这类列表通配片段。"""

        if segment.endswith("[]"):
            return segment[:-2], True
        return segment, False

    @staticmethod
    def _ensure_target_dict(target: dict[str, Any], key: str) -> dict[str, Any]:
        """确保目标对象中存在 dict 子节点。"""

        value = target.get(key)
        if not isinstance(value, dict):
            value = {}
            target[key] = value
        return value

    @staticmethod
    def _ensure_target_list(target: dict[str, Any], key: str) -> list[Any]:
        """确保目标对象中存在 list 子节点。"""

        value = target.get(key)
        if not isinstance(value, list):
            value = []
            target[key] = value
        return value
