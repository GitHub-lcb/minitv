# 图片存储与视频（Bunny）配置手册

MiniTV 的图片和视频都托管在 [bunny.net](https://bunny.net)：封面/字幕走 **Bunny Storage + 图片 CDN**，正片走 **Bunny Stream（转码 + HLS + 签名播放）**。业务服务只保存 URL / Video GUID，视频二进制由浏览器直传 Bunny，不经过 API 服务器。

本文按「先配图片，视频随后」的顺序写。只配图片也能正常建剧、传封面、显示列表；视频相关接口在补齐 Stream 配置前会返回明确的 503。

> 安全约定：所有密钥只写进仓库根目录 `.env`（已被 `.gitignore` 忽略），不要提交、不要贴到聊天/文档/前端代码里。开发、预发、生产必须使用**不同**的 Stream Library、Storage Zone 和 Pull Zone。

---

## 0. 先理解 `BUNNY_MODE`

| 模式 | 行为 |
| --- | --- |
| `fake`（默认） | 完全不联网。封面上传返回 503，视频走本地假的 TUS 接收端，播放地址是假 CDN。适合没有 Bunny 账号时联调。 |
| `real` | 按下面的配置真实调用 Bunny：上传封面/字幕、创建视频对象、TUS 直传、HLS 签名播放。 |

切换只需改 `.env` 的 `BUNNY_MODE` 并**重启后端**（Java 在启动时读取 `.env`）。

---

## 1. 图片：Storage Zone + Pull Zone

### 1.1 创建 Storage Zone

1. 打开 <https://dash.bunny.net> → 左侧 **Storage** → **Add Storage Zone**。
2. 表单建议：
   - **Name**：`minitv-dev`（小写字母/数字/连字符，全账号唯一）；
   - **Storage tier**：`Standard` 即可（`Edge SSD` 目前仅 Frankfurt）；
   - **Main region**：选离上传者/服务器最近的区域（例如服务器在 Singapore 选 `Singapore`）；
   - **Replication**：本地开发可不加；生产可加一个复制区。
3. 点 **Add Storage Zone**。

### 1.2 拿到 Storage 凭据

打开刚创建的 Storage Zone → **Access** 标签 → **API / HTTP**，复制三个值：

| 页面上的字段 | 含义 | 对应环境变量 |
| --- | --- | --- |
| Storage Zone Name | 存储区标识 | `BUNNY_STORAGE_ZONE` |
| Password | Storage Zone 密码（HTTP `AccessKey`） | `BUNNY_STORAGE_PASSWORD` |
| Hostname | 区域上传域名，例如 `sg.storage.bunnycdn.com` | `BUNNY_STORAGE_HOST` |

> 上传接口：`PUT https://{Hostname}/{Storage Zone Name}/{path}`，请求头 `AccessKey: {Password}`。
> 封面写入 `covers/{drama_id}/cover.{ext}`，字幕写入 `subs/{episode_id}/sub{ext}`。

### 1.3 创建图片 Pull Zone（公开 CDN）

1. 左侧 **CDN** → **Add Pull Zone**。
   - **Name**：`minitv-dev-images`；
   - **Origin Type**：`Storage Zone`；
   - **Origin**：选中 `minitv-dev`；
   - 其他保持默认 → **Add Pull Zone**。
   （也可以在 Storage Zone 页的 **Connected Pull Zones** 里直接 Add。）
2. 打开该 Pull Zone → **General**，复制 **Hostname**，形如 `minitv-dev-images.b-cdn.net` → `BUNNY_IMAGE_CDN_HOST`。
3. ⚠️ **Security 页不要开启 Token Authentication**。
   项目里的封面 URL 是公开地址（`https://{图片 CDN host}/covers/...`），开启鉴权后封面会 403。
4. 可选：开启 Image Optimizer（图片缩放/WebP）。项目不依赖，不开启也能正常使用。

### 1.4 填图片相关变量

```dotenv
BUNNY_MODE=real

BUNNY_STORAGE_ZONE=minitv-dev
BUNNY_STORAGE_HOST=sg.storage.bunnycdn.com
BUNNY_STORAGE_PASSWORD=<storage-zone-password>

BUNNY_IMAGE_CDN_HOST=minitv-dev-images.b-cdn.net
# 图片 Pull Zone 未开 Token 鉴权时，填任意非空串即可（只有上传字幕才会用到）
BUNNY_IMAGE_TOKEN_KEY=local-dev-image-token
```

---

## 2. 视频：Bunny Stream

### 2.1 创建视频库

1. 左侧 **Stream** → **Add Video Library**。
2. **Name**：`minitv-dev`；**Region**：选离服务器近的区域（如 `Singapore`）→ 创建。

### 2.2 拿 Library ID / API Key / Pull Zone Hostname

进入视频库：

- **API** 页：
  - `Library ID`（数字）→ `BUNNY_STREAM_LIBRARY_ID`
  - `API Key` → `BUNNY_STREAM_API_KEY`
- 同页 `Pull Zone` → **Manage**（或 **CDN** 页）：复制 **Hostname**，形如 `vz-xxxxx.b-cdn.net` → `BUNNY_STREAM_CDN_HOST`

 > 注意区分三个长得很像的 key：
>
> | key | 在哪 | 形态 | 用途 |
> | --- | --- | --- | --- |
> | Library API Key | 库 → **API** 页 | 41 位（含 `-`） | 创建视频对象、TUS 直传授权 |
> | Token authentication key | 库 → **Security** 页（也等于库关联 Pull Zone 的 Token Authentication Key） | 通常 36 位 GUID | 播放 HLS / Player embed 签名 |
> | 账号 API Key | Account → API key | 较长 GUID | 账号级管理、清缓存（purge） |
>
> 如果 Security 页还没有现成 key：打开 **Embed view token authentication** 即可生成；也可以通过 Bunny API
> `POST https://api.bunny.net/videolibrary/{id}`（body `{"EnableTokenAuthentication":true,"PlayerTokenAuthenticationEnabled":true}`）开启。

### 2.3 开启 Token 鉴权（播放必需）

视频库 → **Security** 页：

1. 开启 **Embed view token authentication**（保护 player/embed）。
2. 复制 **Token authentication key** → `BUNNY_STREAM_TOKEN_KEY`。
   该 key 同时用于签 HLS 直链（`/bcdn_token=...`），重置会让两者同时失效。
3. 确认 **CDN token authentication** 已开启；如未开启，在库关联的 Pull Zone（**API → Pull Zone → Manage**）的 **Security** 页开启 **Token Authentication（Advanced）**。

### 2.4 填视频相关变量

```dotenv
BUNNY_STREAM_LIBRARY_ID=123456
BUNNY_STREAM_API_KEY=<library-api-key>
BUNNY_STREAM_CDN_HOST=vz-xxxxx.b-cdn.net
BUNNY_STREAM_TOKEN_KEY=<token-authentication-key>
```

---

## 3. 账号 API Key（仅清缓存用）

左侧 **Account** → **API key** → 复制/新建 key → `BUNNY_API_KEY`。

- 用于替换封面、下架剧目时调用 Bunny Purge 清理 CDN 缓存；
- 该 key 能管理账号下所有资源，权限最大，只能放服务端 `.env`。
- 若本机网络无法访问 `api.bunny.net`，上传和播放不受影响，只是 Purge 会失败（日志可见）。

---

## 4. 完整 `.env` 模板

```dotenv
BUNNY_MODE=real

# 账号级 key（purge）
BUNNY_API_KEY=<account-api-key>

# Storage Zone（封面 / 字幕）
BUNNY_STORAGE_ZONE=minitv-dev
BUNNY_STORAGE_HOST=sg.storage.bunnycdn.com
BUNNY_STORAGE_PASSWORD=<storage-zone-password>

# 图片 Pull Zone（公开访问，不要开 Token 鉴权）
BUNNY_IMAGE_CDN_HOST=minitv-dev-images.b-cdn.net
BUNNY_IMAGE_TOKEN_KEY=local-dev-image-token

# Stream 视频库
BUNNY_STREAM_LIBRARY_ID=123456
BUNNY_STREAM_API_KEY=<library-api-key>
BUNNY_STREAM_CDN_HOST=vz-xxxxx.b-cdn.net
BUNNY_STREAM_TOKEN_KEY=<token-authentication-key>

# 可选：Bunny 转码回调 HMAC 密钥（默认对账轮询即可，不配也行）
BUNNY_CALLBACK_SECRET=<random-string>
```

改完重启后端：

```powershell
cd server
mvn spring-boot:run
```

---

## 5. 验证

### 5.1 直接验证 Storage / CDN（不经过后端）

```powershell
# 上传一个探针文件
curl.exe -X PUT "https://sg.storage.bunnycdn.com/minitv-dev/__probe.txt" `
  -H "AccessKey: <storage-zone-password>" --data-binary "hello"

# 通过图片 CDN 读取（必须 200）
curl.exe -I "https://minitv-dev-images.b-cdn.net/__probe.txt"

# 清理
curl.exe -X DELETE "https://sg.storage.bunnycdn.com/minitv-dev/__probe.txt" `
  -H "AccessKey: <storage-zone-password>"
```

### 5.2 通过后台验证

1. 建剧 → 上传封面（jpg / png / webp，≤ 5 MB）；
2. 打开 `https://{BUNNY_IMAGE_CDN_HOST}/covers/{drama_id}/cover.{ext}`，能看到图片即成功；
3. 视频库配置完成后：新建分集 →「上传视频」→ 浏览器直传 → 等转码（后台每 120 秒自动对账，也可点「同步状态」）→ 分集进入「审核中」→ 审核发布；
4. 后台分集点「预览」能播放；公开端 `POST /v1/playback` 返回 5 分钟签名的 HLS 地址。

---

## 6. 常见问题

| 现象 | 原因 / 处理 |
| --- | --- |
| 封面 403 | 图片 Pull Zone 误开了 Token Authentication；关掉，或改用外部 `cover_url` |
| 播放 403 | Stream 的 Token key 填错，或 CDN token authentication 没开 |
| 上传 401 / 502 | 把账号 API Key 当成了 Library API Key；或 Library ID/Key 不匹配 |
| `503 BUNNY_MODE=real but BUNNY_STREAM_LIBRARY_ID is not configured` | 视频变量还没填；只配图片时属预期，先不影响封面 |
| `503 ... BUNNY_STORAGE_* is not configured` | Storage 三个变量（zone/host/password）没填全 |
| 字幕播放 500 | 有 `subtitle_url` 但 `BUNNY_IMAGE_TOKEN_KEY` 为空 |
| Purge 失败但上传正常 | 本机访问不了 `api.bunny.net`，或账号 API Key 不对；不影响播放 |
| Bunny 转码长期不完成 | 到 Bunny 控制台看该视频状态；后台点「同步状态」；检查视频格式（MP4/MOV，≤200MB） |
| 上传会话接口返回 502 / 后端日志 `video.bunnycdn.com ... timeout` | 后端所在网络访问不了 Bunny 视频 API。服务器一般可直连；本机开发若有代理，可给 JVM 加代理后启动：`$env:JAVA_TOOL_OPTIONS='-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=<代理端口>'`（图片走 `*.b-cdn.net`/`storage.bunnycdn.com`，通常不受影响） |
| 播放 403 且日志提示 token 不对 | `BUNNY_STREAM_TOKEN_KEY` 填成了 Library API Key；两者不要混用 |

---

## 7. 安全清单

- [ ] `.env` 只在本地/服务器，永远不提交（仓库已忽略）；
- [ ] 前端代码、构建产物、日志里都不出现任何 Bunny key；
- [ ] 开发/生产使用独立 Library、Storage Zone、Pull Zone 和 key；
- [ ] 账号 API Key 权限最大，只用于服务端 purge；
- [ ] 怀疑泄露时：Bunny 控制台重置对应 key，同步更新服务器 `.env` 并重启；
- [ ] 删除剧目/分集只清 DB，Bunny 上的封面/字幕对象需定期清理（见 `docs/` 中已有待办）。
