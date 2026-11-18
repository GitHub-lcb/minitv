import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Alert,
  Button,
  ConfigProvider,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Progress,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  Upload,
  message,
} from "antd";
import type { UploadFile, UploadProps } from "antd";
import {
  CloseOutlined,
  CloudUploadOutlined,
  LinkOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  SyncOutlined,
  VideoCameraOutlined,
} from "@ant-design/icons";
import { api, errorText } from "../api";
import { useCanWriteContent } from "../me";
import {
  MAX_UPLOAD_BYTES,
  directUpload,
  formatDuration,
  formatFileSize,
  orientationLabel,
  probeVideo,
  waitForTranscode,
} from "./episodeUpload";
import type { UploadSession, VideoMeta } from "./episodeUpload";
import { batchQueue } from "./batchUpload";
import { BatchUploadButton, BatchUploadModal, BatchUploadPill } from "./BatchUploadPanel";

type EpisodeRow = {
  id: number;
  episode_number: number;
  title: string;
  status: string;
  duration_seconds: number;
  bunny_video_id: string;
  /** `bunny` (default) or one of the external providers below. */
  video_provider?: string;
  external_video_id?: string;
  external_url?: string;
  aspect_ratio?: string;
};

const STATUS_META: Record<string, { text: string; color: string }> = {
  not_uploaded: { text: "未上传", color: "default" },
  transcoding: { text: "转码中", color: "processing" },
  transcode_failed: { text: "转码失败", color: "error" },
  in_review: { text: "审核中", color: "orange" },
  published: { text: "已上架", color: "green" },
  unpublished: { text: "已下架", color: "red" },
};

/* ---------- external video sources (traffic-first embeds) ---------- */

type Provider = "youtube" | "tiktok" | "facebook" | "vimeo" | "douyin" | "iframe" | "hls" | "mp4";

type ProviderMeta = {
  label: string;
  /** Default aspect ratio; TikTok/抖音 are portrait-first. */
  aspect: string;
  idLabel?: string;
  urlLabel: string;
  urlHint: string;
  /** false when the provider accepts an ID alone (no URL needed). */
  urlRequired: boolean;
};

const PROVIDER_META: Record<Provider, ProviderMeta> = {
  youtube: {
    label: "YouTube",
    aspect: "16:9",
    idLabel: "视频 ID（可选）",
    urlLabel: "YouTube 链接",
    urlHint: "watch / shorts / youtu.be 链接都可以，填链接会自动识别 ID",
    urlRequired: false,
  },
  tiktok: {
    label: "TikTok",
    aspect: "9:16",
    idLabel: "视频 ID（可选）",
    urlLabel: "TikTok 视频链接",
    urlHint: "形如 tiktok.com/@user/video/1234567890，填链接会自动识别 ID",
    urlRequired: false,
  },
  facebook: {
    label: "Facebook",
    aspect: "16:9",
    urlLabel: "Facebook 视频链接",
    urlHint: "必须是公开、允许嵌入的 https 链接",
    urlRequired: true,
  },
  vimeo: {
    label: "Vimeo",
    aspect: "16:9",
    idLabel: "视频 ID（可选）",
    urlLabel: "Vimeo 链接",
    urlHint: "形如 vimeo.com/123456789，填链接会自动识别 ID",
    urlRequired: false,
  },
  douyin: {
    label: "抖音",
    aspect: "9:16",
    urlLabel: "抖音可嵌入链接",
    urlHint: "抖音没有稳定的公开 embed 地址，需自行提供允许嵌入的 https 播放页",
    urlRequired: true,
  },
  iframe: {
    label: "通用 iframe",
    aspect: "16:9",
    urlLabel: "iframe 地址",
    urlHint: "任意允许被 iframe 嵌入的 https 地址",
    urlRequired: true,
  },
  hls: {
    label: "HLS（m3u8）",
    aspect: "16:9",
    urlLabel: "m3u8 地址",
    urlHint: "https 直链；H5/官网播放器用 hls.js 播放",
    urlRequired: true,
  },
  mp4: {
    label: "MP4 直链",
    aspect: "16:9",
    urlLabel: "MP4 地址",
    urlHint: "https 直链；浏览器原生 video 播放",
    urlRequired: true,
  },
};

const PROVIDER_OPTIONS = (Object.keys(PROVIDER_META) as Provider[]).map((value) => ({
  value,
  label: PROVIDER_META[value].label,
}));

const ASPECT_OPTIONS = ["16:9", "9:16", "1:1", "4:3"].map((value) => ({ value, label: value }));

const isExternalProvider = (provider: string | undefined | null): boolean =>
  Boolean(provider) && provider !== "bunny";

const providerLabel = (provider: string | undefined): string => {
  if (!provider || provider === "bunny") return "Bunny";
  return PROVIDER_META[provider as Provider]?.label ?? provider;
};

/** Mirrors the backend pattern per provider so the dialog can preview live. */
function deriveExternalId(provider: Provider, raw: string): string {
  const url = raw.trim();
  if (!url) return "";
  if (provider === "youtube") {
    return /(?:youtu\.be\/|youtube\.com\/(?:watch\?v=|shorts\/|embed\/|live\/))([A-Za-z0-9_-]{6,})/.exec(url)?.[1] ?? "";
  }
  if (provider === "tiktok") return /\/video\/(\d{6,})/.exec(url)?.[1] ?? "";
  if (provider === "vimeo") return /vimeo\.com\/(?:video\/)?(\d{4,})/.exec(url)?.[1] ?? "";
  return "";
}

type RemotePreview = { kind: "embed" | "video" | "link"; url: string; aspect: string };

/** Client-side preview URL, matching {@code ExternalVideoSource.embedUrl}. */
function externalPreview(
  provider: Provider,
  videoIdRaw: string,
  urlRaw: string,
  aspectRaw: string
): RemotePreview | null {
  const url = urlRaw.trim();
  const id = videoIdRaw.trim() || deriveExternalId(provider, url);
  const aspect = aspectRaw || PROVIDER_META[provider].aspect;
  switch (provider) {
    case "youtube":
      return id ? { kind: "embed", url: `https://www.youtube.com/embed/${id}?rel=0&playsinline=1`, aspect } : null;
    case "tiktok":
      return id ? { kind: "embed", url: `https://www.tiktok.com/embed/v2/${id}`, aspect } : null;
    case "vimeo":
      return id ? { kind: "embed", url: `https://player.vimeo.com/video/${id}`, aspect } : null;
    case "facebook":
      return url
        ? {
            kind: "embed",
            url: `https://www.facebook.com/plugins/video.php?href=${encodeURIComponent(url)}&show_text=false&autoplay=false`,
            aspect,
          }
        : null;
    case "douyin":
    case "iframe":
      return url ? { kind: "embed", url, aspect } : null;
    case "mp4":
      return url ? { kind: "video", url, aspect } : null;
    case "hls":
      return url ? { kind: "link", url, aspect } : null;
  }
}

