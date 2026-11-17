package us.minitv.psocial.mapper;

import org.apache.ibatis.annotations.Mapper;
import us.minitv.ops.row.ModerationSettingRow;

/**
 * moderation_settings singleton row (id = 1). The admin endpoints that edit it
 * live in another agent's package; this module only reads it and seeds Python's
 * defaults on first use.
 */
@Mapper
public interface PSocialModerationMapper {

    ModerationSettingRow findSettings();

    int insertSettings(ModerationSettingRow row);
}
