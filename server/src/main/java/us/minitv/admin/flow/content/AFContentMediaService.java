package us.minitv.admin.flow.content;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.admin.flow.content.mapper.AFContentDramaMapper;
import us.minitv.admin.flow.content.mapper.AFContentEpisodeMapper;
import us.minitv.common.ApiException;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.EpisodeRow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Media assets: drama covers and episode subtitles, ported from the
 * {@code ---- media assets ----} section of
 * {@code backend/app/modules/ops/flow_router.py}.
 *
 * <p>Bytes go to Bunny Storage (image CDN); in fake mode both endpoints answer
 * 503 exactly like Python — the admin console falls back to a plain cover URL.</p>
 */
@Service
public class AFContentMediaService {

    static final long COVER_MAX_BYTES = 5L * 1024 * 1024;
    static final long SUBTITLE_MAX_BYTES = 2L * 1024 * 1024;
    static final List<String> COVER_EXTS = List.of("jpg", "png", "webp");
    private static final Map<String, String> COVER_MIME = Map.of(
            "jpg", "image/jpeg", "png", "image/png", "webp", "image/webp");
    private static final Set<String> SUBTITLE_EXTS = Set.of(".vtt", ".srt");

    private final AFContentDramaMapper dramas;
    private final AFContentEpisodeMapper episodes;
    private final AFContentBunny bunny;

    public AFContentMediaService(AFContentDramaMapper dramas,
                                 AFContentEpisodeMapper episodes,
                                 AFContentBunny bunny) {
        this.dramas = dramas;
        this.episodes = episodes;
        this.bunny = bunny;
    }

    /** {@code upload_cover}: sniffed bytes win over the browser Content-Type. */
    @Transactional
    public Map<String, Object> uploadCover(long dramaId, byte[] data) {
        DramaRow drama = requireDrama(dramaId);
        if (!bunny.live()) {
            throw new ApiException(503, "图片存储未配置，可先填写封面链接");
        }
        if (data == null || data.length == 0 || data.length > COVER_MAX_BYTES) {
            throw ApiException.unprocessable("封面大小需在 1 字节到 5MB 之间");
        }
        String ext = sniffCoverExt(data);
        if (ext == null) {
            throw ApiException.unprocessable("封面只支持 JPG、PNG、WebP");
        }
        String remotePath = coverRemote(drama.getId(), ext);
        try {
            bunny.storageUpload(remotePath, data, COVER_MIME.get(ext));
        } catch (AFContentBunny.BunnyError exc) {
            throw new ApiException(502, "封面上传失败：" + exc.getMessage());
        }
        String coverUrl = bunny.imageUrl(remotePath) + "?v=" + System.nanoTime();
        dramas.updateCoverUrl(drama.getId(), coverUrl);
        drama.setCoverUrl(coverUrl);
        purgeCover(drama.getId(), ext);
        forgetOtherCovers(drama.getId(), ext);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cover_url", coverUrl);
        return result;
    }

    /** {@code delete_cover}: clears the URL; best-effort storage cleanup in real mode. */
    @Transactional
    public Map<String, Object> deleteCover(long dramaId) {
        DramaRow drama = requireDrama(dramaId);
        dramas.updateCoverUrl(drama.getId(), "");
        drama.setCoverUrl("");
        forgetOtherCovers(drama.getId(), null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cover_url", "");
        return result;
    }

    /** {@code upload_subtitle}: stores the track under {@code subs/<episode>/sub<ext>}. */
    @Transactional
    public Map<String, Object> uploadSubtitle(long episodeId, byte[] data, String originalFilename) {
        EpisodeRow episode = episodes.findById(episodeId);
        if (episode == null) {
            throw new ApiException(404, "episode not found");
        }
        if (!bunny.live()) {
            throw new ApiException(503, "bunny storage not configured (BUNNY_MODE=fake)");
        }
        if (data == null || data.length == 0 || data.length > SUBTITLE_MAX_BYTES) {
            throw ApiException.unprocessable("subtitle must be between 1 byte and 2MB");
        }
        String name = basename(originalFilename == null || originalFilename.isEmpty()
                ? "subtitle.vtt" : originalFilename);
        String ext = extension(name).toLowerCase();
        if (!SUBTITLE_EXTS.contains(ext)) {
            throw ApiException.unprocessable("subtitle must be .vtt or .srt");
        }
        String remotePath = "subs/" + episode.getId() + "/sub" + ext;
        String contentType = ".vtt".equals(ext) ? "text/vtt" : "application/x-subrip";
        try {
            bunny.storageUpload(remotePath, data, contentType);
        } catch (AFContentBunny.BunnyError exc) {
            throw new ApiException(502, "bunny storage: " + exc.getMessage());
        }
        episodes.updateSubtitleUrl(episode.getId(), remotePath);
        episode.setSubtitleUrl(remotePath);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("subtitle_url", remotePath);
        return result;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private DramaRow requireDrama(long dramaId) {
        DramaRow drama = dramas.findById(dramaId);
        if (drama == null) {
            throw new ApiException(404, "drama not found");
        }
        return drama;
    }

    /** {@code _sniff_cover_ext}: trust the bytes, not the Content-Type. */
    static String sniffCoverExt(byte[] data) {
        if (data == null || data.length < 4) {
            return null;
        }
        if (data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8
                && (data[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N'
                && data[3] == 'G' && (data[4] & 0xFF) == 0x0D && (data[5] & 0xFF) == 0x0A
                && (data[6] & 0xFF) == 0x1A && (data[7] & 0xFF) == 0x0A) {
            return "png";
        }
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "webp";
        }
        return null;
    }

    private static String coverRemote(long dramaId, String ext) {
        return "covers/" + dramaId + "/cover." + ext;
    }

    private void purgeCover(long dramaId, String ext) {
        if (!bunny.live()) {
            return;
        }
        String url;
        try {
            url = bunny.imageUrl(coverRemote(dramaId, ext));
        } catch (AFContentBunny.BunnyError exc) {
            return;
        }
        bestEffort(() -> bunny.purgeUrl(url));
        bestEffort(() -> bunny.purgeUrl(url + "*"));
    }

    private void forgetOtherCovers(long dramaId, String keepExt) {
        for (String ext : COVER_EXTS) {
            if (ext.equals(keepExt)) {
                continue;
            }
            bestEffort(() -> bunny.storageDelete(coverRemote(dramaId, ext)));
            purgeCover(dramaId, ext);
        }
    }

    private void bestEffort(Runnable action) {
        if (!bunny.live()) {
            return;
        }
        try {
            action.run();
        } catch (AFContentBunny.BunnyError ignored) {
            // storage cleanup is best effort; a purge failure must not fail the write
        }
    }

    private static String basename(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }
}
