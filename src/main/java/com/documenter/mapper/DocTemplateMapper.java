package com.documenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.documenter.entity.DocTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DocTemplateMapper extends BaseMapper<DocTemplate> {

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
             WHERE id = #{id}
            """)
    int replaceContent(@Param("id") Long id,
                       @Param("storageKey") String storageKey,
                       @Param("originalName") String originalName,
                       @Param("contentType") String contentType,
                       @Param("extension") String extension,
                       @Param("sizeBytes") Long sizeBytes);
}
