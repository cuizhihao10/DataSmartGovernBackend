# data-sync 可观测性与告警 Runbook

## 1. 文档定位

本文档记录 data-sync 模块当前已经接入 Prometheus/Grafana 体系的指标、告警规则和排障思路。

data-sync 不是普通 CRUD 服务，它会持续执行同步任务、维护执行器租约、写入 checkpoint、处理重复回调、清理历史运行数据。真正商用时，运营人员不仅要知道“任务成功或失败”，还要知道：

- 自动恢复是否还在正常运行；
- 幂等保护是否因为清理失败而持续膨胀；
- checkpoint、错误样本、审计记录和事故记录是否正在积压；
- 执行器崩溃、网络隔离、目标端限流时系统是否能及时发现并进入人工介入。

因此，本 runbook 的目标是把代码中的 Micrometer 指标转化为可理解、可告警、可运营的生产能力。

## 2. Prometheus 抓取关系

Prometheus 配置文件：`docker/prometheus/prometheus.yml`

data-sync 抓取目标：

```yaml
- job_name: 'data-sync'
  static_configs:
    - targets: ['host.docker.internal:8086']
  metrics_path: '/actuator/prometheus'
```

为什么要显式抓取 data-sync：

- data-sync 的自动恢复、幂等清理和运行数据清理指标都在 data-sync 进程内产生；
- observability 模块本身不会自动代理其他服务指标；
- Prometheus 必须直接抓取 `data-sync:/actuator/prometheus`，Grafana 和告警规则才能查询这些时间序列。

## 3. Grafana 自动化配置

Grafana provisioning 文件：

- 数据源：`docker/grafana/provisioning/datasources/prometheus.yml`
- Dashboard provider：`docker/grafana/provisioning/dashboards/dashboards.yml`
- data-sync 总览看板：`docker/grafana/dashboards/data-sync-overview.json`

docker-compose 挂载关系：

```yaml
- ./docker/grafana/provisioning:/etc/grafana/provisioning
- ./docker/grafana/dashboards:/etc/grafana/dashboards
```

为什么 dashboard 挂载到 `/etc/grafana/dashboards`：

- `/var/lib/grafana` 已经挂载为 Grafana 数据卷，用于保存插件、会话和运行态数据；
- 如果把 dashboard 子目录挂在 `/var/lib/grafana/dashboards`，容易和父级数据卷产生遮挡或启动顺序问题；
- `/etc/grafana/dashboards` 更适合作为“随代码版本管理的只读 dashboard 配置目录”。

当前 data-sync 总览看板包含：

- data-sync 服务可用性；
- 过期租约自动恢复最近一轮扫描、恢复、转人工介入数量；
- 自动恢复 10 分钟失败次数；
- 自动恢复 15 分钟转人工介入数量；
- 自动恢复 15 分钟处理量；
- 运行数据 6 小时分类型清理量；
- 幂等清理和运行数据清理 15 分钟失败次数；
- 自动恢复、幂等清理、运行数据清理距离最近一次成功的秒数。

## 4. 指标命名与低基数原则

当前 data-sync 指标遵循低基数原则。指标标签只包含固定枚举，例如：

- `result=EMPTY/RECOVERED/ATTENTION_REQUIRED/SCANNED_ONLY/FAILED`
- `outcome=SCANNED/REQUEUED/ATTENTION_REQUIRED`
- `reason=REENTRY`
- `phase=SCHEDULER`
- `data_type=CHECKPOINT/ERROR_SAMPLE/AUDIT_RECORD/CLOSED_INCIDENT`

不要把以下字段放入 Prometheus 标签：

- `tenantId`
- `taskId`
- `executionId`
- `traceId`
- `idempotencyKey`
- `datasourceId`

原因是这些字段会随着租户、任务和执行次数持续增长，形成高基数时间序列，严重时会拖垮 Prometheus 存储和 Grafana 查询。

## 5. 自动恢复指标

核心指标：

- `datasmart_data_sync_recovery_tick_total`
- `datasmart_data_sync_recovery_execution_total`
- `datasmart_data_sync_recovery_failure_total`
- `datasmart_data_sync_recovery_skip_total`
- `datasmart_data_sync_recovery_tick_duration_seconds`
- `datasmart_data_sync_recovery_last_scanned`
- `datasmart_data_sync_recovery_last_recovered`
- `datasmart_data_sync_recovery_last_attention_required`
- `datasmart_data_sync_recovery_last_success_epoch_seconds`
- `datasmart_data_sync_recovery_last_failure_epoch_seconds`

建议 Grafana 面板：