/** One preview dialog shared by the row action and both editor dialogs. */
type PreviewState =
  | { open: false }
  | { open: true; kind: "embed"; url: string; aspect: string; title: string }
  | { open: true; kind: "video"; url: string; aspect: string; title: string }
  | { open: true; kind: "file"; url: string; title: string }
  | { open: true; kind: "link"; url: string; title: string };

const PREVIEW_FRAME_STYLE: React.CSSProperties = {
  display: "block",
  width: "100%",
  maxHeight: "70vh",
  border: 0,
  background: "#000",
};

function PreviewModal({ preview, onClose }: { preview: PreviewState; onClose: () => void }) {
  const aspectStyle =
    preview.open && (preview.kind === "embed" || preview.kind === "video")
      ? { aspectRatio: preview.aspect.replace(":", " / ") }
      : undefined;
  return (
    <Modal
      open={preview.open}
      title={preview.open ? preview.title : "预览"}
      width={720}
      footer={null}
      centered
      destroyOnHidden
      zIndex={1100}
      onCancel={onClose}
      styles={{ body: { padding: preview.open && preview.kind === "link" ? 16 : 0, background: "#000", lineHeight: 0 } }}
    >
      {preview.open && preview.kind === "embed" ? (
        <iframe
          title={preview.title}
          src={preview.url}
          style={{ ...PREVIEW_FRAME_STYLE, ...aspectStyle }}
          allow="accelerometer; autoplay; fullscreen; encrypted-media; picture-in-picture"
          allowFullScreen
          referrerPolicy="strict-origin-when-cross-origin"
        />
      ) : null}
      {preview.open && preview.kind === "video" ? (
        <video
          key={preview.url}
          src={preview.url}
          controls
          autoPlay
          playsInline
          style={{ ...PREVIEW_FRAME_STYLE, ...aspectStyle, objectFit: "contain" }}
        />
      ) : null}
      {preview.open && preview.kind === "file" ? (
        <video
          key={preview.url}
          src={preview.url}
          controls
          autoPlay
          playsInline
          style={{ ...PREVIEW_FRAME_STYLE, objectFit: "contain" }}
        />
      ) : null}
      {preview.open && preview.kind === "link" ? (
        <div style={{ lineHeight: 1.6 }}>
          <Alert
            type="info"
            showIcon
            message="HLS 播放列表，后台不做内嵌预览"
            description="上架后由 H5/官网播放器播放；也可以复制地址到支持 m3u8 的播放器里检查。"
          />
          <Typography.Paragraph
            copyable
            style={{ marginTop: 16, marginBottom: 0, wordBreak: "break-all", fontFamily: "monospace" }}
          >
            {preview.url}
          </Typography.Paragraph>
        </div>
      ) : null}
    </Modal>
  );
}

/* ---------- video picker (shared by the create and edit dialogs) ---------- */

type VideoPick = {
  fileList: UploadFile[];
  file: File | null;
  meta: VideoMeta | null;
  error: string | null;
  probing: boolean;
  onFileChange: UploadProps["onChange"];
  reset: () => void;
};

function useVideoPick(): VideoPick {
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [file, setFile] = useState<File | null>(null);
  const [meta, setMeta] = useState<VideoMeta | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [probing, setProbing] = useState(false);
  /** Sequence guard: a slow probe must not overwrite a newer pick. */
  const probeSeq = useRef(0);

  const reset = useCallback(() => {
    probeSeq.current += 1;
    setFileList([]);
    setFile(null);
    setMeta(null);
    setError(null);
    setProbing(false);
  }, []);

  const onFileChange: UploadProps["onChange"] = async (info) => {
    const next = info.fileList.slice(-1);
    setFileList(next);
    setError(null);
    setMeta(null);
    const picked = (next[0]?.originFileObj as File | undefined) ?? null;
    setFile(picked);
    if (!picked) {
      setProbing(false);
      return;
    }
    const looksLikeVideo =
      ["video/mp4", "video/quicktime"].includes(picked.type) || /\.(mp4|mov)$/i.test(picked.name);
    if (!looksLikeVideo) {
      setFileList([]);
      setFile(null);
      setProbing(false);
      setError("只支持 MP4 / MOV 文件");
      return;
    }
    if (picked.size > MAX_UPLOAD_BYTES) {
      setFileList([]);
      setFile(null);
      setProbing(false);
      setError(`文件 ${formatFileSize(picked.size)}，超过 200MB 上限`);
      return;
    }
    probeSeq.current += 1;
    const seq = probeSeq.current;
    setProbing(true);
    try {
      // Resolution only: orientation is not restricted. Ops uploads both
      // vertical and landscape episodes, so never gate on width vs height here.
      const probed = await probeVideo(picked);
      if (probeSeq.current !== seq) return;
      setMeta(probed);
    } catch (e) {
      if (probeSeq.current !== seq) return;
      setFileList([]);
      setFile(null);
      setError(errorText(e));
    } finally {
      if (probeSeq.current === seq) setProbing(false);
    }
  };

  return { fileList, file, meta, error, probing, onFileChange, reset };
}

function VideoDrop({
  pick,
  disabled,
  title,
  hint,
}: {
  pick: VideoPick;
  disabled: boolean;
  title: string;
  hint: string;
}) {
  return (
    <Upload.Dragger
      className="ep-drop"
      accept=".mp4,.mov,video/mp4,video/quicktime"
      maxCount={1}
      fileList={pick.fileList}
      showUploadList={false}
      beforeUpload={() => false}
      onChange={pick.onFileChange}
      disabled={disabled}
    >
      <div className="ep-drop-inner">
        <CloudUploadOutlined className="ep-drop-icon" />
        <div className="ep-drop-title">{title}</div>
        <div className="ep-drop-hint">{hint}</div>
      </div>
    </Upload.Dragger>
  );
}

