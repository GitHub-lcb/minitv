# admin-core 移植说明（Python FastAPI -> Java Spring Boot 3 + MyBatis + MySQL）

模块包：`us.minitv.admin.core`（代码在 `server/src/main/java/us/minitv/admin/core/`）
Mapper XML：`server/src/main/resources/mapper/admincore_*.xml`
Python 行为标准：`backend/app/modules/ops/{router,api_key_router,review_router,settlement_router,service,api_keys,settlements,models}.py`、`backend/app/modules/content/service.py`

## 1. 已完成接口（39/39）

### 认证（4）
- POST /admin/v1/auth/login
- GET  /admin/v1/auth/me
- POST /admin/v1/auth/logout
- POST /admin/v1/auth/password

### 员工账号（5）
- GET   /admin/v1/staff
- POST  /admin/v1/staff
- PATCH /admin/v1/staff/{staff_id}
- POST  /admin/v1/staff/{staff_id}/status
- POST  /admin/v1/staff/{staff_id}/password

### 上剧方（6）
- GET   /admin/v1/suppliers
- POST  /admin/v1/suppliers
- PATCH /admin/v1/suppliers/{supplier_id}
- POST  /admin/v1/suppliers/{supplier_id}/status
- POST  /admin/v1/suppliers/{supplier_id}/share
- GET   /admin/v1/suppliers/{supplier_id}/share

### 剧集 / 标签 / 搜索（9）
- GET   /admin/v1/dramas
- POST  /admin/v1/dramas
- GET   /admin/v1/dramas/{drama_id}
- PATCH /admin/v1/dramas/{drama_id}
- GET   /admin/v1/tags
- POST  /admin/v1/tags
- PATCH /admin/v1/tags/{tag_id}
- DELETE /admin/v1/tags/{tag_id}
- GET   /admin/v1/search

### API Key（8）
- GET    /admin/v1/api-keys
- GET    /admin/v1/api-keys/{key_id}/events
- GET    /admin/v1/auth/keys
- POST   /admin/v1/api-keys
- PATCH  /admin/v1/api-keys/{key_id}
- POST   /admin/v1/api-keys/{key_id}/revoke
- POST   /admin/v1/api-keys/{key_id}/rotate
- DELETE /admin/v1/api-keys/{key_id}

### 审核队列（4）
- GET  /admin/v1/reviews
- POST /admin/v1/reviews/batch
- POST /admin/v1/reviews/{drama_id}/decision
- POST /admin/v1/reviews/{drama_id}/publish

### 结算（3）
- GET  /admin/v1/settlements
- POST /admin/v1/settlements/generate
- POST /admin/v1/settlements/{settlement_id}/status

## 2. 文件清单

- controller/ 8 个：AdminCoreAuthController、AdminCoreStaffController、AdminCoreSupplierController、
  AdminCoreDramaController、AdminCoreTagController、AdminCoreApiKeyController、
  AdminCoreReviewController、AdminCoreSettlementController
- service/ 8 个：AdminCoreSessionService、AdminCoreStaffService、AdminCoreSupplierService、
  AdminCoreContentService、AdminCoreApiKeyService、AdminCoreReviewService、
  AdminCoreSettlementService、AdminCoreAuditService
- mapper/ 12 个接口（全部 `AdminCore` 前缀）+ resources/mapper/ 8 个 `admincore_*.xml`
- model/ 3 个联表视图 POJO；AdminCoreSupport（角色门禁/校验/时间工具）
- 全部 `@RestController/@Service` 类名与 `@Mapper` 接口名均带 `AdminCore` 前缀（规范第 8 节）

Mapper XML：admincore_admin_user / admincore_supplier / admincore_tag / admincore_drama /
admincore_api_key / admincore_review / admincore_settlement / admincore_order。
（api_key_events 由基座 `security.ApiKeyEventFilter` 统一写入，本模块只读。）

## 3. 与 Python 的已知差异

1. **邮件通知未实现**：`review_router._notify`（审核通过/驳回后发信）依赖 Python 的 `app.modules.account.mail`；
   Java 侧没有 `spring-boot-starter-mail` 也没有邮件配置，且它本身是 best-effort（SMTP 未配置时 Python 也直接返回）。
   本移植跳过发信，审核状态与审计日志照常写入。**这是唯一未实现的业务副作用。**
2. **422 校验错误 body 形状**：Pydantic 的字段校验错误在 FastAPI 里是 `{"detail":[{type,loc,msg,input}]}` 数组；
   Java 侧按移植规范用 `ApiException.unprocessable(...)` 返回 `{"detail":"..."}` 字符串。
   Python 代码里显式 `HTTPException(422, detail="...")` 的文案（如 "驳回必须填写原因"、密码/角色/账号规则、
   "month must be YYYY-MM"、unknown tag id 等）均逐字一致；纯粹来自 Pydantic 的约束只保证状态码一致。
3. **时间精度**：MySQL 基线用 `DATETIME`（无小数秒），Python 的 PostgreSQL 存微秒；因此响应里的
   `created_at/updated_at/last_login_at/...` 在 Java 侧是秒级（序列化仍是 `...+00:00`）。
