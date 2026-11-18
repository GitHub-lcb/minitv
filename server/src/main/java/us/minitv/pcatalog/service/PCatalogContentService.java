package us.minitv.pcatalog.service;

import org.springframework.stereotype.Service;
import us.minitv.common.ApiException;
import us.minitv.content.ExternalVideoSource;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.EpisodeRow;
import us.minitv.content.row.TagRow;
import us.minitv.pcatalog.dto.PCatalogDramaTag;
import us.minitv.pcatalog.dto.PCatalogEpisodeCounter;
import us.minitv.pcatalog.dto.PCatalogEpisodeCounts;
import us.minitv.pcatalog.mapper.PCatalogDramaMapper;
import us.minitv.pcatalog.mapper.PCatalogEpisodeMapper;
import us.minitv.pcatalog.mapper.PCatalogTagMapper;
import us.minitv.pcatalog.support.PCatalogOut;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The public content read path: only published dramas and published episodes
 * are ever visible here (Python {@code content.service} public visibility
 * helpers plus the {@code public_router} surface).
 */
@Service
public class PCatalogContentService {

    /** "每个分类的前 7 个" - the website preview shows at most this many dramas per category. */
    public static final int PREVIEW_LIMIT = 7;

    private static final int SEARCH_LIMIT = 20;

    private final PCatalogDramaMapper dramaMapper;
    private final PCatalogEpisodeMapper episodeMapper;
    private final PCatalogTagMapper tagMapper;

    public PCatalogContentService(PCatalogDramaMapper dramaMapper,
                                  PCatalogEpisodeMapper episodeMapper,
                                  PCatalogTagMapper tagMapper) {
        this.dramaMapper = dramaMapper;
        this.episodeMapper = episodeMapper;
        this.tagMapper = tagMapper;
    }

    // ------------------------------------------------------------------ reads

    public List<DramaRow> listPublishedDramas() {
        return dramaMapper.listPublished();
    }

    public List<DramaRow> visiblePublishedDramas() {
        return dramaMapper.listVisiblePublished();
    }

    public DramaRow getPublishedDrama(long dramaId) {
        DramaRow drama = dramaMapper.findById(dramaId);
        if (drama == null || !"published".equals(drama.getStatus())) {
            return null;
        }
        return drama;
    }

    public List<DramaRow> searchPublishedDramas(String query) {
        return dramaMapper.searchPublished(query, SEARCH_LIMIT);
    }

    public List<DramaRow> publishedDramasByIds(Collection<Long> dramaIds) {
        if (dramaIds == null || dramaIds.isEmpty()) {
            return List.of();
        }
        return dramaMapper.findPublishedByIds(List.copyOf(dramaIds));
    }

    public List<EpisodeRow> publishedPlayableEpisodes(long dramaId) {
        return episodeMapper.publishedPlayable(dramaId);
    }

    public long countEpisodesOfDrama(long dramaId) {
        return episodeMapper.countByDrama(dramaId);
    }

    public List<PCatalogEpisodeCounter> publishedPlayableCounters() {
        return episodeMapper.publishedPlayableCounters();
    }

    public List<EpisodeRow> playableEpisodesByDramaIds(Collection<Long> dramaIds) {
        if (dramaIds == null || dramaIds.isEmpty()) {
            return List.of();
        }
        return episodeMapper.playableByDramaIds(List.copyOf(dramaIds));
    }

    public Map<Long, PCatalogEpisodeCounts> episodeCounts(Collection<Long> dramaIds) {
        Map<Long, PCatalogEpisodeCounts> counts = new LinkedHashMap<>();
        if (dramaIds == null || dramaIds.isEmpty()) {
            return counts;
        }
        for (PCatalogEpisodeCounts row : episodeMapper.episodeCountsByDramaIds(List.copyOf(dramaIds))) {
            counts.put(row.getDramaId(), row);
        }
        return counts;
    }

    public List<EpisodeRow> playableEpisodesByIds(Collection<Long> episodeIds) {
        if (episodeIds == null || episodeIds.isEmpty()) {
            return List.of();
        }
        return episodeMapper.playableByIds(List.copyOf(episodeIds));
    }