- 最近一轮扫描到的过期租约数量：`datasmart_data_sync_recovery_last_scanned`
- 最近一轮恢复回队列数量：`datasmart_data_sync_recovery_last_recovered`
- 最近一轮转人工介入数量：`datasmart_data_sync_recovery_last_attention_required`
- 10 分钟恢复失败次数：`increase(datasmart_data_sync_recovery_failure_total[10m])`
- 15 分钟重入跳过次数：`increase(datasmart_data_sync_recovery_skip_total{reason="REENTRY"}[15m])`
- 自动恢复耗时 P95：使用 `datasmart_data_sync_recovery_tick_duration_seconds_bucket` 计算 histogram 分位数；如果当前注册表输出为 summary/timer 变体，则按实际 Prometheus 暴露名称调整。

运营解读：

- `last_scanned` 持续为 0：可能系统很健康，也可能没有任务运行；需要结合任务队列和 worker 指标判断。
- `last_recovered` 偶尔大于 0：通常表示 worker 崩溃、网络抖动或节点重启后系统自动修复，属于正常自愈。
- `last_attention_required` 大于 0：说明任务已经超过最大退避次数，需要人工排查，不能只依赖系统继续重试。
- `reentry skip` 持续出现：说明恢复任务执行耗时超过调度间隔，应考虑缩小 batch、增加索引、延长 fixed-delay 或增加分布式调度。

## 6. 幂等清理指标

核心指标：

- `datasmart_data_sync_idempotency_cleanup_tick_total`
- `datasmart_data_sync_idempotency_cleanup_deleted_total`
- `datasmart_data_sync_idempotency_cleanup_failure_total`
- `datasmart_data_sync_idempotency_cleanup_skip_total`
- `datasmart_data_sync_idempotency_cleanup_duration_seconds`
- `datasmart_data_sync_idempotency_cleanup_last_deleted`
- `datasmart_data_sync_idempotency_cleanup_last_retention_days`
- `datasmart_data_sync_idempotency_cleanup_last_expire_before_epoch_seconds`
- `datasmart_data_sync_idempotency_cleanup_last_success_epoch_seconds`
- `datasmart_data_sync_idempotency_cleanup_last_failure_epoch_seconds`

运营解读：

- `last_deleted` 长期等于 0：可能没有过期记录，也可能保留期过长；需要结合表行数判断。
- `last_deleted` 长期接近 batch-size：说明清理速度追不上写入速度，可以缩短 fixed-delay、临时提高 batch-size，或考虑归档。
- `failure_total` 增长：优先检查 `data_sync_callback_idempotency` 表索引、删除权限、SQL 方言和锁等待。

## 7. 运行数据清理指标

核心指标：

- `datasmart_data_sync_operational_cleanup_tick_total`
- `datasmart_data_sync_operational_cleanup_deleted_total{data_type=...}`
- `datasmart_data_sync_operational_cleanup_failure_total`
- `datasmart_data_sync_operational_cleanup_skip_total`
- `datasmart_data_sync_operational_cleanup_duration_seconds`
- `datasmart_data_sync_operational_cleanup_last_total_deleted`
- `datasmart_data_sync_operational_cleanup_last_success_epoch_seconds`
- `datasmart_data_sync_operational_cleanup_last_failure_epoch_seconds`

建议 Grafana 面板：

- 各类运行数据 6 小时删除量：`increase(datasmart_data_sync_operational_cleanup_deleted_total[6h])`
- 最近一轮总删除量：`datasmart_data_sync_operational_cleanup_last_total_deleted`
- 最近成功时间距现在秒数：`time() - datasmart_data_sync_operational_cleanup_last_success_epoch_seconds`
- 清理失败次数：`increase(datasmart_data_sync_operational_cleanup_failure_total[15m])`

运营解读：

- `data_type=CHECKPOINT` 删除量很高：说明同步任务 checkpoint 写入频率较高，后续可能需要 checkpoint 合并、按分区保留最新 N 条或引入冷归档。
- `data_type=ERROR_SAMPLE` 删除量很高：说明任务失败样本很多，需要检查字段映射、目标库写入、源端坏数据和采样策略。
- `data_type=AUDIT_RECORD` 删除量很高：说明操作频率或系统自动动作很多，需要评估审计保留期和冷归档。
- `data_type=CLOSED_INCIDENT` 删除量很高：说明历史事故较多，需要结合 incident 类型复盘系统性稳定性问题。

## 8. 告警规则文件

规则文件：`docker/prometheus/rules/data-sync-alerts.yml`

当前告警覆盖：

- `DataSyncServiceDown`
- `DataSyncPrometheusTargetMissing`
- `DataSyncRecoverySchedulerSilent`
- `DataSyncRecoveryFailureDetected`
- `DataSyncRecoveryReentrySkipped`
- `DataSyncRecoveryAttentionRequired`
- `DataSyncIdempotencyCleanupSilent`
- `DataSyncIdempotencyCleanupFailureDetected`
- `DataSyncOperationalCleanupSilent`
- `DataSyncOperationalCleanupFailureDetected`
- `DataSyncOperationalCleanupBacklogHigh`