function VideoFileCard({
  pick,
  onClear,
  onPreview,
  clearDisabled,
  progress,
}: {
  pick: VideoPick;
  onClear: () => void;
  onPreview: () => void;
  clearDisabled: boolean;
  progress: number | null;
}) {
  const name = pick.file?.name ?? "已选择的视频";
  const detail = pick.meta
    ? `${pick.meta.width}×${pick.meta.height} · ${orientationLabel(pick.meta)} · ${formatDuration(pick.meta.durationSeconds)} · ${formatFileSize(pick.file?.size ?? 0)}`
    : pick.probing
      ? "正在读取分辨率和时长…"
      : "等待视频信息";
  return (
    <div className="ep-file">
      <div className="ep-video-mark" aria-hidden>
        <VideoCameraOutlined />
      </div>
      <div className="ep-file-main">
        <div className="ep-file-name" title={name}>
          {name}
        </div>
        <div className="ep-file-sub">{detail}</div>
        {progress != null && (
          <Progress percent={progress} size="small" status="active" style={{ margin: "8px 0 0" }} />
        )}
      </div>
      <div className="ep-file-actions">
        <Button type="link" size="small" className="ep-text-btn" icon={<PlayCircleOutlined />} onClick={onPreview}>
          预览
        </Button>
        <Button
          type="text"
          size="small"
          icon={<CloseOutlined />}
          disabled={clearDisabled}
          aria-label="移除视频"
          onClick={onClear}
        />
      </div>
    </div>
  );
}

/* ---------- create / edit dialog ---------- */

/**
 * One dialog for both flows: ``editing === null`` creates a new episode row
 * (video required); otherwise it edits an existing row - title / number are
 * patched, and picking a file additionally replaces the Bunny video.
 */