    /** Enabled tags per drama, keyed by drama id, in tag-id order (content.tags_by_drama). */
    public Map<Long, List<TagRow>> tagsByDrama(Collection<Long> dramaIds) {
        Map<Long, List<TagRow>> tags = new LinkedHashMap<>();
        if (dramaIds == null || dramaIds.isEmpty()) {
            return tags;
        }
        for (PCatalogDramaTag row : tagMapper.tagsByDramaIds(List.copyOf(dramaIds))) {
            TagRow tag = new TagRow();
            tag.setId(row.getTagId());
            tag.setNameEn(row.getNameEn());
            tag.setNameZh(row.getNameZh());
            tag.setCategory(row.getCategory());
            tag.setStatus(row.getStatus());
            tags.computeIfAbsent(row.getDramaId(), key -> new ArrayList<>()).add(tag);
        }
        return tags;
    }

    // --------------------------------------------------------------- endpoints

    /** GET /v1/preview - first N dramas per category, public data only. */
    public Map<String, Object> preview() {
        Map<Long, PCatalogEpisodeCounter> counters = new LinkedHashMap<>();
        for (PCatalogEpisodeCounter row : publishedPlayableCounters()) {
            counters.put(row.getDramaId(), row);
        }
        List<DramaRow> dramas = new ArrayList<>();
        for (DramaRow drama : listPublishedDramas()) {
            PCatalogEpisodeCounter counter = counters.get(drama.getId());
            if (counter != null && counter.getFreePlayableCount() != null && counter.getFreePlayableCount() > 0) {
                dramas.add(drama);
            }
        }
        // Python: dramas.sort(key=lambda drama: -drama.play_count) over the recency-ordered list;
        // List.sort is stable, so recency stays the tiebreaker.
        dramas.sort(Comparator.comparingLong(
                (DramaRow drama) -> drama.getPlayCount() == null ? 0L : drama.getPlayCount()).reversed());

        List<Long> dramaIds = dramas.stream().map(DramaRow::getId).toList();
        Map<Long, List<TagRow>> tagsByDrama = tagsByDrama(dramaIds);

        List<Map<String, Object>> categories = new ArrayList<>();
        categories.add(PCatalogOut.of(
                "id", 0,
                "name", "All shows",
                "main", true,
                "total", dramas.size(),
                "dramas", previewCards(dramas, counters, tagsByDrama)));

        for (TagRow tag : tagMapper.listEnabledOrdered()) {
            List<DramaRow> linked = new ArrayList<>();
            for (DramaRow drama : dramas) {
                for (TagRow dramaTag : tagsByDrama.getOrDefault(drama.getId(), List.of())) {
                    if (tag.getId().equals(dramaTag.getId())) {
                        linked.add(drama);
                        break;
                    }
                }
            }
            if (linked.isEmpty()) {
                continue;
            }
            categories.add(PCatalogOut.of(
                    "id", tag.getId(),
                    "name", tag.getNameEn(),
                    "main", "main".equals(tag.getCategory()),
                    "total", linked.size(),
                    "dramas", previewCards(linked, counters, tagsByDrama)));
        }
        return PCatalogOut.of("limit", PREVIEW_LIMIT, "categories", categories);
    }

    /**
     * Free on the public website: inside the drama's free window, or an
     * external platform source (traffic-first embeds; no paywall possible).
     */
    private static boolean isFreePreview(EpisodeRow episode, DramaRow drama) {
        return ExternalVideoSource.isExternal(episode.getVideoProvider())
                || episode.getEpisodeNumber() <= drama.getFreeEpisodeCount();
    }

    /** GET /v1/preview/dramas/{drama_id} - metadata plus the published episode list. */
    public Map<String, Object> previewDrama(long dramaId) {
        DramaRow drama = getPublishedDrama(dramaId);
        if (drama == null) {
            throw ApiException.notFound("drama not found");
        }
        List<EpisodeRow> episodes = publishedPlayableEpisodes(drama.getId());
        if (episodes.isEmpty()) {
            throw ApiException.notFound("no published episodes");
        }
        long freePlayable = 0L;
        for (EpisodeRow episode : episodes) {
            if (isFreePreview(episode, drama)) {
                freePlayable++;
            }
        }
        if (freePlayable == 0L) {
            throw ApiException.notFound("no free episodes");
        }
        PCatalogEpisodeCounter counter = new PCatalogEpisodeCounter();
        counter.setDramaId(drama.getId());
        counter.setEpisodeCount((long) episodes.size());
        counter.setFreePlayableCount(freePlayable);
        List<TagRow> tags = tagsByDrama(List.of(drama.getId())).getOrDefault(drama.getId(), List.of());
        long totalEpisodeCount = countEpisodesOfDrama(drama.getId());

        List<Map<String, Object>> items = new ArrayList<>();
        for (EpisodeRow episode : episodes) {
            items.add(PCatalogOut.of(
                    "id", episode.getId(),
                    "episode_number", episode.getEpisodeNumber(),
                    "title", episode.getTitle(),
                    "duration_seconds", episode.getDurationSeconds(),
                    "free", isFreePreview(episode, drama)));
        }
        return PCatalogOut.of(
                "drama", previewCard(drama, counter, tags),
                "items", items,
                "total_episode_count", totalEpisodeCount);
    }

