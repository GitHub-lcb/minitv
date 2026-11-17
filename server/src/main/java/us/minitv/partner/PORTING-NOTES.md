# partner（上剧方 / supplier）模块移植说明

Python 标准：`backend/app/modules/ops/partner_router.py`、`content/upload_service.py`、
`content/service.py`、`common/bunny.py`、`ops/settlements.py`、`ops/api_keys.py`。
Java 包：`us.minitv.partner`（控制器/服务/网关），`us.minitv.partner.mapper` + `resources/mapper/partner_*.xml`（SQL）。

## 完成接口（31/31，全部对接 Python 行为）

| 分组 | 接口 |
|---|---|
| 资料/凭据 | GET `/partner/v1/me`；POST `/auth/password`；POST `/auth/logout` |
| 标签/剧集管理 | GET `/tags`；GET/POST `/dramas`；GET/PATCH `/dramas/{drama_id}`；POST `/dramas/{drama_id}/cover`；DELETE `/dramas/{drama_id}`；POST `/dramas/{drama_id}/submit` |
| 分集 | POST `/dramas/{drama_id}/episodes`；PATCH/DELETE `/episodes/{episode_id}`；GET `/episodes/{episode_id}/preview-url` |
| 上传流水线 | POST `/episodes/{episode_id}/upload-session`；POST `/episodes/{episode_id}/upload-headers`；GET `/episodes/{episode_id}/upload-status`；POST `/episodes/{episode_id}/upload-failed`；POST/PATCH `/dev-tus/{video_id}` |
| 数据/结算 | GET `/stats/overview`；GET `/stats/dramas`；GET `/settlements` |
| API Key | GET `/api-keys`；GET `/auth/keys`；GET `/auth/key-info`；POST `/api-keys`；PATCH `/api-keys/{key_id}`；POST `/api-keys/{key_id}/rotate`；POST `/api-keys/{key_id}/revoke` |

额外：实现了 Python 源码中存在、但 `PORTING-ENDPOINTS.md` 未列出的
`HEAD /partner/v1/dev-tus/{video_id}`（fake 模式 TUS 断点续传探测，行为与 Python 相同）。

## 与 Python 的已知差异（均为不可达/基座约束或未联真实上游）

1. 鉴权阶段 Redis 故障的 503 文案：`@CurrentPartner` 基座解析器统一文案（全仓库一致）；
   本模块 `auth/password`、`auth/logout` 内部 Redis 失败仍返回 Python 原文 `会话存储不可用`。
2. 请求体校验 422 由手写 Pydantic v2 风格数组模拟；`list[int]` 元素错误的 `loc` 用
   `"tag_ids.0"` 字符串（Pydantic 为 `["body","tag_ids",0]`），个别 `type` 名（如 `int_type`）与
   Pydantic 的 `int_from_float` 不同；业务性 422/409 的文案与结构逐字对齐。
3. 封面 multipart：受基座 Spring 默认 multipart 限制（`application.yml` 禁改，1MB 文件上限在
   进入 handler 前生效）。fake 模式仍严格先返回 503；real 模式接近 5MB 的大文件可能被容器提前拒绝。
4. Bunny real 模式用 JDK `HttpClient` 重写（创建视频重试、TUS 头、embed 签名、Storage 上传/删除、
   CDN purge 均照抄 Python）；fake 模式（默认）行为等价，未对真实 Bunny 账号联调。
5. `api_key_events` 落库由基座 HTTP 过滤器负责（非本包职责），本包只负责 `request` 属性与审计。
6. 审计 payload 的 `expires_at` 使用本仓库统一的 `...+00:00` ISO 形式；Python 在 MySQL 下为无时区 isoformat。
7. `GET /partner/v1/dramas` 排序与 Python 相同（仅 `updated_at DESC`）；DATETIME 秒精度并列时顺序由
   MySQL 决定（与 Python 执行的 SQL 相同）。
8. 并发竞态下 `create_episode` 的 MySQL 唯一键冲突翻译为 409 `第 N 集已存在`（Python 该处未捕获会 500）；
   属防御性对齐，正常流程不可达。
9. 数据库副作用完全对齐：`dramas`/`episodes` 显式写 `updated_at = NOW()`（对应 SQLAlchemy `onupdate`），
   `admin_users` 改密同；`audit_logs`、`admin_api_keys`、`media_uploads`、`drama_tags` 写入字段一致。
10. 无未实现/占位项。

## 关键实现点

- 租户隔离：所有查询/mutation 都带 `supplier_id`（drama 直连；episode/media_upload 经 join dramas）。
- 状态机：`PartnerTransitions` 复制 `content/service.py` 的 EPISODE_TRANSITIONS，非法迁移 409 文案
  `illegal episode transition: X -> Y`；DB 触发器仍有兜底。
- 上传会话：422 校验文案、`_reset_for_replace`、`vertical`、浏览器时长占位、替换时旧时长清零、
  fake 模式 `video_id=token_hex(16)` / `/partner/v1/dev-tus/...` 路径改写、HMAC callback 签名均对齐。
- 统计/结算：`first_orders` 用 `ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY paid_at, id)`；
  `_share_at` 按订单支付时刻取分成；`effective_share_bps` 用金额加权（非 revenue/gross）；
  `completion_rate` 用银行家舍入 4 位；无首单时 `0`（整数）而不是 `0.0`。
- API Key：scope 白名单 `read/write/upload`、`_expiry` 合法天数、空 scopes 422、同名 409、
  rotate 先撤销旧钥再签发（利用生成列唯一索引释放名字）、revoke 幂等、`state` 按 Python 的
  14 天边界计算（未用基座按整天截断的实现）。
- Redis key：`minitv_admin_session:<token>`（改密扫描撤销、登出删除）与基座一致。

## 验证

- `server/tools/check-package.ps1 -Path server/src/main/java/us/minitv/partner` → `JAVAC_OK (32 files)`。
- 额外做了两类本地静态冒烟（临时文件，未提交）：MyBatis 7 个 XML 全量解析 + 114 条语句动态 SQL 构建
  （0 失败，含 `@MapperScan` 注解 Mapper 绑定校验）；用桩 Mapper 直接驱动 Service 的 150 项断言
  （响应键、默认值、409/422 文案与 detail 结构、上传流水线、fake TUS、统计数值、API Key 生命周期），全部通过。
