import { useEffect, useRef, useState } from "react";
import { postJson } from "../api";
import { IconPlayCenter } from "../icons";
import type { PlaybackAuth } from "../types";
import { attachHls, type PlayerHandle } from "./hls";

interface Props {
  dramaId: number;
  episodeId: number;
  resumeSeconds?: number;
  active: boolean;
  /** When false the view never asks for a URL (locked episodes). */
  playable: boolean;
  playbackSpeed?: number;
  quality?: string;
  onQualitiesChange?: (qualities: string[]) => void;
  onFinished?: () => void;
}

interface EmbedState {
  url: string;
  aspect: string;
}

/**
 * Mints a signed URL only while this slot is the one on screen, then reports
 * start / 15s progress / complete. Analytics failures never stop playback.
 * Tokens live five minutes, so a URL fetched with the list would already be dead.
 */
export function EpisodeView({
  dramaId,
  episodeId,
  resumeSeconds = 0,
  active,
  playable,
  playbackSpeed = 1.0,
  quality = "Auto",
  onQualitiesChange,
  onFinished,
}: Props) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const handleRef = useRef<PlayerHandle | null>(null);
  const [paused, setPaused] = useState(false);
  const [progress, setProgress] = useState(0);
  const [failed, setFailed] = useState(false);
  const [embed, setEmbed] = useState<EmbedState | null>(null);
  /** width / height reported by the media element; null until metadata loads. */
  const [videoAspect, setVideoAspect] = useState<number | null>(null);
  const onFinishedRef = useRef(onFinished);
  onFinishedRef.current = onFinished;
  const onQualitiesChangeRef = useRef(onQualitiesChange);
  onQualitiesChangeRef.current = onQualitiesChange;

  useEffect(() => {
    if (!active || !playable) return;
    let handle: PlayerHandle | null = null;
    let heartbeat = 0;
    let cancelled = false;
    let finished = false;
    const listeners = new AbortController();

    const report = (kind: "start" | "progress" | "complete", position: number) => {
      postJson(`/v1/playback/${kind}`, { episode_id: episodeId, position_seconds: position }).catch(() => undefined);
    };

    (async () => {
      try {
        const auth = await postJson<PlaybackAuth>("/v1/playback", { drama_id: dramaId, episode_id: episodeId });
        if (cancelled) return;
        if (!auth.allowed || (!auth.url && auth.type !== "embed")) {
          if (auth.allowed === false) setFailed(true);
          return;
        }
        if (auth.type === "embed") {
          // Platform players (YouTube / Facebook / TikTok / 抖音 / iframe) own
          // the playback UI and cannot report progress reliably; count the open.
          setEmbed({ url: auth.embed_url ?? "", aspect: auth.aspect_ratio || "16:9" });
          report("start", 0);
          return;
        }
        setEmbed(null);
        const video = videoRef.current;
        if (!video) return;

        const finish = () => {
          if (finished) return;
          finished = true;
          window.clearInterval(heartbeat);
          report("complete", Math.floor(video.duration || 0));
          onFinishedRef.current?.();
        };
        const onTime = () => {
          if (!video.duration) return;
          setProgress(video.currentTime / video.duration);
          if (video.currentTime >= video.duration - 1.5) finish();
        };
        const start = () => {
          if (resumeSeconds > 0) video.currentTime = resumeSeconds;
          video.playbackRate = playbackSpeed;
          video.play().catch(() => setPaused(true));
          report("start", resumeSeconds);
          heartbeat = window.setInterval(() => report("progress", Math.floor(video.currentTime || 0)), 15000);
        };
        video.addEventListener("timeupdate", onTime, { signal: listeners.signal });
        video.addEventListener("ended", finish, { signal: listeners.signal });
        video.addEventListener(
          "loadedmetadata",
          () => {
            start();
            handle?.setQuality(quality);
          },
          { once: true, signal: listeners.signal }
        );

        if (auth.type === "mp4") {
          // Plain progressive file served by any CDN.
          video.src = auth.url as string;
          return;
        }

        handle = attachHls(video, auth.url as string, (levels) => {
          onQualitiesChangeRef.current?.(levels);
        });
        handleRef.current = handle;
        if (quality) {
          handle.setQuality(quality);
        }
        if (video.readyState >= 1) start();
      } catch {
        if (!cancelled) setFailed(true);
      }
    })();

    return () => {
      cancelled = true;
      window.clearInterval(heartbeat);
      listeners.abort();
      handle?.destroy();
      handleRef.current = null;
    };
  }, [active, playable, dramaId, episodeId, resumeSeconds]);

  useEffect(() => {
    setEmbed(null);
    setFailed(false);
    setProgress(0);
    setVideoAspect(null);
  }, [episodeId]);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    video.playbackRate = playbackSpeed;
  }, [playbackSpeed]);

  useEffect(() => {
    if (handleRef.current && quality) {
      handleRef.current.setQuality(quality);
    }
  }, [quality]);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    if (!active) {
      video.pause();
      return;
    }
    if (!paused) {
      video.playbackRate = playbackSpeed;
      video.play().catch(() => undefined);
    }
  }, [active, paused, playbackSpeed]);

  const toggle = () => {
    const video = videoRef.current;
    if (!video || !playable) return;
    if (video.paused) {
      setPaused(false);
      video.play().catch(() => undefined);
    } else {
      setPaused(true);
      video.pause();
    }
  };

  // Landscape sources (HLS/MP4 with width > height, or a landscape embed) keep
  // their real ratio in a centred letterbox band. Portrait sources keep the
  // full-bleed vertical stage the feed is designed around.
  const mediaAspect = embed ? aspectNumber(embed.aspect) : videoAspect;
  const contained = mediaAspect != null && mediaAspect > 1.02;

  return (
    <>
      <div
        className={`media-box${contained ? " is-contained" : ""}`}
        style={contained && mediaAspect != null ? { aspectRatio: mediaAspect } : undefined}
      >
        {embed ? (
          <iframe
            src={embed.url}
            title="video"
            allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
            allowFullScreen
            referrerPolicy="strict-origin-when-cross-origin"
          />
        ) : (
          <video
            ref={videoRef}
            playsInline
            onClick={toggle}
            onLoadedMetadata={(event) => {
              const video = event.currentTarget;
              if (video.videoWidth > 0 && video.videoHeight > 0) {
                setVideoAspect(video.videoWidth / video.videoHeight);
              }
            }}
          />
        )}
      </div>
      {failed && <div className="lock">Could not play</div>}
      {!embed && paused && playable && (
        <button
          type="button"
          className="play-pause-overlay"
          aria-label="Play"
          onClick={toggle}
        >
          <span className="play-pause-badge">
            <IconPlayCenter size={34} />
          </span>
        </button>
      )}
      {!embed && <div className="progress"><b style={{ width: `${progress * 100}%` }} /></div>}
    </>
  );
}

/** "9:16" → 0.5625; malformed values return null. */
function aspectNumber(value: string): number | null {
  const parts = value.split(":");
  if (parts.length !== 2) return null;
  const width = Number(parts[0]);
  const height = Number(parts[1]);
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return null;
  return width / height;
}