4. **列表排序 tie-break**：Python 多数 `ORDER BY` 没有次级排序键，本移植在时间戳相同的场景补了确定性次级键
   （dramas: `updated_at DESC, id DESC`；api-keys: `id DESC`；settlements: `period_start DESC, id DESC`；
   review: `submitted_at DESC NULLS LAST, id DESC`；share history: `effective_from DESC, id DESC`）。
   MySQL `DATETIME` 秒级截断会让同一秒创建的行出现并列，此选择与 Python 中"后插入者时间更晚"的语义一致。
5. **审计日志 payload 中时间格式**：Python `isoformat()` 带微秒（`...123456+00:00`），Java 用 `Times.iso()`（秒级）。
6. **PATCH 请求体的"字段是否存在"语义**：Python 用 Pydantic `exclude_unset`，本移植用 `Map<String,Object>` +
   `containsKey` 复刻（显式 null 与省略的行为已对齐）。POST 请求体用 record，省略字段与显式 null 在少数
   "有默认值的可选字段"上无法区分（如 `display_name` 省略=默认空串、显式 null 在 Python 是 422，
   Java 等同省略）；对 `expires_in_days` 这类有实质区别的字段已专门用 Map 处理（省略=默认 90 天，null=长期）。
7. **用户名/名称大小写比较**：Python 用 PostgreSQL 区分大小写；MySQL 基线是 `utf8mb4_0900_ai_ci`，
   `LIKE`/`=` 大小写不敏感（如 staff/supplier 搜索、username 唯一性）。这是数据库基线的差异，未在代码中"修正"。
8. **校验顺序**：FastAPI 先解析 JSON → 依赖（鉴权/角色）→ Pydantic 字段校验；Spring 先完成参数解析
   （含 @RequestBody 类型转换），再执行方法体中的角色检查。因此"非法 JSON + 无权限"的组合请求
   状态码可能不同（形状/文案见第 2 条），正常请求完全一致。
9. **`tags` 删除检查 / `dramas` 标签关联的更新**：Python 用 `db.rollback()` 后抛错；Java 用事务回滚，
   对外响应一致（409 detail 结构与文案逐字一致）。
10. **`GET /admin/v1/search` 的 `users` 字段**：Python 当前阶段恒为 `[]`，本移植同样返回 `[]`（未接用户搜索）。

## 4. 关键实现说明

- **认证**：全部走基座 `@CurrentStaff`（会话或 `mk_live_` API Key，含 scope/角色/staff_only 校验）；
  细分角色用 `AdminCoreSupport.requireAdminRole / requireContentWriter / requireReviewer / requireMoney`，
  403 文案照抄 Python（"admin only"、"role 'x' cannot write content"、"role cannot review content"、
  "role cannot access settlements"）。
- **会话**：`minitv_admin_session:` + 8h TTL；登录/登出/吊销会话直接复用基座 `RedisStore/PasswordHasher/Tokens`，
  Redis 不可用时返回 503 + Python 原文（含"请先启动 minitv-redis…"整段）。
  改密/重置密码/禁用账号都是"先吊销 Redis 会话，再落库"，与 Python 顺序一致。
- **API Key**：生成/校验/scope 策略复用基座 `security.ApiKeyService`（`mk_live_` 前缀、sha256、
  `read/write/upload/delete/billing/staff`、TTL 30/90/180/365、`keyState`）；本模块实现 CRUD/轮换/撤销/删除
  与审计。明文只在 create/rotate 响应出现一次，审计永不写明文/hash。
- **审计**：所有 Python 里写 `AuditLog` 的动作都在本模块用 `AdminCoreAuditService.record(...)` 落 `audit_logs`
  （actor_type=admin、action/target/payload 与 Python 一致；时间字段用 Python isoformat 等价格式）。
- **状态机**：review/publish 内联复刻 `content/service.py` 的 drama/episode 转移矩阵与
  `illegal drama transition: a -> b` 文案；MySQL 触发器（V3）作为第二道防线。
- **结算**：`orders` 的"每用户首笔已支付未退款订单"用 MySQL 8 窗口函数
  `ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY paid_at, id)` 复刻 Python 的窗口子查询；
  按订单支付时刻匹配 effective-dated `supplier_share_history`，`effective_share_bps` 用金额加权
  （`bps_weight // gross`），与 Python 完全一致；confirmed/paid 单不再重算。
- **JSON 列**：`admin_api_keys.scopes` / `audit_logs.payload` 以 String 读写（`Json`），未做对象映射。
- **请求体命名**：所有 camelCase record 组件用 `@JsonNaming(SnakeCaseStrategy)` 映射 snake_case JSON
  （display_name / supplier_id / old_password / drama_ids ...）。

## 5. 验证

- 类型检查：`& server\tools\check-package.ps1 -Path server\src\main\java\us\minitv\admin\core` -> `JAVAC_OK (32 files)`
- 离线自测（不启动服务、不连库）：
  - Jackson record snake_case 反序列化 OK
  - 8 个 mapper XML 解析 OK（含 DTD/namespace/语句 id 绑定）
  - 12 个 @Mapper 接口共 72 个方法全部有 MappedStatement，72 条语句 `getBoundSql()` 构建 OK
- 未修改本模块以外的任何源码（pom/application.yml/db migration/common/config/security/row/共享 mapper 均未动）。

## 6. 未实现/占位项

- 审核结果的邮件通知（见差异 1），无占位代码，仅跳过。
- `GET /admin/v1/search` 的用户搜索（Python 亦为固定空列表，非占位）。
