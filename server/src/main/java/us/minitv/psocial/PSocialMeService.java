package us.minitv.psocial;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.minitv.account.row.UserRow;
import us.minitv.common.Times;
import us.minitv.content.row.DramaRow;
import us.minitv.psocial.dto.PSocialDramaCount;
import us.minitv.psocial.dto.PSocialHistoryView;
import us.minitv.psocial.mapper.PSocialContentMapper;
import us.minitv.psocial.mapper.PSocialHistoryMapper;
import us.minitv.psocial.mapper.PSocialInboxMapper;
import us.minitv.psocial.mapper.PSocialReactionMapper;
import us.minitv.social.row.InboxMessageRow;
import us.minitv.social.row.ReactionRow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The signed-in viewer's profile shelves: favorites, inbox, watch history and
 * named reaction lists ("liked"/"favorited"/...).
 *
 * <p>Port of the corresponding handlers in {@code app/modules/public_flow.py}.</p>
 */
@Service
public class PSocialMeService {

    private final PSocialReactionMapper reactions;
    private final PSocialInboxMapper inbox;
    private final PSocialHistoryMapper history;
    private final PSocialContentMapper content;

    public PSocialMeService(PSocialReactionMapper reactions,
                            PSocialInboxMapper inbox,
                            PSocialHistoryMapper history,
                            PSocialContentMapper content) {
        this.reactions = reactions;
        this.inbox = inbox;
        this.history = history;
        this.content = content;
    }

    /** GET /v1/me/favorites — saved dramas as cards, newest first. */
    public Map<String, Object> favorites(Integer limitParam, Integer offsetParam, UserRow user) {
        int limit = Math.max(1, Math.min(limitParam == null ? 20 : limitParam, 50));
        int offset = Math.max(0, offsetParam == null ? 0 : offsetParam);

        long total = reactions.countFavoriteDramas(user.getId());
        List<DramaRow> rows = reactions.listFavoriteDramas(user.getId(), limit, offset);

        List<Long> dramaIds = new ArrayList<>(rows.size());
        for (DramaRow drama : rows) {
            dramaIds.add(drama.getId());
        }
        Map<Long, Long> totals = episodeTotals(dramaIds);

        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (DramaRow drama : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", drama.getId());
            item.put("title", drama.getTitle());
            item.put("cover_url", drama.getCoverUrl());
            item.put("synopsis", drama.getSynopsis());
            item.put("play_count", drama.getPlayCount());
            item.put("episode_count", totals.getOrDefault(drama.getId(), 0L));
            items.add(item);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", total);
        response.put("items", items);
        return response;
    }

    /** GET /v1/me/messages — every inbox row plus the unread count. */
    public Map<String, Object> messages(UserRow user) {
        List<InboxMessageRow> rows = inbox.listByUser(user.getId());
        long unread = 0;
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (InboxMessageRow row : rows) {
            boolean read = row.getReadAt() != null;
            if (!read) {
                unread++;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("kind", row.getKind());
            item.put("title", row.getTitle());
            item.put("body", row.getBody());
            item.put("read", read);
            items.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("unread", unread);
        response.put("items", items);
        return response;
    }

    /** POST /v1/me/messages/read — mark everything unread as read. */
    @Transactional
    public Map<String, Object> markMessagesRead(UserRow user) {
        inbox.markAllRead(user.getId(), Times.now());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("unread", 0);
        return response;
    }

    /** GET /v1/me/history — newest progress first, joined to drama + episode cards. */
    public Map<String, Object> history(UserRow user) {
        List<PSocialHistoryView> rows = history.listRecent(user.getId(), 60);

        List<Long> dramaIds = new ArrayList<>();
        for (PSocialHistoryView row : rows) {
            if (row.getDramaId() != null && !dramaIds.contains(row.getDramaId())) {
                dramaIds.add(row.getDramaId());
            }
        }
        Map<Long, Long> totals = episodeTotals(dramaIds);

        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (PSocialHistoryView row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("episode_id", row.getEpisodeId());
            item.put("position_seconds", row.getPositionSeconds());
            item.put("episode_number", row.getEpisodeNumber());
            item.put("duration_seconds", row.getDurationSeconds());
            item.put("updated_at", row.getUpdatedAt());
            item.put("drama", dramaCard(row.getDramaId(), row.getTitle(), row.getCoverUrl(), totals));
            items.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", items);
        return response;
    }

    /** GET /v1/me/lists/{kind} — reactions of one kind, dramas expanded to cards. */
    public Map<String, Object> list(String kind, UserRow user) {
        List<ReactionRow> rows = reactions.listByUserAndKind(user.getId(), kind);

        List<Long> dramaIds = new ArrayList<>();
        for (ReactionRow row : rows) {
            if ("drama".equals(row.getTargetType())) {
                dramaIds.add(row.getTargetId());
            }
        }
        Map<Long, DramaRow> dramas = new HashMap<>();
        if (!dramaIds.isEmpty()) {
            for (DramaRow drama : content.listPublishedDramasByIds(dramaIds)) {
                dramas.put(drama.getId(), drama);
            }
        }
        Map<Long, Long> totals = episodeTotals(new ArrayList<>(dramas.keySet()));

        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (ReactionRow row : rows) {
            if ("drama".equals(row.getTargetType())) {
                DramaRow drama = dramas.get(row.getTargetId());
                if (drama == null) {
                    // Unpublished since it was liked.
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("target_type", row.getTargetType());
                item.put("target_id", row.getTargetId());
                item.put("drama", dramaCard(drama, totals));
                items.add(item);
            } else {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("target_type", row.getTargetType());
                item.put("target_id", row.getTargetId());
                items.add(item);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", items.size());
        response.put("items", items);
        return response;
    }

    private Map<Long, Long> episodeTotals(List<Long> dramaIds) {
        Map<Long, Long> totals = new HashMap<>();
        if (dramaIds == null || dramaIds.isEmpty()) {
            return totals;
        }
        for (PSocialDramaCount row : content.listPublishedEpisodeCounts(dramaIds)) {
            totals.put(row.getDramaId(), row.getTotal() == null ? 0L : row.getTotal());
        }
        return totals;
    }

    private Map<String, Object> dramaCard(Long dramaId, String title, String coverUrl, Map<Long, Long> totals) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", dramaId);
        card.put("title", title);
        card.put("cover_url", coverUrl);
        card.put("episode_count", totals.getOrDefault(dramaId, 0L));
        return card;
    }

    private Map<String, Object> dramaCard(DramaRow drama, Map<Long, Long> totals) {
        return dramaCard(drama.getId(), drama.getTitle(), drama.getCoverUrl(), totals);
    }
}
