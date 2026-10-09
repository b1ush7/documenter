package com.documenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.documenter.entity.DocumentVersion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentVersionMapper extends BaseMapper<DocumentVersion> {
}
