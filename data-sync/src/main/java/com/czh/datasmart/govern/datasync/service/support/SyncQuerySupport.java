/**
 * @Author : Cui
 * @Date: 2026/05/23 10:20
 * @Description DataSmart Govern Backend - SyncQuerySupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * data-sync 查询与文本规整支持组件。
 *
 * <p>这个组件的职责很明确：把“如何分页、如何拼查询条件、如何规整字符串输入”统一收口。
 * 这样做不是为了省几行代码，而是为了让各个业务服务不再各自复制一套查询辅助方法。
 *
 * <p>从商业产品角度看，data-sync 未来会持续扩展：
 * 1. 任务查询会不断增加过滤维度，例如调度方式、告警级别、审批状态和执行器标签；
 * 2. 事故查询会继续增加责任人、值班组、SLA 阶段和处置来源等筛选条件；
 * 3. 模板、执行记录、checkpoint、审计流水等列表接口都需要统一的分页与空值规整规则。
 *
 * <p>因此这类逻辑不应散落在 `Impl` 里，更不应在每个服务中重复抄写，
 * 否则后续只要调整一个分页上限、一个字符串归一化规则，维护者就要在多处同步修改。
 */
@Component
public class SyncQuerySupport {

    private static final long DEFAULT_CURRENT = 1L;
    private static final long DEFAULT_PAGE_SIZE = 20L;
    private static final long MAX_PAGE_SIZE = 100L;

    /**
     * 构建安全分页对象。
     *
     * <p>商业系统里的列表接口不能直接信任前端传入的 current/size：
     * - current 可能为空、0 或负数；
     * - size 可能被恶意放大，导致一次请求拉出过多记录；
     * - 某些兼容性调用会完全不传分页参数。
     *
     * <p>这里统一做兜底，避免每个服务自己重复写一套相同判断。
     *
     * @param current 请求页码
     * @param size 请求页大小
     * @param <T> 分页记录类型
     * @return 安全的 MyBatis Plus 分页对象
     */
    public <T> Page<T> page(Long current, Long size) {
        long safeCurrent = current == null || current <= 0 ? DEFAULT_CURRENT : current;
        long safeSize = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return new Page<>(safeCurrent, safeSize);
    }

    /**
     * 仅在值有效时追加等值查询条件。
     *
     * <p>这个方法专门处理“字符串空白值”和普通空值，避免 `eq(column, "")`
     * 这种请求把数据库查询条件变成难以理解的伪过滤。
     *
     * @param wrapper MyBatis 查询包装器
     * @param column 目标列
     * @param value 需要追加的值
     * @param <T> 实体类型
     * @param <V> 列值类型
     */
    public <T, V> void eqIfPresent(LambdaQueryWrapper<T> wrapper, SFunction<T, V> column, V value) {
        if (wrapper == null || column == null || value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        wrapper.eq(column, value);
    }

    /**
     * 将枚举/状态类字符串统一转为大写。
     *
     * <p>这是 data-sync 很重要的一条兼容线：
     * 前端、网关、脚本调用、内部回调都可能以不同大小写传入状态值。
     * 如果不在服务端统一收口，后面每一张表、每一个接口都要单独兼容。
     *
     * @param value 原始值
     * @return 规整后的值
     */
    public String normalizeCode(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 去除字符串前后空白，空字符串则转换为 null。
     *
     * <p>数据库字段通常不希望看到“空格字符串”和真正的空值混在一起。
     * 这个方法适合用于描述、配置片段、映射 JSON、策略文本等可选字段。
     *
     * @param value 原始文本
     * @return 修剪后的文本，或 null
     */
    public String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 返回默认文本。
     *
     * <p>该方法会先调用 {@link #trimToNull(String)}，因此它同时兼容空字符串和纯空白字符串。
     * 这比在各个服务里写 `value == null || value.isBlank()` 更清晰，也更不容易漏掉边界。
     *
     * @param value 待规整文本
     * @param defaultValue 默认值
     * @return 最终文本
     */
    public String defaultText(String value, String defaultValue) {
        String trimmed = trimToNull(value);
        return trimmed == null ? defaultValue : trimmed;
    }

    /**
     * 安全提取当前操作者 ID。
     *
     * <p>很多业务方法只需要一个 actorId 来写审计、补默认 owner 或判断“是否本人相关”。
     * 这里统一收口后，service 方法可以更专注于领域行为本身。
     *
     * @param actorContext 操作上下文
     * @return actorId，可能为 null
     */
    public Long actorId(SyncActorContext actorContext) {
        return actorContext == null ? null : actorContext.actorId();
    }

    /**
     * 截断超长文本。
     *
     * <p>事故摘要、错误样本、审计载荷这类字段都不适合无限增长，
     * 因为它们往往既要进数据库，也要进入日志、响应体或审计系统。
     * 统一截断可以避免单条记录把后续接口拖慢。
     *
     * @param value 原始文本
     * @param maxLength 最大长度
     * @return 截断后的文本
     */
    public String truncate(String value, int maxLength) {
        if (value == null || maxLength <= 0 || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
