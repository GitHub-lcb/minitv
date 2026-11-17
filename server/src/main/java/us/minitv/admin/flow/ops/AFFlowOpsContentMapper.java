package us.minitv.admin.flow.ops;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import us.minitv.content.row.DramaHomeGroupRow;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.EpisodeRow;
import us.minitv.content.row.HomeGroupRow;
import us.minitv.ops.row.BannerRow;
import us.minitv.ops.row.ChannelTabRow;
import us.minitv.ops.row.CopyEntrieRow;
import us.minitv.ops.row.HotSearcheRow;
import us.minitv.ops.row.LegalPageRow;
import us.minitv.ops.row.MarketingStatRow;
import us.minitv.ops.row.RewardConfigRow;
import us.minitv.ops.row.StoreLinkRow;
import us.minitv.social.row.PushRecordRow;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Home groups, copy entries, channel tabs, banners, hot searches, marketing
 * stats, push records, legal pages and store links.
 */
@Mapper
public interface AFFlowOpsContentMapper {

    // ---- home_groups -------------------------------------------------------

    @Select("SELECT * FROM home_groups WHERE id = #{id}")
    HomeGroupRow findGroup(@Param("id") long id);

    @Select("SELECT * FROM home_groups ORDER BY position, id")
    List<HomeGroupRow> listGroups();

