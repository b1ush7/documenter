package com.documenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.documenter.entity.DocTemplate;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DocTemplateMapper extends BaseMapper<DocTemplate> {

    /** PUT 元数据采用完整替换语义，可选字段必须能显式写入 NULL。 */
    @Update("""
            UPDATE doc_template
               SET code = #{template.code},
                   name = #{template.name},
                   category = #{template.category,jdbcType=VARCHAR},
                   description = #{template.description,jdbcType=VARCHAR},
                   variables = #{template.variables,jdbcType=VARCHAR}
             WHERE id = #{template.id}
            """)
    int updateMetadata(@Param("template") DocTemplate template);

    /** 仅修改启用状态，避免覆盖文件内容或元数据。 */
    @Update("UPDATE doc_template SET enabled = #{enabled} WHERE id = #{id}")
    int updateEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);

    /** 删除条件在数据库写入时检查，防止读取后被并发启用的模板仍遭删除。 */
    @Delete("DELETE FROM doc_template WHERE id = #{id} AND enabled = 0")
    int deleteDisabled(@Param("id") Long id);

    /**
     * 更新模板内容并递增版本号。
     *
     * <p>用 SQL 自增而不是「读出来 +1 再写回」：后者在并发上传时两个请求会读到同一个版本号，
     * 导致版本号重复、无法判断哪份内容更新。
     */
    @Update("""
            UPDATE doc_template
               SET storage_key = #{storageKey},
                   original_name = #{originalName},
                   content_type = #{contentType},
                   extension = #{extension},
                   size_bytes = #{sizeBytes},
                   version_no = version_no + 1
             WHERE id = #{id} AND version_no = #{expectVersion}
            """)
    int replaceContent(@Param("id") Long id,
                       @Param("storageKey") String storageKey,
                       @Param("originalName") String originalName,
                       @Param("contentType") String contentType,
                       @Param("extension") String extension,
                       @Param("sizeBytes") Long sizeBytes,
                       @Param("expectVersion") Integer expectVersion);
}
