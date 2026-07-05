/**
 * @Author : Cui
 * @Date: 2026/07/05 16:19
 * @Description DataSmart Govern Backend - SyncObjectMappingExecutionContract.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.util.List;

/**
 * 多对象映射内部执行契约。
 *
 * <p>该契约用于把模板中的 {@code objectMappingConfig} 从“不透明 JSON 字符串”转换成 data-sync 可以安全消费的结构。
 * 它仍然属于内部执行契约：普通 API、审计摘要和指标只应该使用 mappingCount、issueCodes 等低敏摘要，
 * 不应该回显对象名、字段映射覆盖或配置正文。</p>
 *
 * <p>商业化产品为什么需要这个契约：多表/多对象同步不能靠 runner 临时解析 JSON 和猜测表清单。
 * 控制面必须在执行前先完成语法校验、对象名安全校验、数量上限校验和字段映射覆盖识别，
 * 否则真实生产环境里会出现“用户勾选了很多表，但执行器只跑了第一张表”或“脏配置进入真实读写”的风险。</p>
 *
 * @param parseable JSON 是否可以被解析为受支持结构。
 * @param executableBySerialFanOut 是否可以由当前最小串行 fan-out 执行。
 * @param mappingCount 显式对象映射数量。
 * @param mappings 可执行对象映射条目。内部使用，不能直接暴露。
 * @param issueCodes 阻断或高风险问题码。
 * @param warnings 非阻断提示。
 * @param payloadPolicy 载荷边界说明。
 */
public record SyncObjectMappingExecutionContract(
        boolean parseable,
        boolean executableBySerialFanOut,
        int mappingCount,
        List<SyncObjectMappingExecutionItem> mappings,
        List<String> issueCodes,
        List<String> warnings,
        String payloadPolicy
) {

    public static final String PAYLOAD_POLICY =
            "INTERNAL_OBJECT_MAPPING_CONTRACT_DO_NOT_EXPOSE_OBJECT_NAMES_OR_MAPPING_BODY";

    public SyncObjectMappingExecutionContract {
        mappings = mappings == null ? List.of() : List.copyOf(mappings);
        issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        payloadPolicy = PAYLOAD_POLICY;
    }
}
