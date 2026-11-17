# public-account 模块移植说明（us.minitv.paccount）

Python 唯一行为标准：`backend/app/modules/public_flow.py`（认证 / me / 金币 / 奖励 / 任务部分）、
`account/{service,mail,google,facebook,tasks,models}.py`、`wallet/{models,service}.py`、
`ops/config_models.py`（RewardConfig / Checkin / AdClaim / TaskClaim）。

## 已完成接口（17/17）

| # | 接口 | 实现位置 |
|---|---|---|
| 1 | POST /v1/auth/email/code | `PAccountAuthController.emailCode` → `PAccountAuthService.issueCode` |
| 2 | GET /v1/auth/providers | `PAccountAuthController.providers` → `PAccountAuthService.providers` |
| 3 | POST /v1/auth/email/authenticate | `PAccountAuthService.authenticateWithCode` |
| 4 | POST /v1/auth/email/register | `PAccountAuthService.register` |
| 5 | POST /v1/auth/email/login | `PAccountAuthService.login` |
| 6 | POST /v1/auth/google | `PAccountAuthService.loginWithGoogle`（`PAccountGoogleAuth`） |
| 7 | POST /v1/auth/facebook | `PAccountAuthService.loginWithFacebook`（`PAccountFacebookAuth`） |
| 8 | POST /v1/auth/oauth/{provider} | `PAccountAuthService.oauthLogin` |
| 9 | GET /v1/me | `PAccountMeService.me` |
| 10 | PATCH /v1/me | `PAccountMeService.updateProfile` |
| 11 | GET /v1/me/ledger | `PAccountMeService.ledger` |
| 12 | POST /v1/me/checkin | `PAccountRewardService.checkIn` |
| 13 | POST /v1/me/ad-reward | `PAccountRewardService.claimAd` |
| 14 | GET /v1/tasks | `PAccountTasksService.tasksOverview(null)` |
| 15 | GET /v1/me/tasks | `PAccountTasksService.tasksOverview(user)` |
| 16 | POST /v1/me/tasks/{code}/claim | `PAccountTasksService.claimTask` |
| 17 | POST /v1/me/reminder | `PAccountMeService.setReminder` |

## 关键对齐点

- 响应键名/顺序、`{"detail": ...}` 错误体、401 `login required`、422/429/400/404/409/500/501 状态码均照抄。
- Redis：验证码限流 key 逐字 `code_rate:{purpose}:{email}`（TTL 3600s，>5 次 429）；
  会话 token 复用基座 `SessionService`（`minitv_user_session:` 30 天）。
- 验证码：SHA-256 存储、10 分钟过期、失败 5 次上限、验证成功即删；失败计次单独提交（Python 先 commit 再抛）。
- 金币：`wallet_accounts` 行锁 + `coin_ledger` 唯一 `biz_key`；biz_type/biz_key 文案逐字一致
  （`register:{id}` / `checkin:{id}:{day}` / `ad:{id}:{day}` / `task:{code}:{id}:{day}`）。
- 天数统一 America/Los_Angeles；任务进度用 UTC 窗口边界（`Times.dayStartUtc`），
  无 FILTER 聚合（用 `COUNT(DISTINCT ...)`）。
- JSON 列 `reward_configs.amounts` 当 String 读，Jackson 解析为 `List<Integer>`。
- 所有 Bean / Mapper 类名以 `PAccount` 开头；mapper XML 为 `resources/mapper/paccount_user.xml`。
- 事务用 `TransactionTemplate` 显式包裹（而非类级 `@Transactional`）：Python 在验证码错误时
  “先 commit 计次再抛 400”，若整方法 `@Transactional` 会被回滚，故按 Python 的提交边界逐步包裹；
  其余多写操作（注册/登录/签到/广告/任务领取/三方绑定）均为单事务提交。

## 已知差异 / 受基座限制

1. 请求校验失败（必填缺失、`id_token` 为空）由 Bean Validation + 基座 `GlobalExceptionHandler`
   返回 422 数组，字段级 `msg/type` 文案与 Pydantic v2 数组不完全逐字相同（形状一致）。
2. `CodeIn.purpose` 显式传 `null` 时按缺省值 `"login"` 处理；Pydantic 会报 422（缺省省略时行为一致）。
3. 时间字段按移植规范返回 `LocalDateTime`，由基座 `JacksonConfig` 序列化；该基座序列化器使用
   `LocalDateTime.toString()+"+00:00"`，秒为 0 时会省略 `:00`（如 `12:00+00:00`），Python
   `isoformat()` 为 `12:00:00+00:00`。属基座公共行为，未在本包内改写。
4. 时间戳：Python 用 DB `now()`（server_default）写入，本实现同样省略 `created_at/updated_at`
   由 MySQL 默认值填充；`updated_at` 的显式更新用 `now()` 对齐 SQLAlchemy `onupdate`。
5. 仅 `PATCH /v1/me` 的 `birthday` 采用 ISO 日期 (`yyyy-MM-dd`)；Pydantic 3.11+ 还接受
   基本格式 `yyyyMMdd`，Java `LocalDate.parse` 不接受（极少见，未做兼容）。
6. Google/Facebook 证书缓存、HTTP 超时、错误文案（unreachable / rejected / invalid）逐字对齐；
   JWT 校验用 JDK `SHA256withRSA` 手写（项目无 JWT 依赖），覆盖 alg/iss/aud/exp/iat/nbf。
7. 邮件（Resend）响应体 JSON 解析失败时返回 503 `email delivery failed` 并删除验证码行；
   Python 该极端路径会 500 且保留行（正常路径一致）。
8. 无占位/未实现项；所有 17 个接口均为完整实现。
