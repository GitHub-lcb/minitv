package us.minitv.admin.core.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import us.minitv.content.row.DramaRow;
import us.minitv.content.row.TagRow;

import java.util.List;

/** tags / drama_tags statements used by the admin-core surfaces. */
@Mapper
public interface AdminCoreTagMapper {

    @Select("SELECT * FROM tags ORDER BY name_en")
    List<TagRow> listAll();

    @Select("SELECT * FROM tags WHERE id = #{id}")
    TagRow findById(@Param("id") long id);

    @Select("SELECT id FROM tags WHERE name_en = #{nameEn} ORDER BY id LIMIT 1")
    Long findIdByNameEn(@Param("nameEn") String nameEn);

    @Select("SELECT COUNT(*) FROM drama_tags WHERE tag_id = #{tagId}")
    long countByTagId(@Param("tagId") long tagId);

    @Insert("""
            INSERT INTO tags (name_en, name_zh, category, status, created_at)
            VALUES (#{nameEn}, #{nameZh}, #{category}, #{status}, NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(TagRow row);

    int updateFields(@Param("id") long id,
                     @Param("setNameEn") boolean setNameEn,
                     @Param("nameEn") String nameEn,
                     @Param("setNameZh") boolean setNameZh,
                     @Param("nameZh") String nameZh,
                     @Param("setCategory") boolean setCategory,
                     @Param("category") String category,
                     @Param("setStatus") boolean setStatus,
                     @Param("status") String status);

    @Delete("DELETE FROM tags WHERE id = #{id}")
    int deleteById(@Param("id") long id);

    @Select("""
            SELECT d.* FROM dramas d
            JOIN drama_tags dt ON dt.drama_id = d.id
            WHERE dt.tag_id = #{tagId}
            ORDER BY d.id
            """)
    List<DramaRow> listDramasByTag(@Param("tagId") long tagId);

    @Select("""
            SELECT t.* FROM tags t
            JOIN drama_tags dt ON dt.tag_id = t.id
            WHERE dt.drama_id = #{dramaId}
            ORDER BY t.id
            """)
    List<TagRow> listByDrama(@Param("dramaId") long dramaId);

    @Delete("DELETE FROM drama_tags WHERE drama_id = #{dramaId}")
    int deleteDramaTags(@Param("dramaId") long dramaId);

    @Insert("INSERT INTO drama_tags (drama_id, tag_id, created_at) VALUES (#{dramaId}, #{tagId}, NOW())")
    int insertDramaTag(@Param("dramaId") long dramaId, @Param("tagId") long tagId);
}
