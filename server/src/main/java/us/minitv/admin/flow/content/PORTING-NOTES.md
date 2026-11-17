# admin-flow-content 移植说明

模块：`admin-flow-content`（Java 包 `us.minitv.admin.flow.content`）
来源：`backend/app/modules/ops/flow_router.py`（第 1–1237 行）、
`backend/app/modules/content/upload_service.py`、
`backend/app/modules/common/bunny.py`、
`backend/app/modules/content/service.py`、
`backend/app/modules/content/publish_service.py`（publish/schedule/delete 部分）。

## 完成接口（23 个，全部实现）

| # | 方法 & 路径 | 实现 |
|---|---|---|
| 1 | POST /admin/v1/dramas/{drama_id}/episodes | AFContentEpisodeController#createEpisode |
| 2 | POST /admin/v1/dramas/{drama_id}/episodes/bulk | #bulkEpisodes |
| 3 | GET /admin/v1/dramas/{drama_id}/episodes | #listEpisodes（含 real 模式转码归并） |
| 4 | GET /admin/v1/episodes/{episode_id}/preview-url | #previewUrl |
| 5 | POST /admin/v1/episodes/{episode_id}/upload-session | #uploadSession |
| 6 | POST /admin/v1/episodes/{episode_id}/upload-headers | #uploadHeaders |
| 7 | POST /admin/v1/episodes/{episode_id}/sync-status | #syncStatus |
| 8 | POST /admin/v1/episodes/{episode_id}/upload-failed | #uploadFailed |
| 9 | POST /admin/v1/dev-tus/{video_id} | #devTusCreate（201 + Location/Tus-Resumable） |
| 10 | PATCH /admin/v1/dev-tus/{video_id} | #devTusPatch（204 + Upload-Offset） |
| 11 | DELETE /admin/v1/episodes/{episode_id} | #deleteEpisode（204） |
| 12 | PATCH /admin/v1/episodes/{episode_id} | #updateEpisode |
| 13 | POST /admin/v1/episodes/{episode_id}/publish | #publish |
| 14 | POST /admin/v1/episodes/{episode_id}/unpublish | #unpublish |
| 15 | POST /admin/v1/episodes/bulk-status | #bulkStatus |
| 16 | POST /admin/v1/dramas/{drama_id}/cover | AFContentDramaMediaController#uploadCover（multipart） |
| 17 | DELETE /admin/v1/dramas/{drama_id}/cover | #deleteCover |
| 18 | POST /admin/v1/episodes/{episode_id}/subtitle | AFContentEpisodeController#uploadSubtitle（multipart） |
| 19 | POST /admin/v1/dramas/{drama_id}/status | AFContentDramaMediaController#dramaStatus |
| 20 | POST /admin/v1/dramas/{drama_id}/schedule | #dramaSchedule |
| 21 | POST /admin/v1/schedules/apply | #applySchedules |
| 22 | POST /admin/v1/dramas/{drama_id}/delete | #deleteDrama（require_admin_role） |
| 23 | GET /admin/v1/dramas/{drama_id}/preview | #preview |

额外实现：`HEAD /admin/v1/dev-tus/{video_id}`（Python 同文件里有，TUS 续传检查用；不在 23 条清单内，属同一路由组）。

## 与 Python 的已知差异

1. **multipart 体积上限**：Python 封面最多读 5MB+1、字幕全量读；Java 容器上限由本包
   `AFContentMultipartConfig` 提到 30MB（`application.yml` 属禁止修改范围，基座默认 1MB 会让
   2–5MB 封面在上传前就 500）。超过 30MB 的文件返回 500 而不是 Python 的 422；≤30MB 的
   体积/内容校验（422 文案）与 Python 完全一致。
2. **显式 JSON null**：可选字段（如 `title`、`duration_seconds`）显式传 `null` 时按“未提供”
   处理（Python Pydantic 会 422）；缺省字段行为一致（title → `Episode N`，duration → 0）。
3. **naive datetime**：`schedule` 收到不带时区的时间时统一按 UTC 存取，响应/审计里输出
   `...T00:00:00+00:00`；Python 对 naive 值输出不带 offset 的 isoformat。带 offset 的输入一致。
4. **Bunny 上游错误内层文本**：外层文案与状态码一致（如 `bunny upstream: ...`、502），
   Python `repr(bytes[:200])` 的字节转义用 UTF-8 近似还原。
5. **admin preview 的 authorize 子集**：该端点固定以匿名观众（`user_id=None`）鉴权，只可能走到
   “免费集数 / locked”分支；会员与金币解锁分支属公共播放模块，本模块未复制。
6. **real 模式转码巡检线程**：由 `AFContentTranscodeReconciler`（ApplicationReadyEvent，120s 间隔）
   启动，行为同 Python；fake 模式不启动（与测试环境一致）。
7. **校验错误 detail 文案**：`@Valid` 的 422 仍为 FastAPI 形状 `{"detail":[{type,loc,msg}]}`，
   但 `msg` 是 Jakarta 校验文案而非 Pydantic 原文；status/loc 形状一致。
8. **real 模式外部调用**依赖同一组 `BUNNY_*` 环境变量；`.env` 当前为 `fake`，因此封面上传
   返回 503、preview-url 返回 503、dev-tus 可用，均与 Python 相同。

## 未实现/占位项

- 无。所有接口均有完整实现；fake 模式与 real 模式（Bunny Stream/Storage/Purge）路径均已编写。

## 文件清单

Java（19）：2 个 Controller、5 个 Service、1 个 Bunny 网关、1 个转码巡检组件、
1 个 multipart 配置组件、1 个 DTO、1 个角色守卫、1 个状态机、6 个 Mapper（mapper/ 子包）。
Mapper XML（4）：`afflow_episode.xml`、`afflow_drama.xml`、`afflow_media_upload.xml`、
`afflow_cascade.xml`（audit/cdn_purges 走注解 SQL）。