    /** GET /v1/home - published dramas, most recently updated first. */
    public Map<String, Object> home() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (DramaRow drama : listPublishedDramas()) {
            items.add(dramaOut(drama));
        }
        return PCatalogOut.of("items", items);
    }

    /** GET /v1/dramas/{drama_id}. */
    public Map<String, Object> dramaDetail(long dramaId) {
        DramaRow drama = getPublishedDrama(dramaId);
        if (drama == null) {
            throw ApiException.notFound("drama not found");
        }
        return dramaOut(drama);
    }

    /** GET /v1/search?q=. */
    public Map<String, Object> search(String query) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (DramaRow drama : searchPublishedDramas(query)) {
            items.add(dramaOut(drama));
        }
        return PCatalogOut.of("items", items);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> dramaOut(DramaRow drama) {
        return PCatalogOut.of(
                "id", drama.getId(),
                "title", drama.getTitle(),
                "synopsis", drama.getSynopsis(),
                "cover_url", drama.getCoverUrl(),
                "status", drama.getStatus(),
                "free_episode_count", drama.getFreeEpisodeCount(),
                "coin_price_per_episode", drama.getCoinPricePerEpisode(),
                "updated_at", isoUtc(drama.getUpdatedAt()));
    }

    /**
     * Python emitted {@code datetime.isoformat()}: always seconds and {@code +00:00}.
     * Spelled out explicitly because {@code LocalDateTime.toString()} (what the base
     * Jackson customiser and {@code Times.iso} use) drops the seconds when they are
     * zero, which would occasionally change the wire value.
     */
    private static String isoUtc(java.time.LocalDateTime value) {
        return value == null ? null
                : value.format(java.time.format.DateTimeFormatter
                        .ofPattern("yyyy-MM-dd'T'HH:mm:ss'+00:00'"));
    }

    private List<Map<String, Object>> previewCards(List<DramaRow> dramas,
                                                   Map<Long, PCatalogEpisodeCounter> counters,
                                                   Map<Long, List<TagRow>> tagsByDrama) {
        List<Map<String, Object>> cards = new ArrayList<>();
        int limit = Math.min(PREVIEW_LIMIT, dramas.size());
        for (int index = 0; index < limit; index++) {
            DramaRow drama = dramas.get(index);
            cards.add(previewCard(drama,
                    counters.get(drama.getId()),
                    tagsByDrama.getOrDefault(drama.getId(), List.of())));
        }
        return cards;
    }

    private Map<String, Object> previewCard(DramaRow drama,
                                            PCatalogEpisodeCounter counter,
                                            List<TagRow> tags) {
        List<String> tagNames = new ArrayList<>();
        for (TagRow tag : tags) {
            tagNames.add(tag.getNameEn());
        }
        long episodeCount = counter == null || counter.getEpisodeCount() == null ? 0L : counter.getEpisodeCount();
        long freePlayable = counter == null || counter.getFreePlayableCount() == null
                ? 0L : counter.getFreePlayableCount();
        return PCatalogOut.of(
                "id", drama.getId(),
                "title", drama.getTitle(),
                "synopsis", drama.getSynopsis(),
                "cover_url", drama.getCoverUrl(),
                "play_count", drama.getPlayCount(),
                "free_episode_count", drama.getFreeEpisodeCount(),
                "coin_price_per_episode", drama.getCoinPricePerEpisode(),
                "episode_count", episodeCount,
                "free_playable_count", freePlayable,
                "tags", tagNames);
    }
}
