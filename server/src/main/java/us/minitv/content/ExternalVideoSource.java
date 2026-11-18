package us.minitv.content;

import us.minitv.common.ApiException;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalises an episode's video source.
 *
 * <p>{@code bunny} keeps the existing signed-HLS pipeline. Everything else is
 * an external source rendered by the platform's own player ({@code embed}) or
 * by an ordinary HTML5 player ({@code hls}/{@code mp4}). External sources are
 * deliberately <em>not</em> paywall-protected: the platform player owns the
 * bytes, so coins/membership cannot be enforced on them (accepted as a
 * traffic-first trade-off; Bunny stays for the paid catalogue).</p>
 */
public final class ExternalVideoSource {

    public static final String BUNNY = "bunny";
    public static final String YOUTUBE = "youtube";
    public static final String TIKTOK = "tiktok";
    public static final String FACEBOOK = "facebook";
    public static final String VIMEO = "vimeo";
    public static final String DOUYIN = "douyin";
    public static final String IFRAME = "iframe";
    public static final String HLS = "hls";
    public static final String MP4 = "mp4";

    private static final Pattern ASPECT = Pattern.compile("^\\d{1,2}:\\d{1,2}$");
    private static final Pattern YOUTUBE_ID = Pattern.compile(
            "(?:youtu\\.be/|youtube\\.com/(?:watch\\?v=|shorts/|embed/|live/))([A-Za-z0-9_-]{6,})");
    private static final Pattern TIKTOK_ID = Pattern.compile("/video/(\\d{6,})");
    private static final Pattern VIMEO_ID = Pattern.compile("vimeo\\.com/(?:video/)?(\\d{4,})");

    private ExternalVideoSource() {
    }

    /** Normalised source row ready for persistence. */
    public record Source(String provider, String videoId, String url, String aspectRatio, String type) {
    }

    public static Source normalise(String providerRaw, String videoIdRaw, String urlRaw, String aspectRaw) {
        String provider = providerRaw == null || providerRaw.isBlank()
                ? BUNNY : providerRaw.trim().toLowerCase(Locale.ROOT);
        String videoId = trim(videoIdRaw);
        String url = trim(urlRaw);
        String aspect = trim(aspectRaw);

        if (provider.equals(BUNNY)) {
            return new Source(BUNNY, "", "", "", "hls");
        }
        if (!provider.equals(YOUTUBE) && !provider.equals(TIKTOK) && !provider.equals(FACEBOOK)
                && !provider.equals(VIMEO) && !provider.equals(DOUYIN) && !provider.equals(IFRAME)
                && !provider.equals(HLS) && !provider.equals(MP4)) {
            throw ApiException.unprocessable("不支持的视频来源: " + provider);
        }

        String derivedId = videoId;
        switch (provider) {
            case YOUTUBE -> {
                if (derivedId.isEmpty()) {
                    derivedId = match(YOUTUBE_ID, url, "YouTube 链接无法识别视频 ID");
                }
                requireHttps(url.isEmpty() ? "https://www.youtube.com/" : url);
            }
            case TIKTOK -> {
                if (derivedId.isEmpty()) {
                    derivedId = match(TIKTOK_ID, url, "TikTok 链接无法识别视频 ID");
                }
                if (!derivedId.chars().allMatch(Character::isDigit)) {
                    throw ApiException.unprocessable("TikTok 视频 ID 应为数字");
                }
            }
            case VIMEO -> {
                if (derivedId.isEmpty()) {
                    derivedId = match(VIMEO_ID, url, "Vimeo 链接无法识别视频 ID");
                }
                if (derivedId.isEmpty()) {
                    throw ApiException.unprocessable("Vimeo 需要视频 ID 或链接");
                }
            }
            case FACEBOOK -> requireHttps(url.isEmpty() ? "https://www.facebook.com/" : url);
            case DOUYIN, IFRAME -> {
                requireHttps(url);
                if (url.isEmpty()) {
                    throw ApiException.unprocessable(provider + " 需要可嵌入的 https 链接");
                }
            }
            case HLS, MP4 -> {
                requireHttps(url);
                if (url.isEmpty()) {
                    throw ApiException.unprocessable(provider + " 需要 https 视频地址");
                }
            }
            default -> {
            }
        }

        if (aspect.isEmpty()) {
            aspect = switch (provider) {
                case TIKTOK, DOUYIN -> "9:16";
                default -> "16:9";
            };
        } else if (!ASPECT.matcher(aspect).matches()) {
            throw ApiException.unprocessable("画面比例格式应为 16:9 或 9:16");
        }
        String type = switch (provider) {
            case HLS, MP4 -> provider;
            default -> "embed";
        };
        return new Source(provider, derivedId, url, aspect, type);
    }

    /** Stable, public embed URL used by H5/web iframe players. */
    public static String embedUrl(String provider, String videoId, String url) {
        return switch (provider == null ? "" : provider) {
            case YOUTUBE -> "https://www.youtube.com/embed/" + safe(videoId)
                    + "?rel=0&playsinline=1&modestbranding=1";
            case TIKTOK -> "https://www.tiktok.com/embed/v2/" + safe(videoId);
            case VIMEO -> "https://player.vimeo.com/video/" + safe(videoId);
            case FACEBOOK -> "https://www.facebook.com/plugins/video.php?href="
                    + java.net.URLEncoder.encode(safe(url), java.nio.charset.StandardCharsets.UTF_8)
                    + "&show_text=false&autoplay=false&width=1280&height=720";
            default -> safe(url); // douyin / iframe
        };
    }

    public static boolean isExternal(String provider) {
        return provider != null && !provider.isBlank() && !BUNNY.equalsIgnoreCase(provider.trim());
    }

    /** {@code embed} for platform players, {@code hls}/{@code mp4} for direct URLs. */
    public static String typeOf(String provider) {
        if (provider == null) {
            return "hls";
        }
        String p = provider.trim().toLowerCase(Locale.ROOT);
        return switch (p) {
            case HLS, MP4 -> p;
            case BUNNY, "" -> "hls";
            default -> "embed";
        };
    }

    private static void requireHttps(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exc) {
            throw ApiException.unprocessable("视频链接格式不正确");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw ApiException.unprocessable("视频链接必须是 https 地址");
        }
    }

    private static String match(Pattern pattern, String url, String error) {
        if (url == null || url.isBlank()) {
            throw ApiException.unprocessable(error);
        }
        Matcher matcher = pattern.matcher(url);
        if (!matcher.find()) {
            throw ApiException.unprocessable(error);
        }
        return matcher.group(1);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