告警等级建议：

- `critical`：服务不可抓取、目标缺失，这意味着基本观测链路断裂。
- `warning`：自动恢复失败、人工介入增长、清理任务失败或长时间未成功运行。
- `info`：重入跳过、清理量较高等需要观察但不一定立即中断业务的现象。

## 9. Alertmanager 路由

Alertmanager 配置文件：`docker/alertmanager/alertmanager.yml`

Prometheus 到 Alertmanager 的转发配置位于 `docker/prometheus/prometheus.yml`：

```yaml
alerting:
  alertmanagers:
    - static_configs:
        - targets:
            - 'alertmanager:9093'
```

当前路由策略：

- `critical`：进入 `platform-critical`，分组等待 10 秒，30 分钟重复提醒一次。
- `warning`：进入 `platform-warning`，分组等待 30 秒，2 小时重复提醒一次。
- `info`：进入 `platform-info`，分组等待 2 分钟，12 小时重复提醒一次。
- 默认路由：进入 `platform-null`，用于未匹配告警的安全兜底。

当前接收器为什么是空接收器：

- 本地开发环境不应该误发真实短信、电话、企业微信或钉钉通知；
- 仓库中不应提交真实 webhook、token、邮箱密码等密钥；
- 先把路由、分组、抑制和严重级别固化下来，后续接真实通知渠道时只需要替换 receiver 配置。

抑制规则：

- 同一 `module + category` 下，如果已经有 `critical` 告警，则抑制 `warning` 和 `info`。
- 同一 `module + category` 下，如果已经有 `warning` 告警，则抑制 `info`。

这样做是为了避免根因告警和衍生告警同时刷屏。例如 data-sync 服务不可抓取时，恢复调度静默、清理调度静默都可能随后触发；此时优先处理服务不可用这个根因即可。

生产接入建议：

- `platform-critical`：接电话、短信、PagerDuty 或核心值班群，要求分钟级响应。
- `platform-warning`：接模块值班群、工单系统或邮件，要求按 SLA 响应。
- `platform-info`：接低优先级通知、日报或仅保留在 Alertmanager/Grafana 中观察。
- 所有真实 webhook、SMTP 密码和 token 都应通过环境变量、密钥管理系统或部署平台注入，不应写入仓库。

## 10. 通知模板与真实渠道接入

通知模板文件：`docker/alertmanager/templates/datasmart-notification.tmpl`

模板用途：

- `datasmart.alert.title`：统一告警标题，适合邮件 subject、企业 IM 标题或工单标题。
- `datasmart.alert.text`：统一告警正文，包含状态、严重级别、模块、类别、摘要、说明、告警列表和处理建议。
- `datasmart.webhook.payload`：预留给自研告警网关或工单系统的 JSON 格式载荷。

示例接收器文件：`docker/alertmanager/notification-channels.example.yml`

为什么示例文件不自动加载：

- Alertmanager 不支持把多个 receiver 文件自动合并成一个完整配置；
- 示例文件包含 webhook、SMTP 等真实渠道结构，不应该在本地开发环境默认启用；
- 生产接入前需要根据客户部署方式选择环境变量、Secret、Vault、Nacos 加密配置或部署平台密钥注入。

真实渠道接入步骤：

1. 从 `notification-channels.example.yml` 复制一个 receiver 示例到 `alertmanager.yml` 的 `receivers` 下。
2. 将 `platform-critical`、`platform-warning` 或 `platform-info` 的 receiver 内容替换为真实渠道。
3. 使用部署平台注入 webhook URL、SMTP 密码或 token，不要把密钥写入仓库。
4. 在测试环境触发一条低优先级告警，确认标题、正文、分组、恢复通知和抑制规则符合预期。
5. 再逐步接入 critical 通道，避免一开始就把未调好的规则打到真实值班群。

建议通知渠道：

- `critical`：电话、短信、PagerDuty、核心值班群、工单系统 P1。
- `warning`：模块值班群、工单系统 P2/P3、邮件。
- `info`：低优先级机器人、日报聚合、仅 Grafana/Alertmanager 展示。

## 11. 后续增强方向

后续可以继续补：

- Grafana dashboard 继续增强变量、行分组、runbook 链接和告警跳转；
- Alertmanager 接入真实通知渠道，把 critical/warning/info 分发到不同值班组，并补充密钥注入文档；
- 租户级或连接器级业务视图，但不要直接把租户 ID 放到 Prometheus 标签，可考虑定期聚合表；
- 清理历史落库，支持运营台查看每轮删除量和失败原因；
- 冷归档链路，把长期审计和错误样本归档到 MinIO 或数据仓库；
- OpenTelemetry trace，把同步任务、执行器回调和数据源访问串成端到端链路。
