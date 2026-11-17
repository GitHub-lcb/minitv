package us.minitv.pcommerce.service;

import org.springframework.stereotype.Service;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.EpisodeRow;
import us.minitv.content.row.TagRow;
import us.minitv.pcommerce.mapper.PCommerceContentMapper;

import java.util.List;

/**
 * Public visibility rules from {@code content.service}: non-published content
 * must never leak through a client-facing query.
 */
@Service
public class PCommerceContentService {

    private final PCommerceContentMapper content;

    public PCommerceContentService(PCommerceContentMapper content) {
        this.content = content;
    }

    public DramaRow findDrama(long dramaId) {
        return content.findDrama(dramaId);
    }

    public EpisodeRow findEpisode(long episodeId) {
        return content.findEpisode(episodeId);
    }

    /** content.service.get_published_drama */
    public DramaRow getPublishedDrama(long dramaId) {
        DramaRow drama = content.findDrama(dramaId);
        if (drama == null || !"published".equals(drama.getStatus())) {
            return null;
        }
        return drama;
    }

    /** content.service.list_published_episodes */
    public List<EpisodeRow> listPublishedEpisodes(long dramaId) {
        if (getPublishedDrama(dramaId) == null) {
            return List.of();
        }
        return content.listPublishedEpisodes(dramaId);
    }

    /** content.service.tags_by_drama: enabled tags in tag-id order. */
    public List<TagRow> tagsOf(long dramaId) {
        return content.tagsForDrama(dramaId);
    }
}
