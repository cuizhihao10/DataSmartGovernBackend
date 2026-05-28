-- ---------------------------------------------------------------------------
-- task-management：任务草稿转换中间态说明更新
-- ---------------------------------------------------------------------------
-- 背景：
-- 任务草稿从 APPROVED 转换真实 task 时，如果用户重复点击、Agent 超时重试或网关重放请求，
-- 普通“先查状态再创建任务”的流程可能在并发下创建多条真实任务。
--
-- 本迁移不新增字段，只更新 status 字段注释，把 CONVERTING 纳入草稿状态机说明：
-- APPROVED -> CONVERTING -> CONVERTED。
-- 应用层会通过条件更新抢占 CONVERTING，只有抢占成功的事务才允许创建真实任务。
-- ---------------------------------------------------------------------------

USE datasmart_govern;

ALTER TABLE task_draft
    MODIFY status VARCHAR(32) NOT NULL DEFAULT 'DRAFT'
        COMMENT '草稿状态：DRAFT、PENDING_APPROVAL、APPROVED、CONVERTING、REJECTED、CONVERTED；CONVERTING 用于并发转换门闩';
