package com.documenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.documenter.entity.DocTemplateVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DocTemplateVersionMapper extends BaseMapper<DocTemplateVersion> {
    @Select("SELECT * FROM doc_template_version WHERE template_id = #{templateId} AND version_no = #{versionNo}")
    DocTemplateVersion findVersion(@Param("templateId") Long templateId, @Param("versionNo") Integer versionNo);

    @Select("SELECT COUNT(*) FROM doc_template_version WHERE template_id = #{templateId}")
    long countVersions(@Param("templateId") Long templateId);

    @Select("""
            SELECT * FROM doc_template_version WHERE template_id = #{templateId}
             ORDER BY version_no DESC LIMIT #{limit} OFFSET #{offset}
            """)
    List<DocTemplateVersion> listVersions(@Param("templateId") Long templateId,
                                         @Param("limit") long limit, @Param("offset") long offset);
}
