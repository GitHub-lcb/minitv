# admin-flow-ops 移植说明

模块：`modules/ops/flow_router.py` 中后台运营流（分组 / 文案 / Tab / Banner / 热词 / 营销 / 奖励 / 套餐计费 / 评论审核 / 用户 / 财务 / 看板 / 推送 / 法务 / 商店）。
Java 包：`us.minitv.admin.flow.ops`；Mapper XML：`resources/mapper/afflowops_*.xml`。

## 完成接口（45/45）

| 控制器 | 接口 |
|---|---|
| AFFlowOpsGroupsController (8) | POST /admin/v1/groups；GET /admin/v1/groups；PATCH /admin/v1/groups/{id}；POST /admin/v1/groups/{id}/dramas；GET /admin/v1/groups/{id}/dramas；DELETE /admin/v1/groups/{id}/dramas/{drama_id}；PATCH /admin/v1/groups/{id}/dramas/{drama_id}；GET /admin/v1/dramas/{id}/groups |
| AFFlowOpsContentController (16) | POST/GET /admin/v1/copies；GET/POST /admin/v1/tabs；PATCH/DELETE /admin/v1/tabs/{id}；GET/POST /admin/v1/banners；PATCH/DELETE /admin/v1/banners/{id}；POST /admin/v1/hot-searches；POST /admin/v1/marketing/stats；POST/GET /admin/v1/pushes；POST /admin/v1/legal/{slug}；POST /admin/v1/stores |
| AFFlowOpsRewardsController (2) | GET/POST /admin/v1/rewards/config |
| AFFlowOpsBillingController (4) | GET /admin/v1/plans；GET/POST /admin/v1/billing/settings；POST /admin/v1/plans/{code}/price |
| AFFlowOpsModerationController (5) | GET /admin/v1/comments；GET/POST /admin/v1/moderation/settings；POST /admin/v1/moderation/test；POST /admin/v1/comments/{id} |
| AFFlowOpsUsersController (6) | GET /admin/v1/users；PATCH /admin/v1/users/{id}；POST /admin/v1/users/{id}/coins；GET/POST/DELETE /admin/v1/users/{id}/membership |
| AFFlowOpsFinanceController (3) | GET /admin/v1/finance/orders；GET /admin/v1/finance/orders/{order_no}；GET /admin/v1/finance/ledger |
| AFFlowOpsDashboardController (1) | GET /admin/v1/dashboard |

认证：全部走基座 `@CurrentStaff`（等价 `require_admin`），再按 Python 文案补 `require_content_writer`
（`role '<role>' cannot write content`）与 `require_admin_role`（`admin only`）。所有 Controller/Service/
Component/Mapper 类名以 `AFFlowOps` 开头。

行为对齐要点（已逐行照抄）：
- 分组：`GROUP_LIMIT=100`、`written/selected/limit/already_in_group` 预览语义、`not in group`/`group is full` 文案；
  `discover` tab 的 409 `discover tab is fixed` 与 `locked` 计算。
- 奖励：`reward_config_overview` 的 8 个任务目录、次日生效、`current/pending` 结构、`recent_changes` 审计回读。
- 计费：billing_mode 全局开关（400 `unknown billing mode`）、`provision_prices_for_mode` 的逐套餐
  `{code,ok,error|price_id}` 结果、Stripe `/prices` 与 one-time 最低 $0.50 校验、`/subscriptions` 取消调用。
- 会员：`active_membership` 双窗口（current_period_end / pass_end）取 max、`canceling` 语义、
  `membership_detail` 的 source/plan_name/history(6)/last_adjust；grant 的 extend/restart 与 3650 天上限、
  revoke 的 now/period_end 及其全部中文错误文案。
- 审核：Fernet（AES128-CBC+HMAC-SHA256，`v1:` 前缀）与 Python `cryptography` 双向兼容（已实测互解）；
  `key_status`/`mask_key`/`set_api_key`、OpenAI chat/completions 调用及 400 降级重试
  （response_format / max_tokens→max_completion_tokens / temperature）、`decide()` 阈值；
  设置变更审计 payload（before/after/key_action/key_masked/prompt_chars）。
- 看板：`daily_metrics` 快照 upsert；trend/previous/overview/top_dramas 全部由真实事件表计算。
- 金币调整：`wallet_accounts` 行锁 + `coin_ledger`（`biz_type=adjustment`，biz_key 格式同 Python 含 epoch 秒）、
  余额非负 409、审计 payload。
- 订单/看板金额口径：gross=paid+refunded、net=paid、today 按洛杉矶业务日。

## 验证

- `server/tools/check-package.ps1` → `JAVAC_OK (28 files)`。
- 4 个 `afflowops_*.xml` 用 MyBatis `XMLMapperBuilder` 实际解析（含动态分支），7 个 Mapper、115 条
  SQL（含 INSERT/UPDATE/DELETE 与 foreach upsert）在本地 MySQL 8 上全部 `EXPLAIN` 通过（未执行 DML、已 rollback）。
- Fernet：Python 生成 blob → Java 解密成功；Java 生成 blob → Python 解密成功。

## 已知差异

1. 请求模型（Pydantic）校验失败时，Java 端用手写 422 + 字符串 `detail`（或 `@Valid` 的 loc/msg 数组），
   与 FastAPI 的 `detail: [{loc,msg,type}]` 数组形状不是逐字段一致；HTTP 状态码 422 保持一致。
   端点内 `HTTPException(422, "…")` 的字符串 detail（如 `name is required`、`nothing to update`、
   `bad status`、`en is required`）逐字照抄。
2. 时间统一由基座 Jackson 配置序列化为 `...+00:00`（Python 从 MySQL 读出的 naive datetime 无偏移）。
   按 PORTING-GUIDE 第 3 节“直接返回 LocalDateTime”处理。
3. 看板按洛杉矶业务日分桶在 Java 中用 `Times.businessDay()` 完成（PORTING-GUIDE 第 4 节允许），SQL 只按
   UTC 窗口过滤；数据量大时拉到 Java 的行数多于 Postgres `timezone()` 聚合（结果一致）。
4. 审核模型的网络异常文案用 Java 异常名（常见映射为 `ConnectError` / `ReadTimeout`），与 httpx 的类名
   可能不完全一致；`OpenAI <status>: <message>` 与截断/非 JSON 等文案一致。
5. `round(score, 3)` 用 `Math.round`（half-up），Python 为银行家舍入；仅在恰好 .0005 的平局上有差异。
6. 评论列表 / 订单列表用“主表 + 逐行查关联表”替代 JOIN（跨模块表不依赖其他 Agent 的 mapper），
   输出等价，查询次数更多。
7. `suspend` 之外的 Stripe 错误映射：Python `BillingError(502/503)` 文案一致；`_stripe_request` 的
   User-Agent/表单编码一致。
8. `ensure_default_plans()` 未被本模块任何接口调用，未主动播种默认套餐（与 Python 调用链一致）。
9. 未修改 `server/PORTING-ENDPOINTS.md` 的勾选，避免与并行 Agent 同时写该共享文件产生覆盖冲突。
10. 未实现/占位项：无（45 个接口全部实现；Stripe 与审核 HTTP 调用均为可运行实现）。

## 文件

Java（28）：AFFlowOpsSupport/Bodies/Fernet/StripeClient、7×Mapper、9×Service（Group/Config/Reward/Billing/
ModerationGateway/Moderation/User/Finance/Analytics）、8×Controller。
XML（4）：afflowops_users / afflowops_finance / afflowops_moderation / afflowops_analytics。
