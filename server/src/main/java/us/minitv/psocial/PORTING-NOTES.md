# public-social 模块移植说明（Java 包 `us.minitv.psocial`）

Python 源码唯一行为标准：`backend/app/modules/public_flow.py`（评论 / reactions / favorites / messages / history / lists 段）、
`backend/app/modules/social/moderation.py`、`social/models.py`、`playback/models.py`、`content/models.py`。

## 完成接口（11/11）

| # | 接口 | 实现位置 |
|---|---|---|
| 1 | GET /v1/dramas/{drama_id}/comments | `PSocialCommentController#listDramaComments` → `PSocialCommentService#listDramaComments` |
| 2 | GET /v1/episodes/{episode_id}/comments | `PSocialCommentController#listEpisodeComments` → 游标分页 + 点赞聚合 + 待审可见性 |
| 3 | POST /v1/episodes/{episode_id}/comments | `PSocialCommentController#createEpisodeComment` → 冷却 30s / 日限 50 / 入队审核 |
| 4 | POST /v1/comments | `PSocialCommentController#createComment`（旧版别名，episode_id 必填） |
| 5 | DELETE /v1/comments/{comment_id} | `PSocialCommentController#deleteComment` |
| 6 | POST /v1/reactions | `PSocialCommentController#react` → `PSocialReactionService` |
| 7 | GET /v1/me/favorites | `PSocialMeController#favorites` → `PSocialMeService` |
| 8 | GET /v1/me/messages | `PSocialMeController#messages` |
| 9 | POST /v1/me/messages/read | `PSocialMeController#readMessages` |
| 10 | GET /v1/me/history | `PSocialMeController#history` |
| 11 | GET /v1/me/lists/{kind} | `PSocialMeController#list` |

均使用基座 `@CurrentUser`（游客可选的列表接口用 `required = false`），未做任何角色细分（Python 这 11 个接口没有细分角色检查）。

## 实现要点

- **MyBatis**：6 个 `PSocial*Mapper` 接口 + `resources/mapper/psocial_*.xml`（32 条 statement）。MySQL 方言：IN 用 `<foreach>`、分页 `LIMIT/OFFSET`、`UPDATE ... RETURNING` 改为 `SELECT ... FOR UPDATE` + `UPDATE`。
- **审核队列**（`PSocialModerationService` / `PSocialModerationStore` / `PSocialModerationWorker`）：完整搬了 `get_settings`（单行种子默认值：model=`deepseek-flash`、base=`https://codex.apiz.ai/v1`、阈值 0.75、6s 超时）、`queue_state`、`process_comment`（claim→classify→写回，`moderation_state='running'` 守卫防止覆盖人工决定）、`process_pending_batch`（30s 扫描，`MODERATION_WORKER=0` 关闭，与 Python 一致）、`decide`、chat/completions 调用（含 400 时逐个剔除 `response_format`/`max_tokens`/`temperature` 重试、`finish_reason=length`、```json 围栏解析、score 归一化）。
- **Fernet 凭证解密**：`PSocialModerationCrypto` 实现 `cryptography.fernet` 的 `v1:` token（SHA-256 派生密钥 + AES-128-CBC + HMAC-SHA256），解密失败返回 `""` 回落 `OPENAI_API_KEY`。已用 Python 生成的真实 token 做离线比对（见下）。
- **异步副作用**：POST 评论成功后通过 `PSocialModerationWorker#submitProcess`（虚拟线程池）在事务提交后执行审核，等价 FastAPI BackgroundTasks。
- **Redis**：本模块 11 个接口不直接使用 Redis（会话解析在基座）。

## 与 Python 的已知差异

1. **POST /v1/comments 的 501–1000 字符 body**：Python 在 handler 内构造 `EpisodeCommentIn` 触发 pydantic `ValidationError`，属未捕获异常 → Starlette 返回 500 纯文本；Java 复现为 500，但 body 是基座统一格式 `{"detail":"Internal Server Error"}`。
2. **422 校验载荷形状**：status 一致（422），Python 的 pydantic 错误数组字段是 `type/loc/msg/input/ctx`；基座 `GlobalExceptionHandler` 只输出 `type/loc/msg`。长度/类型错误走 Bean Validation 或 `MethodArgumentTypeMismatchException`，属基座统一行为。
3. **后台审核时序**：生产语义与 Python 一致（响应先返回，审核随后完成）。但 Python 的 `TestClient` 会 drain BackgroundTasks，Java 测试不会——断言审核结果时需轮询等后台线程完成。
4. **审核失败文案里的异常类名**：Python 写 `请求失败：ConnectError` 之类 httpx 异常名，Java 写 `请求失败：ConnectException` 等 JDK 异常名；该文案只落在 `comments.moderation_error` 与日志里，不进 HTTP 响应。
5. **时间序列化**：统一由基座 `JacksonConfig` 输出 `...+00:00`；当秒为 0 时 `LocalDateTime.toString()` 会省略 `:00`（Python `isoformat()` 始终带秒），这是基座级差异，非本模块代码。
6. **未实现（占位）**：`moderation.py` 中后台管理用的 `key_status / mask_key / set_api_key / clear_api_key / moderate / encrypt_key` 属于 `/admin/v1/moderation/*` 接口，由 admin 模块 Agent 实现；本包按“不依赖别的 Agent 类”原则未写这些函数。审核运行链路所需的其余函数已全部实现。
7. **`GET /v1/me/lists/{kind}` 的行序**：与 Python 一样不写 ORDER BY（依赖 MySQL 返回顺序），未人为加 `ORDER BY id`，以免与 Python 查询计划不同。
8. **查询参数边界**：`?limit=`（空值）Spring 绑定为 null 后走默认值，Python 返回 422；极边界差异。

## 校验结果

- `& server\tools\check-package.ps1 -Path server\src\main\java\us\minitv\psocial` → **JAVAC_OK (22 files)**。
- 离线追加校验（临时目录，不污染仓库）：
  - Fernet：Python 生成的 `v1:` token 在 Java 端解密结果一致；错误 secret / 垃圾串均回落 `""`（与 `decrypt_key` 行为一致）。
  - MyBatis：6 个 XML 全部可被 `XMLMapperBuilder` 解析，32 条 statement 与 6 个 Mapper 接口方法一一对应。
  - 审核 system prompt / categories 常量与 Python 源码 AST 字面量逐字符一致。

## 文件清单（29 个）

- 控制器 2：`PSocialCommentController`、`PSocialMeController`
- 服务/组件 7：`PSocialCommentService`、`PSocialReactionService`、`PSocialMeService`、`PSocialModerationService`、`PSocialModerationStore`、`PSocialModerationWorker`、`PSocialModerationCrypto`
- Mapper 6：`PSocialCommentMapper`、`PSocialReactionMapper`、`PSocialInboxMapper`、`PSocialHistoryMapper`、`PSocialContentMapper`、`PSocialModerationMapper`
- DTO 7（`us.minitv.psocial.dto`）
- XML 6：`psocial_comment.xml`、`psocial_reaction.xml`、`psocial_inbox_message.xml`、`psocial_history.xml`、`psocial_content.xml`、`psocial_moderation.xml`