function EpisodeEditorModal({
  open,
  dramaId,
  episodes,
  editing,
  onClose,
  onSaved,
}: {
  open: boolean;
  dramaId: string;
  episodes: EpisodeRow[];
  editing: EpisodeRow | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form] = Form.useForm<{ episode_number: number; title: string }>();
  const pick = useVideoPick();
  const resetPick = pick.reset;
  const [error, setError] = useState<string | null>(null);
  const [phase, setPhase] = useState<"idle" | "saving" | "uploading" | "transcoding">("idle");
  const [progress, setProgress] = useState(0);
  /** Existing videos stay collapsed until the operator asks to replace. */
  const [replacing, setReplacing] = useState(
    () => editing == null || !editing.bunny_video_id || editing.status === "transcode_failed"
  );
  /** Episode row id once created on the server: retries must reuse it. */
  const [episodeId, setEpisodeId] = useState<number | null>(null);
  const xhrRef = useRef<XMLHttpRequest | null>(null);
  /** Local blob URL for a not-yet-uploaded pick; revoked on close or unmount. */
  const previewBlobRef = useRef<string | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [preview, setPreview] = useState<PreviewState>({ open: false });
  /** External rows edit their source, they never upload bytes here. */
  const isExternalEditing = Boolean(editing && isExternalProvider(editing.video_provider));
  const externalReady = Boolean(editing?.external_video_id || editing?.external_url);

  useEffect(
    () => () => {
      if (previewBlobRef.current) URL.revokeObjectURL(previewBlobRef.current);
    },
    []
  );

  useEffect(() => {
    if (open) return;
    if (previewBlobRef.current) {
      URL.revokeObjectURL(previewBlobRef.current);
      previewBlobRef.current = null;
    }
    setPreview({ open: false });
  }, [open]);

  const closePreview = () => {
    if (previewBlobRef.current) {
      URL.revokeObjectURL(previewBlobRef.current);
      previewBlobRef.current = null;
    }
    setPreview({ open: false });
  };

  const previewCurrent = async () => {
    if (!editing) return;
    setPreviewing(true);
    try {
      const signed = await api<{
        url?: string;
        embed_url?: string;
        type?: string;
        aspect_ratio?: string;
      }>(`/admin/v1/episodes/${editing.id}/preview-url`);
      if (previewBlobRef.current) {
        URL.revokeObjectURL(previewBlobRef.current);
        previewBlobRef.current = null;
      }
      const title = `预览 · 第 ${editing.episode_number} 集`;
      const type = signed.type ?? "embed";
      const aspect = signed.aspect_ratio || editing.aspect_ratio || "9:16";
      if (type === "embed") {
        const url = signed.embed_url ?? signed.url ?? "";
        if (!url) throw new Error("没有可预览的地址");
        setPreview({ open: true, kind: "embed", url, aspect, title });
      } else if (type === "mp4") {
        const url = signed.url ?? "";
        if (!url) throw new Error("没有可预览的地址");
        setPreview({ open: true, kind: "video", url, aspect, title });
      } else {
        const url = signed.url ?? signed.embed_url ?? "";
        if (!url) throw new Error("没有可预览的地址");
        setPreview({ open: true, kind: "link", url, title });
      }
    } catch (e) {
      message.error(`预览失败：${errorText(e)}`);
    } finally {
      setPreviewing(false);
    }
  };

  const previewPicked = () => {
    if (!pick.file) return;
    if (previewBlobRef.current) URL.revokeObjectURL(previewBlobRef.current);
    const url = URL.createObjectURL(pick.file);
    previewBlobRef.current = url;
    setPreview({ open: true, kind: "file", url, title: `预览 · ${pick.file.name}` });
  };

  const clearPick = () => {
    if (preview.open && preview.kind === "file") closePreview();
    resetPick();
  };

  const isCreate = editing === null;
  const busy = phase !== "idle";
  /** Only the byte upload locks the dialog; transcoding runs in the background. */
  const locked = phase === "uploading";
  const canReplaceVideo = !isExternalEditing && (editing === null || editing.status !== "transcoding");

  const nextNumber = useMemo(
    () => episodes.reduce((max, row) => Math.max(max, row.episode_number), 0) + 1,
    [episodes]
  );

  useEffect(() => {
    if (!open) return;
    resetPick();
    setError(null);
    setProgress(0);
    setPhase("idle");
    setEpisodeId(editing?.id ?? null);
    form.setFieldsValue(
      editing
        ? { episode_number: editing.episode_number, title: editing.title }
        : { episode_number: nextNumber, title: `Episode ${nextNumber}` }
    );
  }, [open, editing, nextNumber, form, resetPick]);

  const save = async () => {
    const values = await form.validateFields().catch(() => null);
    if (!values) return;
    if (isCreate && !(pick.file && pick.meta)) {
      setError("请先选择要上传的视频文件");
      return;
    }
    if (pick.file && !pick.meta) {
      setError(pick.probing ? "正在读取视频信息，请稍候" : "视频信息无效，请重新选择文件");
      return;
    }
    setPhase("saving");
    setError(null);
    let session: UploadSession | null = null;
    try {
      let targetId = episodeId;
      if (targetId == null) {
        const created = await api<{ id: number }>(`/admin/v1/dramas/${dramaId}/episodes`, {
          method: "POST",
          body: JSON.stringify({
            episode_number: values.episode_number,
            title: (values.title ?? "").trim(),
          }),
        });
        targetId = created.id;
        setEpisodeId(targetId);
      } else {
        // Metadata edits (title / number) are a plain PATCH; the video file,
        // when picked, is swapped in afterwards through the upload session.
        await api(`/admin/v1/episodes/${targetId}`, {
          method: "PATCH",
          body: JSON.stringify({
            episode_number: values.episode_number,
            title: (values.title ?? "").trim(),
          }),
        });
      }
      if (pick.file && pick.meta) {
        setPhase("uploading");
        setProgress(0);
        session = await api<UploadSession>(`/admin/v1/episodes/${targetId}/upload-session`, {
          method: "POST",
          body: JSON.stringify({
            filename: pick.file.name,
            mime: pick.file.type || "video/mp4",
            size_bytes: pick.file.size,
            // Probed values only: never substitute a default resolution, it
            // would misreport landscape uploads as portrait.
            width: pick.meta.width,
            height: pick.meta.height,
            duration_seconds: pick.meta.durationSeconds,
          }),
        });
        await directUpload(session, pick.file, setProgress, (xhr) => {
          xhrRef.current = xhr;
        });
        setPhase("transcoding");
        const finalStatus = await waitForTranscode(targetId);
        if (finalStatus === "transcode_failed") {
          setError("Bunny 转码失败，请检查视频文件后重试");
          setPhase("idle");
          return;
        }
        if (finalStatus === "in_review") {
          message.success(
            isCreate
              ? `第 ${values.episode_number} 集上传完成，已进入审核中`
              : `第 ${values.episode_number} 集视频已替换；转码完成，需要重新上架`
          );
        } else {
          message.info("视频已上传，Bunny 仍在转码；稍后可在列表点「同步状态」");
        }
      } else {
        message.success("分集已更新");
      }
      onSaved();
      onClose();
    } catch (e) {
      if (session) {
        // Open the retry path: transcoding rows cannot start a new session.
        try {
          await api(`/admin/v1/episodes/${session.episode_id}/upload-failed`, {
            method: "POST",
            body: JSON.stringify({ video_id: session.video_id, reason: errorText(e) }),
          });
        } catch {
          /* best effort - the row can still be synced/pruned later */
        }
      }
      setError(errorText(e));
      setPhase("idle");
    }
  };

  const cancelUpload = () => {
    xhrRef.current?.abort();
  };

  const stopReplacing = () => {
    clearPick();
    setReplacing(false);
  };

  const primaryLabel = isCreate
    ? "开始上传"
    : pick.file
      ? editing?.bunny_video_id
        ? "保存并替换视频"
        : "保存并上传视频"
      : "保存";

  // ``editing`` is the row from when the dialog opened. Once a replace starts,
  // that badge is stale — a previous 转码失败 must not stay on the title while
  // the new file is uploading or already at Bunny.
  const liveStatus = phase === "uploading" || phase === "transcoding" ? "transcoding" : editing?.status;
  const statusMeta = liveStatus ? STATUS_META[liveStatus] ?? { text: liveStatus, color: "default" } : null;
  const hasVideo = Boolean(editing?.bunny_video_id);
  const showFile = Boolean(pick.file);
  const showDrop = canReplaceVideo && replacing && !showFile;
  const showCurrent = !isExternalEditing && hasVideo && !replacing && phase !== "uploading" && phase !== "transcoding";
  /** Failed transcodes have a Bunny id but nothing playable yet. */
  const canPreviewCurrent = isExternalEditing
    ? externalReady
    : Boolean(editing && editing.bunny_video_id && editing.status !== "transcode_failed");

  const infoSection = (
    <section className="ep-section">
      <div className="ep-kicker">基本信息</div>
      <Form form={form} layout="vertical" disabled={busy}>
        <div className="ep-fields">
          <Form.Item
            label="集数"
            name="episode_number"
            rules={[{ required: true, message: "请输入集数" }]}
            style={{ marginBottom: 0 }}
          >
            <InputNumber min={1} max={2000} style={{ width: "100%" }} />
          </Form.Item>
          <Form.Item
            label="标题"
            name="title"
            rules={[{ required: true, message: "请输入标题" }]}
            style={{ marginBottom: 0 }}
          >
            <Input placeholder="这一集的标题" maxLength={200} />
          </Form.Item>
        </div>
      </Form>
    </section>
  );

  const videoSection = (
    <section className="ep-section">
      <div className="ep-section-head">
        <div className="ep-kicker">
          视频
          {isCreate && <span className="ep-req">必填</span>}
        </div>
        {hasVideo && replacing && canReplaceVideo && phase === "idle" && (
          <Button type="link" size="small" className="ep-text-btn" onClick={stopReplacing}>
            取消替换
          </Button>
        )}
      </div>

      {!canReplaceVideo && !isExternalEditing && (
        <div className="ep-note">转码进行中，完成前不能替换视频。标题和集数可以先保存。</div>
      )}

      {editing?.status === "transcode_failed" && replacing && !showFile && (
        <div className="ep-note">上次转码失败。重新选择视频并保存后会再次转码，完成后进入审核。</div>
      )}

      {showCurrent && (
        <div className="ep-video-card">
          <div className="ep-video-mark" aria-hidden>
            <VideoCameraOutlined />
          </div>
          <div className="ep-video-copy">
            <div className="ep-video-title">当前视频</div>
            <div className="ep-video-sub">
              {editing?.duration_seconds ? `时长 ${formatDuration(editing.duration_seconds)}` : "时长未知"}
            </div>
          </div>
          <div className="ep-video-actions">
            {canPreviewCurrent && (
              <Button icon={<PlayCircleOutlined />} loading={previewing} onClick={previewCurrent}>
                预览
              </Button>
            )}
            {canReplaceVideo && (
              <Button disabled={busy} onClick={() => setReplacing(true)}>
                替换视频
              </Button>
            )}
          </div>
        </div>
      )}

      {isExternalEditing && (
        <>
          <div className="ep-video-card">
            <div className="ep-video-mark" aria-hidden>
              <LinkOutlined />
            </div>
            <div className="ep-video-copy">
              <div className="ep-video-title">外链视频 · {providerLabel(editing?.video_provider)}</div>
              <div className="ep-video-sub">
                {editing?.aspect_ratio || "16:9"}
                {editing?.duration_seconds ? ` · 时长 ${formatDuration(editing.duration_seconds)}` : ""}
                {" · "}
                {editing?.external_url ||
                  (editing?.external_video_id ? `ID ${editing.external_video_id}` : "未填写地址")}
              </div>
            </div>
            <div className="ep-video-actions">
              {canPreviewCurrent && (
                <Button icon={<PlayCircleOutlined />} loading={previewing} onClick={previewCurrent}>
                  预览
                </Button>
              )}
            </div>
          </div>
          <div className="ep-note">
            外链分集由第三方平台播放，不能上传文件，也无法强制付费或登录（流量优先）；需要扣费的剧请继续用 Bunny 源。修改来源请回列表点「编辑外链」。
          </div>
        </>
      )}

      {showDrop && (
        <VideoDrop
          pick={pick}
          disabled={busy}
          title={hasVideo ? "把新视频拖到这里，或点击选择" : "把视频拖到这里，或点击选择"}
          hint={
            hasVideo
              ? "MP4、MOV · 不超过 200MB。保存后会重新转码，完成前不能播放"
              : "MP4、MOV · 横版或竖版 · 不超过 200MB · 直传 Bunny"
          }
        />
      )}

      {showFile && (
        <VideoFileCard
          pick={pick}
          onClear={clearPick}
          onPreview={previewPicked}
          clearDisabled={busy}
          progress={phase === "uploading" ? progress : null}
        />
      )}

      {pick.meta && hasVideo && phase === "idle" && (
        <div className="ep-warn">保存后会立即重新转码，期间不能播放，完成后需要重新上架。</div>
      )}

      {pick.error && <div className="ep-error">{pick.error}</div>}
    </section>
  );

  return (
    <ConfigProvider
      theme={{
        token: { colorPrimary: "#E53B3E", borderRadius: 8 },
        components: {
          Input: {
            activeBorderColor: "#1f1f1f",
            hoverBorderColor: "#bfbfbf",
            activeShadow: "0 0 0 2px rgba(31, 31, 31, 0.06)",
          },
          InputNumber: {
            activeBorderColor: "#1f1f1f",
            hoverBorderColor: "#bfbfbf",
            activeShadow: "0 0 0 2px rgba(31, 31, 31, 0.06)",
          },
        },
      }}
    >
      <Modal
        rootClassName="ep-editor-root"
        open={open}
        title={
          <span className="ep-title">
            {editing ? `编辑第 ${editing.episode_number} 集` : "上传新分集"}
            {statusMeta ? <Tag color={statusMeta.color}>{statusMeta.text}</Tag> : null}
          </span>
        }
        width={600}
        maskClosable={!locked && !preview.open}
        keyboard={!preview.open}
        closable={!locked}
        onCancel={locked ? undefined : onClose}
        styles={{ body: { paddingTop: 8 } }}
        footer={
          <div className="ep-footer">
            {locked ? (
              <Button danger onClick={cancelUpload}>
                取消上传
              </Button>
            ) : phase === "transcoding" ? (
              <Button onClick={onClose}>关闭</Button>
            ) : (
              <>
                <Button onClick={onClose} disabled={phase === "saving"}>
                  取消
                </Button>
                <Button
                  type="primary"
                  loading={phase === "saving"}
                  disabled={pick.probing || (isCreate && !pick.file)}
                  onClick={save}
                >
                  {primaryLabel}
                </Button>
              </>
            )}
          </div>
        }
      >
        <div className="ep-editor">
          {isCreate ? videoSection : infoSection}
          {isCreate ? infoSection : videoSection}
          {phase === "transcoding" && (
            <Alert
              type="info"
              showIcon
              message="文件已上传，Bunny 正在转码"
              description="转码完成后会自动进入审核中。列表里的旧状态可以先忽略，关闭后会刷新。"
            />
          )}
          {error && <Alert type="error" showIcon message={error} />}
        </div>
      </Modal>
      <PreviewModal preview={preview} onClose={closePreview} />
    </ConfigProvider>
  );
}

