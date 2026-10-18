package com.documenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 一次 OCR 结果对应的人工校正文本。 */
@Data
@TableName("ocr_correction")
public class OcrCorrection {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long fileId;
    private Integer versionNo;
    private String fingerprint;
    private String correctionsJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
