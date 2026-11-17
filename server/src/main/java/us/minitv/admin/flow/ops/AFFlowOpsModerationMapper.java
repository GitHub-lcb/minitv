package us.minitv.admin.flow.ops;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import us.minitv.ops.row.ModerationSettingRow;
import us.minitv.social.row.CommentRow;

import java.util.List;
import java.util.Map;

/** Auto-moderation policy singleton and the comment moderation queue. */
@Mapper
public interface AFFlowOpsModerationMapper {

    @Select("SELECT * FROM moderation_settings WHERE id = 1")
    ModerationSettingRow findSettings();

    @Insert("INSERT INTO moderation_settings (id, enabled, base_url, model, categories, system_prompt, "
            + "approve_threshold, block_threshold, fallback_action, timeout_seconds, api_key_secret) "
            + "VALUES (1, #{enabled}, #{baseUrl}, #{model}, #{categories}, #{systemPrompt}, "
            + "#{approveThreshold}, #{blockThreshold}, #{fallbackAction}, #{timeoutSeconds}, #{apiKeySecret})")
    int insertSettings(ModerationSettingRow row);

    @Update("UPDATE moderation_settings SET enabled = #{enabled}, base_url = #{baseUrl}, model = #{model}, "
            + "categories = #{categories}, system_prompt = #{systemPrompt}, "
            + "approve_threshold = #{approveThreshold}, block_threshold = #{blockThreshold}, "
            + "fallback_action = #{fallbackAction}, timeout_seconds = #{timeoutSeconds}, "
            + "api_key_secret = #{apiKeySecret}, updated_by = #{updatedBy}, updated_at = NOW() WHERE id = 1")
    int updateSettings(ModerationSettingRow row);

    @Select("SELECT * FROM comments WHERE id = #{id}")
    CommentRow findComment(@Param("id") long id);

    @Update("UPDATE comments SET status = #{status}, moderation_state = #{moderationState} WHERE id = #{id}")
    int updateCommentModeration(CommentRow row);

    /** Dynamic list/count statements live in afflowops_moderation.xml. */
    List<CommentRow> searchComments(@Param("status") String status, @Param("dramaId") Long dramaId,
                                    @Param("episodeId") Long episodeId, @Param("like") String like,
                                    @Param("limit") int limit, @Param("offset") int offset);

    long countComments(@Param("status") String status, @Param("dramaId") Long dramaId,
                       @Param("episodeId") Long episodeId, @Param("like") String like);

    List<Map<String, Object>> statusCounts(@Param("dramaId") Long dramaId,
                                           @Param("episodeId") Long episodeId,
                                           @Param("like") String like);
}
