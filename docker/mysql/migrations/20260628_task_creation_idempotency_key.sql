-- DataSmart Govern Backend - task creation idempotency key
--
-- 业务背景：
-- 1. task-management 已经有执行回调幂等表，用于保护 progress/complete/fail/defer 等状态回写；
-- 2. 但“创建任务”本身也是一个真实副作用，跨服务调用一旦发生 HTTP 超时、Kafka 重放、worker 重启或人工补偿，
--    上游可能不知道第一次请求是否已经成功；
-- 3. 如果没有创建幂等键，同一个 Agent command 或 data-quality 治理请求可能重复创建多条 PENDING 任务，
--    后续 worker 会重复执行治理动作，形成商业系统里非常危险的副作用放大。
--
-- 设计说明：
-- - creation_idempotency_key 允许为空，表示保持普通“每次调用都创建新任务”的语义；
-- - 非空时由唯一索引保证同一个创建意图最多落一条 task；
-- - MySQL 唯一索引允许多个 NULL，因此不会影响历史任务和手工连续创建相似任务；
-- - 字段只能保存低敏机器标识，禁止保存 payload、SQL、prompt、样本、模型输出、凭据、内部 URL 或工具参数正文。

USE datasmart_govern;

ALTER TABLE task
    ADD COLUMN creation_idempotency_key VARCHAR(180) NULL
        COMMENT '任务创建幂等键；用于跨服务重试复用同一条任务，禁止保存业务正文、SQL、prompt、样本、凭据或内部 URL'
        AFTER type;

CREATE UNIQUE INDEX uk_task_creation_idempotency_key
    ON task (creation_idempotency_key);