    @Insert("INSERT INTO home_groups (name, position, status, style, badge) "
            + "VALUES (#{name}, #{position}, #{status}, #{style}, #{badge})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertGroup(HomeGroupRow row);

    @Update("UPDATE home_groups SET name = #{name}, position = #{position}, status = #{status}, "
            + "style = #{style}, badge = #{badge}, updated_at = NOW() WHERE id = #{id}")
    int updateGroup(HomeGroupRow row);

    @Select("SELECT group_id, COUNT(*) AS cnt FROM drama_home_groups GROUP BY group_id")
    List<Map<String, Object>> groupCounts();

    @Select("SELECT COUNT(*) FROM drama_home_groups WHERE group_id = #{groupId}")
    long countGroupDramas(@Param("groupId") long groupId);

    // ---- drama_home_groups -------------------------------------------------

    @Select("SELECT * FROM drama_home_groups WHERE group_id = #{groupId} AND drama_id = #{dramaId}")
    DramaHomeGroupRow findLink(@Param("groupId") long groupId, @Param("dramaId") long dramaId);

    @Insert("INSERT INTO drama_home_groups (group_id, drama_id, position_in_group) "
            + "VALUES (#{groupId}, #{dramaId}, #{positionInGroup})")
    int insertLink(DramaHomeGroupRow row);

    @Delete("DELETE FROM drama_home_groups WHERE group_id = #{groupId} AND drama_id = #{dramaId}")
    int deleteLink(@Param("groupId") long groupId, @Param("dramaId") long dramaId);

    @Update("UPDATE drama_home_groups SET position_in_group = #{position} "
            + "WHERE group_id = #{groupId} AND drama_id = #{dramaId}")
    int updateLinkPosition(@Param("groupId") long groupId, @Param("dramaId") long dramaId,
                           @Param("position") int position);

    @Select("SELECT * FROM drama_home_groups WHERE group_id = #{groupId} "
            + "ORDER BY position_in_group, drama_id")
    List<DramaHomeGroupRow> listLinks(@Param("groupId") long groupId);

    @Select("SELECT * FROM drama_home_groups WHERE drama_id = #{dramaId}")
    List<DramaHomeGroupRow> listLinksByDrama(@Param("dramaId") long dramaId);

    // ---- dramas (read-only cross-module lookup) ----------------------------

    @Select("SELECT * FROM dramas WHERE id = #{id}")
    DramaRow findDrama(@Param("id") long id);

    @Select("SELECT * FROM episodes WHERE id = #{id}")
    EpisodeRow findEpisode(@Param("id") long id);

    // ---- copy_entries ------------------------------------------------------

    @Select("SELECT * FROM copy_entries WHERE `key` = #{key}")
    CopyEntrieRow findCopyByKey(@Param("key") String key);

    @Insert("INSERT INTO copy_entries (`key`, en, zh) VALUES (#{key}, #{en}, #{zh})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertCopy(CopyEntrieRow row);

    @Update("UPDATE copy_entries SET en = #{en}, zh = #{zh} WHERE `key` = #{key}")
    int updateCopy(CopyEntrieRow row);

    @Select("SELECT * FROM copy_entries ORDER BY `key`")
    List<CopyEntrieRow> listCopies();

    // ---- channel_tabs ------------------------------------------------------

    @Select("SELECT * FROM channel_tabs WHERE id = #{id}")
    ChannelTabRow findTab(@Param("id") long id);

    @Select("SELECT * FROM channel_tabs WHERE code = #{code}")
    ChannelTabRow findTabByCode(@Param("code") String code);

    @Select("SELECT * FROM channel_tabs ORDER BY position, id")
    List<ChannelTabRow> listTabs();

    @Insert("INSERT INTO channel_tabs (code, title_en, title_zh, position, enabled, locked, group_id) "
            + "VALUES (#{code}, #{titleEn}, #{titleZh}, #{position}, #{enabled}, #{locked}, #{groupId,jdbcType=BIGINT})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertTab(ChannelTabRow row);

    @Update("UPDATE channel_tabs SET title_en = #{titleEn}, title_zh = #{titleZh}, position = #{position}, "
            + "enabled = #{enabled}, locked = #{locked}, group_id = #{groupId,jdbcType=BIGINT} WHERE id = #{id}")
    int updateTab(ChannelTabRow row);

    @Delete("DELETE FROM channel_tabs WHERE id = #{id}")
    int deleteTab(@Param("id") long id);

    // ---- banners -----------------------------------------------------------

    @Select("SELECT * FROM banners WHERE slot = #{slot} ORDER BY position, id")
    List<BannerRow> listBanners(@Param("slot") String slot);

    @Select("SELECT * FROM banners WHERE id = #{id}")
    BannerRow findBanner(@Param("id") long id);

    @Select("SELECT COUNT(*) FROM banners WHERE slot = #{slot} AND status = 'online'")
    long countOnlineBanners(@Param("slot") String slot);

    @Insert("INSERT INTO banners (slot, drama_id, status, position, kicker, headline) "
            + "VALUES (#{slot}, #{dramaId,jdbcType=BIGINT}, #{status}, #{position}, #{kicker}, #{headline})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertBanner(BannerRow row);

    @Update("UPDATE banners SET drama_id = #{dramaId,jdbcType=BIGINT}, status = #{status}, position = #{position}, "
            + "kicker = #{kicker}, headline = #{headline} WHERE id = #{id}")
    int updateBanner(BannerRow row);

    @Delete("DELETE FROM banners WHERE id = #{id}")
    int deleteBanner(@Param("id") long id);

    // ---- hot_searches / marketing_stats -----------------------------------

    @Insert("INSERT INTO hot_searches (term, position, enabled) VALUES (#{term}, #{position}, TRUE)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertHotSearch(HotSearcheRow row);

    @Insert("INSERT INTO marketing_stats (label, value, position) VALUES (#{label}, #{value}, #{position})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertMarketingStat(MarketingStatRow row);

    // ---- reward_configs ----------------------------------------------------

    @Select("SELECT * FROM reward_configs WHERE code = #{code} ORDER BY effective_on DESC")
    List<RewardConfigRow> listRewardConfigs(@Param("code") String code);

    @Select("SELECT * FROM reward_configs WHERE code = #{code} AND effective_on = #{effectiveOn} LIMIT 1")
    RewardConfigRow findRewardConfig(@Param("code") String code, @Param("effectiveOn") LocalDate effectiveOn);

    @Insert("INSERT INTO reward_configs (code, amounts, effective_on) "
            + "VALUES (#{code}, #{amounts}, #{effectiveOn})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertRewardConfig(RewardConfigRow row);

    @Update("UPDATE reward_configs SET amounts = #{amounts} WHERE id = #{id}")
    int updateRewardConfigAmounts(@Param("id") long id, @Param("amounts") String amounts);

    // ---- push_records ------------------------------------------------------

    @Insert("INSERT INTO push_records (drama_id, title, body, delivered_count) "
            + "VALUES (#{dramaId,jdbcType=BIGINT}, #{title}, #{body}, #{deliveredCount})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertPush(PushRecordRow row);

    @Select("SELECT * FROM push_records WHERE id = #{id}")
    PushRecordRow findPush(@Param("id") long id);

    @Select("SELECT * FROM push_records ORDER BY id DESC")
    List<PushRecordRow> listPushes();

    // ---- legal_pages / store_links ----------------------------------------

    @Select("SELECT * FROM legal_pages WHERE slug = #{slug}")
    LegalPageRow findLegal(@Param("slug") String slug);

    @Insert("INSERT INTO legal_pages (slug, title, body) VALUES (#{slug}, #{title}, #{body})")
    int insertLegal(LegalPageRow row);

    @Update("UPDATE legal_pages SET title = #{title}, body = #{body} WHERE slug = #{slug}")
    int updateLegal(LegalPageRow row);

    @Select("SELECT * FROM store_links WHERE platform = #{platform}")
    StoreLinkRow findStoreLink(@Param("platform") String platform);

    @Insert("INSERT INTO store_links (platform, url) VALUES (#{platform}, #{url})")
    int insertStoreLink(StoreLinkRow row);

    @Update("UPDATE store_links SET url = #{url} WHERE platform = #{platform}")
    int updateStoreLink(StoreLinkRow row);
}