type ExternalFormValues = {
  episode_number: number;
  title: string;
  video_provider: Provider;
  external_video_id?: string;
  external_url?: string;
  aspect_ratio: string;
  duration_seconds?: number | null;
};

/**
 * Create / edit an episode whose bytes live on a third-party platform.
 *
 * External rows are a traffic-first choice: the platform player owns the
 * stream, so neither coins nor membership can be enforced. Operators create
 * them here, then publish through the normal review flow.
 */
function ExternalEpisodeModal({
  open,
  dramaId,
  episodes,
  editing,
  onClose,
  onSaved,
}: {
  open: boolean;
  dramaId: string;
  episodes: EpisodeRow[];
  editing: EpisodeRow | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form] = Form.useForm<ExternalFormValues>();
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const provider = (Form.useWatch("video_provider", form) as Provider | undefined) ?? "youtube";
  const videoId = (Form.useWatch("external_video_id", form) as string | undefined) ?? "";
  const url = (Form.useWatch("external_url", form) as string | undefined) ?? "";
  const aspect = (Form.useWatch("aspect_ratio", form) as string | undefined) ?? "";
  const meta = PROVIDER_META[provider];

  const nextNumber = useMemo(
    () => episodes.reduce((max, row) => Math.max(max, row.episode_number), 0) + 1,
    [episodes]
  );

  useEffect(() => {
    if (!open) return;
    setError(null);
    if (editing) {
      const provider = (
        isExternalProvider(editing.video_provider) ? editing.video_provider : "youtube"
      ) as Provider;
      form.setFieldsValue({
        episode_number: editing.episode_number,
        title: editing.title,
        video_provider: provider,
        external_video_id: editing.external_video_id ?? "",
        external_url: editing.external_url ?? "",
        aspect_ratio: editing.aspect_ratio || PROVIDER_META[provider].aspect,
        duration_seconds: editing.duration_seconds || 0,
      });
    } else {
      form.setFieldsValue({
        episode_number: nextNumber,
        title: `Episode ${nextNumber}`,
        video_provider: "youtube",
        external_video_id: "",
        external_url: "",
        aspect_ratio: "16:9",
        duration_seconds: 0,
      });
    }
  }, [open, editing, nextNumber, form]);

  const onProviderChange = (next: Provider) => {
    // A link/ID for the previous platform is almost never valid for the next
    // one, and a stale value would be silently accepted by the backend.
    form.setFieldsValue({
      video_provider: next,
      external_video_id: "",
      external_url: "",
      aspect_ratio: PROVIDER_META[next].aspect,
    });
    setError(null);
  };

  const preview = externalPreview(provider, videoId, url, aspect || meta.aspect);

  const save = async () => {
    const values = await form.validateFields().catch(() => null);
    if (!values) return;
    const id = (values.external_video_id ?? "").trim();
    const link = (values.external_url ?? "").trim();
    if (meta.urlRequired && !link) {
      setError(`${meta.label} 需要可嵌入的 https 链接`);
      return;
    }
    if (!id && !link) {
      setError(`${meta.label} 需要视频 ID 或 https 链接`);
      return;
    }
    if (link && !/^https:\/\//i.test(link)) {
      setError("链接必须是 https 地址");
      return;
    }
    setSaving(true);
    setError(null);
    const body: Record<string, unknown> = {
      episode_number: values.episode_number,
      title: (values.title ?? "").trim(),
      video_provider: values.video_provider,
      external_video_id: id,
      external_url: link,
      aspect_ratio: values.aspect_ratio,
    };
    try {
      if (editing) {
        await api(`/admin/v1/episodes/${editing.id}`, {
          method: "PATCH",
          body: JSON.stringify(body),
        });
        message.success(`第 ${values.episode_number} 集外链已更新`);
      } else {
        await api(`/admin/v1/dramas/${dramaId}/episodes`, {
          method: "POST",
          body: JSON.stringify({
            ...body,
            duration_seconds: Math.max(0, Math.round(Number(values.duration_seconds ?? 0))),
          }),
        });
        message.success(`第 ${values.episode_number} 集外链已添加，处于审核中，可上架`);
      }
      onSaved();
      onClose();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setSaving(false);
    }
  };

  const previewFrameAspect = (aspect || meta.aspect).replace(":", " / ");

  return (
    <Modal
      open={open}
      title={editing ? `编辑第 ${editing.episode_number} 集 · 外链` : "添加外链分集"}
      width={600}
      closable={!saving}
      maskClosable={!saving}
      onCancel={saving ? undefined : onClose}
      styles={{ body: { paddingTop: 8 } }}
      footer={
        <div className="ep-footer">
          <Button onClick={onClose} disabled={saving}>
            取消
          </Button>
          <Button type="primary" loading={saving} onClick={save}>
            保存
          </Button>
        </div>
      }
    >
      <div className="ep-editor">
        <section className="ep-section">
          <div className="ep-kicker">基本信息</div>
          <Form form={form} layout="vertical" disabled={saving}>
            <div className="ep-fields">
              <Form.Item
                label="集数"
                name="episode_number"
                rules={[{ required: true, message: "请输入集数" }]}
                style={{ marginBottom: 0 }}
              >
                <InputNumber min={1} max={2000} style={{ width: "100%" }} />
              </Form.Item>
              <Form.Item
                label="标题"
                name="title"
                rules={[{ required: true, message: "请输入标题" }]}
                style={{ marginBottom: 0 }}
              >
                <Input placeholder="这一集的标题" maxLength={200} />
              </Form.Item>
            </div>
          </Form>
        </section>

        <section className="ep-section">
          <div className="ep-kicker">视频来源</div>
          <Form form={form} layout="vertical" disabled={saving}>
            <Form.Item label="平台" name="video_provider" style={{ marginBottom: 12 }}>
              <Select options={PROVIDER_OPTIONS} onChange={onProviderChange} />
            </Form.Item>
            {meta.idLabel && (
              <Form.Item label={meta.idLabel} name="external_video_id" style={{ marginBottom: 12 }}>
                <Input placeholder="留空时从链接中自动识别" allowClear />
              </Form.Item>
            )}
            <Form.Item label={meta.urlLabel} name="external_url" style={{ marginBottom: 12 }}>
              <Input placeholder="https://…" allowClear />
            </Form.Item>
            <div className="ep-note" style={{ marginBottom: 12 }}>
              {meta.urlHint}
            </div>
            <div className="ep-fields">
              <Form.Item label="画面比例" name="aspect_ratio" style={{ marginBottom: 0 }}>
                <Select options={ASPECT_OPTIONS} />
              </Form.Item>
              {!editing && (
                <Form.Item
                  label="时长（秒，可选）"
                  name="duration_seconds"
                  style={{ marginBottom: 0 }}
                >
                  <InputNumber min={0} max={86400} style={{ width: "100%" }} placeholder="例如 90" />
                </Form.Item>
              )}
            </div>
          </Form>
        </section>

        <section className="ep-section">
          <div className="ep-kicker">预览</div>
          {preview ? (
            preview.kind === "embed" ? (
              <div
                style={{
                  position: "relative",
                  width: preview.aspect === "9:16" ? "min(100%, 220px)" : "100%",
                  margin: "0 auto",
                  aspectRatio: previewFrameAspect,
                  maxHeight: 420,
                  overflow: "hidden",
                  borderRadius: 10,
                  background: "#000",
                }}
              >
                <iframe
                  title="外链视频预览"
                  src={preview.url}
                  style={{ position: "absolute", inset: 0, width: "100%", height: "100%", border: 0 }}
                  allow="accelerometer; autoplay; encrypted-media; picture-in-picture; fullscreen"
                  allowFullScreen
                  referrerPolicy="strict-origin-when-cross-origin"
                />
              </div>
            ) : preview.kind === "video" ? (
              <video
                src={preview.url}
                controls
                playsInline
                style={{ display: "block", width: "100%", maxHeight: 320, background: "#000", borderRadius: 10 }}
              />
            ) : (
              <div className="ep-note">HLS 地址需在 H5/官网播放器中预览：{preview.url}</div>
            )
          ) : (
            <div className="ep-note">
              填入链接后这里会显示预览。若第三方平台禁止嵌入，预览可能显示拒绝连接，H5 端也可能无法播放。
            </div>
          )}
        </section>

        <div className="ep-note">
          外链视频不参与付费/会员拦截（第三方播放器自己取流）；需要扣费的剧请继续用 Bunny 源。上架仍需审核通过。
        </div>
        {error && <Alert type="error" showIcon message={error} />}
      </div>
    </Modal>
  );
}

