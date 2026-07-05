/**
 * @Author : Cui
 * @Date: 2026/07/05 14:52
 * @Description DataSmart Govern Backend - SyncOfflineRunnerAdapterRegistry.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 专用离线 Runner adapter 注册表。
 *
 * <p>该注册表负责把多个 {@link SyncOfflineRunnerAdapter} 组织成一个可查询的 adapter 列表。
 * 当前阶段仓库还没有接入真实 DataX 引擎，所以生产运行时大概率是空列表；这不是错误，而是明确表示：
 * “合同已经可以识别需要专用 Runner 的场景，但尚未注册可承接该合同的真实执行器”。调度门面会在这种情况下继续
 * fail-closed，避免把多对象、自定义 SQL、checkpoint 等场景错误交给最小 run-once。</p>
 *
 * <p>后续接入真实 runner 时，只需要新增一个实现了 {@link SyncOfflineRunnerAdapter} 的 Spring Bean。
 * 例如 DataX adapter 可以根据合同中的 readerFamily、writerFamily、shardPlan.requiredRunnerCapabilities 判断是否支持，
 * 再把请求转换成受控 DataX job 并通过回调推进 execution 状态。</p>
 */
@Component
public class SyncOfflineRunnerAdapterRegistry {

    /**
     * 已注册 adapter 列表。
     *
     * <p>使用不可变副本避免运行时被外部代码修改，保证 worker loop 每次选择 adapter 时看到的是稳定集合。</p>
     */
    private final List<SyncOfflineRunnerAdapter> adapters;

    public SyncOfflineRunnerAdapterRegistry(List<SyncOfflineRunnerAdapter> adapters) {
        this.adapters = adapters == null ? List.of() : List.copyOf(adapters);
    }

    /**
     * 为指定合同选择第一个可承接 adapter。
     *
     * <p>当前选择策略是“按 Spring 注入顺序选择第一个 supports=true 的 adapter”。这已经足够支撑第一阶段闭环。
     * 后续如果出现多个 runner 竞争同一类合同，可以升级为带优先级、容量、租户配额和维护窗口感知的选择策略。</p>
     *
     * @param contract 低敏 Runner 合同。
     * @return 可承接该合同的 adapter；不存在则为空。
     */
    public Optional<SyncOfflineRunnerAdapter> select(SyncOfflineRunnerJobContract contract) {
        if (contract == null || adapters.isEmpty()) {
            return Optional.empty();
        }
        return adapters.stream()
                .filter(adapter -> safelySupports(adapter, contract))
                .findFirst();
    }

    /**
     * 返回当前注册的 adapter 编码，主要用于测试、诊断和后续只读运维接口。
     */
    public List<String> adapterCodes() {
        return adapters.stream()
                .map(SyncOfflineRunnerAdapter::adapterCode)
                .toList();
    }

    /**
     * 安全执行 supports。
     *
     * <p>adapter 的 supports 理论上应该是纯函数，但商业化系统必须防御插件或外部适配器 bug。
     * 如果某个 adapter 在能力判断阶段抛异常，注册表不会让整个 worker loop 崩溃，而是跳过该 adapter，
     * 后续可通过专门的 adapter 健康检查或启动期校验暴露问题。</p>
     */
    private boolean safelySupports(SyncOfflineRunnerAdapter adapter, SyncOfflineRunnerJobContract contract) {
        if (adapter == null) {
            return false;
        }
        try {
            return adapter.supports(contract);
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