export default function EpisodesPanel({ dramaId }: { dramaId: string }) {
  const [rows, setRows] = useState<EpisodeRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [status, setStatus] = useState<string | undefined>(undefined);
  const [editorOpen, setEditorOpen] = useState(false);
  /** Bumps on each open so the dialog remounts with a fresh replace/upload state. */
  const [editorNonce, setEditorNonce] = useState(0);
  /** null means the editor is in "upload new episode" mode. */
  const [editing, setEditing] = useState<EpisodeRow | null>(null);
  const [externalOpen, setExternalOpen] = useState(false);
  /** null means the external dialog creates a new row. */
  const [externalEditing, setExternalEditing] = useState<EpisodeRow | null>(null);
  const [externalNonce, setExternalNonce] = useState(0);
  const [preview, setPreview] = useState<PreviewState>({ open: false });
  const [selectedKeys, setSelectedKeys] = useState<number[]>([]);
  const [bulkBusy, setBulkBusy] = useState(false);
  const [batchOpen, setBatchOpen] = useState(false);
  const canWrite = useCanWriteContent();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const query = new URLSearchParams({ page_size: "200" });
      if (status) query.set("status", status);
      const res = await api<{ items: EpisodeRow[] }>(
        `/admin/v1/dramas/${dramaId}/episodes?${query.toString()}`
      );
      setRows(res.items);
    } catch (e) {
      message.error(`加载分集失败：${errorText(e)}`);
    } finally {
      setLoading(false);
    }
  }, [dramaId, status]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    // Batch uploads run outside this component: refresh the table when an
    // item settles, coalescing bursts (upload finish → transcode → done).
    let timer: number | null = null;
    const unsubscribe = batchQueue.onSettled(() => {
      if (timer != null) return;
      timer = window.setTimeout(() => {
        timer = null;
        load();
      }, 800);
    });
    return () => {
      unsubscribe();
      if (timer != null) window.clearTimeout(timer);
    };
  }, [load]);

  const openEditor = (row: EpisodeRow | null) => {
    setEditing(row);
    setEditorNonce((nonce) => nonce + 1);
    setEditorOpen(true);
  };

  const openExternal = (row: EpisodeRow | null) => {
    setExternalEditing(row);
    setExternalNonce((nonce) => nonce + 1);
    setExternalOpen(true);
  };

  const openPreview = async (row: EpisodeRow) => {
    try {
      const signed = await api<{
        url?: string;
        embed_url?: string;
        type?: string;
        aspect_ratio?: string;
      }>(`/admin/v1/episodes/${row.id}/preview-url`);
      const title = `预览 · 第 ${row.episode_number} 集`;
      const type = signed.type ?? "embed";
      const aspect = signed.aspect_ratio || row.aspect_ratio || "9:16";
      if (type === "embed") {
        const url = signed.embed_url ?? signed.url ?? "";
        if (!url) throw new Error("没有可预览的地址");
        setPreview({ open: true, kind: "embed", url, aspect, title });
      } else if (type === "mp4") {
        const url = signed.url ?? "";
        if (!url) throw new Error("没有可预览的地址");
        setPreview({ open: true, kind: "video", url, aspect, title });
      } else {
        const url = signed.url ?? signed.embed_url ?? "";
        if (!url) throw new Error("没有可预览的地址");
        setPreview({ open: true, kind: "link", url, title });
      }
    } catch (e) {
      message.error(`预览失败：${errorText(e)}`);
    }
  };

  /**
   * Drop the external source and fall back to a Bunny upload slot. The row
   * keeps its number/title and goes back to 未上传, so the operator can upload
   * a file through the normal editor afterwards.
   */
  const convertToBunny = async (row: EpisodeRow) => {
    try {
      await api(`/admin/v1/episodes/${row.id}`, {
        method: "PATCH",
        body: JSON.stringify({ video_provider: "bunny" }),
      });
      message.success(`第 ${row.episode_number} 集已改为 Bunny 源，请上传视频`);
      load();
    } catch (e) {
      message.error(`切换来源失败：${errorText(e)}`);
    }
  };

  const syncStatus = async (row: EpisodeRow) => {
    try {
      const res = await api<{ status: string }>(`/admin/v1/episodes/${row.id}/sync-status`, { method: "POST" });
      const meta = STATUS_META[res.status];
      message.success(`第 ${row.episode_number} 集状态：${meta?.text ?? res.status}`);
      load();
    } catch (e) {
      message.error(`同步失败：${errorText(e)}`);
    }
  };

  const publish = async (row: EpisodeRow) => {
    try {
      await api(`/admin/v1/episodes/${row.id}/publish`, { method: "POST" });
      message.success(`第 ${row.episode_number} 集已上架`);
      load();
    } catch (e) {
      message.error(`上架失败：${errorText(e)}`);
    }
  };

  const unpublish = async (row: EpisodeRow) => {
    try {
      await api(`/admin/v1/episodes/${row.id}/unpublish`, { method: "POST" });
      message.success(`第 ${row.episode_number} 集已下架`);
      load();
    } catch (e) {
      message.error(`下架失败：${errorText(e)}`);
    }
  };

  const bulkStatus = async (action: "publish" | "unpublish") => {
    if (selectedKeys.length === 0) return;
    setBulkBusy(true);
    const label = action === "publish" ? "上架" : "下架";
    try {
      const res = await api<{ updated: unknown[]; skipped: unknown[] }>(
        "/admin/v1/episodes/bulk-status",
        {
          method: "POST",
          body: JSON.stringify({ episode_ids: selectedKeys, action }),
        }
      );
      const skipped = res.skipped.length;
      message.success(
        `批量${label}完成：成功 ${res.updated.length} 集${skipped ? `，跳过 ${skipped} 集（状态不符或缺少视频）` : ""}`
      );
      setSelectedKeys([]);
      load();
    } catch (e) {
      message.error(`批量${label}失败：${errorText(e)}`);
    } finally {
      setBulkBusy(false);
    }
  };

  return (
    <Space direction="vertical" style={{ width: "100%" }}>
      <Space wrap size="middle">
        {canWrite && (
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => openEditor(null)}
          >
            上传新分集
          </Button>
        )}
        {canWrite && (
          <Button icon={<LinkOutlined />} onClick={() => openExternal(null)}>
            外链分集
          </Button>
        )}
        {canWrite && <BatchUploadButton onClick={() => setBatchOpen(true)} />}
        <Select
          allowClear
          placeholder="状态筛选"
          style={{ width: 160 }}
          value={status}
          onChange={(value) => setStatus(value)}
          options={Object.entries(STATUS_META).map(([value, meta]) => ({ value, label: meta.text }))}
        />
        <Button icon={<ReloadOutlined />} onClick={() => load()}>
          刷新
        </Button>
        <Typography.Text type="secondary">共 {rows.length} 集</Typography.Text>
        {selectedKeys.length > 0 && (
          <>
            <Typography.Text>已选 {selectedKeys.length} 集</Typography.Text>
            <Button
              type="primary"
              disabled={!canWrite}
              loading={bulkBusy}
              onClick={() => bulkStatus("publish")}
            >
              批量上架
            </Button>
            <Button
              danger
              disabled={!canWrite}
              loading={bulkBusy}
              onClick={() => bulkStatus("unpublish")}
            >
              批量下架
            </Button>
            <Button type="link" onClick={() => setSelectedKeys([])}>
              取消选择
            </Button>
          </>
        )}
      </Space>

      <Table<EpisodeRow>
        rowKey="id"
        loading={loading}
        dataSource={rows}
        pagination={{ pageSize: 20 }}
        scroll={{ x: 880 }}
        rowSelection={{
          selectedRowKeys: selectedKeys,
          onChange: (keys) => setSelectedKeys(keys as number[]),
          getCheckboxProps: (row) => ({
            // Nothing to publish/take down until some playable source exists.
            disabled: !row.bunny_video_id && !row.external_video_id && !row.external_url,
          }),
        }}
        columns={[
          { title: "集", dataIndex: "episode_number", width: 70 },
          {
            title: "标题",
            dataIndex: "title",
            ellipsis: true,
            render: (value: string) =>
              value || <Typography.Text type="secondary">未命名</Typography.Text>,
          },
          { title: "时长", dataIndex: "duration_seconds", width: 90, render: (value: number) => formatDuration(value) },
          {
            title: "来源",
            dataIndex: "video_provider",
            width: 130,
            render: (value: string | undefined, row) => {
              const external = isExternalProvider(value);
              return (
                <Space size={4} wrap>
                  <Tag color={external ? "blue" : "default"}>{providerLabel(value)}</Tag>
                  {external && (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {row.aspect_ratio || "16:9"}
                    </Typography.Text>
                  )}
                </Space>
              );
            },
          },
          {
            title: "状态",
            dataIndex: "status",
            width: 110,
            render: (value: string) => {
              const meta = STATUS_META[value] ?? { text: value, color: "default" };
              return <Tag color={meta.color}>{meta.text}</Tag>;
            },
          },
          {
            title: "操作",
            width: 320,
            render: (_, row) => {
              const external = isExternalProvider(row.video_provider);
              const hasExternal = Boolean(row.external_video_id || row.external_url);
              const canPreview = external
                ? hasExternal
                : Boolean(row.bunny_video_id) && row.status !== "transcode_failed";
              return (
                <Space size={12} wrap>
                  {canPreview && (
                    <Button size="small" type="link" style={{ padding: 0 }} onClick={() => openPreview(row)}>
                      预览
                    </Button>
                  )}
                  {!external && row.status === "transcoding" && (
                    <Button size="small" type="link" style={{ padding: 0 }} icon={<SyncOutlined />} onClick={() => syncStatus(row)}>
                      同步状态
                    </Button>
                  )}
                  {canWrite && !external && (!row.bunny_video_id || row.status === "transcode_failed") && (
                    <Button
                      size="small"
                      type={row.status === "transcode_failed" ? "link" : "primary"}
                      danger={row.status === "transcode_failed"}
                      style={row.status === "transcode_failed" ? { padding: 0 } : undefined}
                      onClick={() => openEditor(row)}
                    >
                      {row.status === "transcode_failed" ? "重新上传" : "上传"}
                    </Button>
                  )}
                  {canWrite && (
                    <Button
                      size="small"
                      type="link"
                      style={{ padding: 0 }}
                      onClick={() => (external ? openExternal(row) : openEditor(row))}
                    >
                      {external ? "编辑外链" : "编辑"}
                    </Button>
                  )}
                  {canWrite && external && (
                    <Popconfirm
                      title={`改为 Bunny 上传？第 ${row.episode_number} 集的外链会清空，之后需要重新上传视频。`}
                      onConfirm={() => convertToBunny(row)}
                    >
                      <Button size="small" type="link" style={{ padding: 0 }}>
                        改为上传
                      </Button>
                    </Popconfirm>
                  )}
                  {row.status === "in_review" && canWrite && (
                    <Popconfirm title={`上架第 ${row.episode_number} 集？`} onConfirm={() => publish(row)}>
                      <Button size="small" type="link" style={{ padding: 0 }}>
                        上架
                      </Button>
                    </Popconfirm>
                  )}
                  {row.status === "unpublished" && canWrite && (
                    <Popconfirm title={`重新上架第 ${row.episode_number} 集？`} onConfirm={() => publish(row)}>
                      <Button size="small" type="link" style={{ padding: 0 }}>
                        上架
                      </Button>
                    </Popconfirm>
                  )}
                  {row.status === "published" && canWrite && (
                    <Popconfirm title={`下架第 ${row.episode_number} 集？`} onConfirm={() => unpublish(row)}>
                      <Button size="small" type="link" danger style={{ padding: 0 }}>
                        下架
                      </Button>
                    </Popconfirm>
                  )}
                </Space>
              );
            },
          },
        ]}
      />

      <EpisodeEditorModal
        key={`editor-${editorNonce}`}
        open={editorOpen}
        dramaId={dramaId}
        episodes={rows}
        editing={editing}
        onClose={() => {
          setEditorOpen(false);
          load();
        }}
        onSaved={load}
      />

      <ExternalEpisodeModal
        key={`external-${externalNonce}`}
        open={externalOpen}
        dramaId={dramaId}
        episodes={rows}
        editing={externalEditing}
        onClose={() => {
          setExternalOpen(false);
          load();
        }}
        onSaved={load}
      />

      <PreviewModal preview={preview} onClose={() => setPreview({ open: false })} />

      <BatchUploadModal
        open={batchOpen}
        onClose={() => setBatchOpen(false)}
        dramaId={dramaId}
        existingNumbers={rows.map((row) => row.episode_number)}
      />

      <BatchUploadPill dramaId={dramaId} hidden={batchOpen} onOpen={() => setBatchOpen(true)} />
    </Space>
  );
}
