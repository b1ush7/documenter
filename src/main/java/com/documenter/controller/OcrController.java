package com.documenter.controller;

import com.documenter.constant.ApiResponse;
import com.documenter.dto.OcrRequestDTO;
import com.documenter.service.OcrService;
import com.documenter.util.SecurityUtil;
import com.documenter.vo.OcrResultVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** PDF 与图片 OCR 接口。 */
@RestController
@RequestMapping("/file")
public class OcrController {

    private final OcrService ocrService;

    public OcrController(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    /** 识别一个明确 PDF 或图片版本；PDF 原生文字页直接复用，扫描页送入配置的 OCR 引擎。 */
    @PostMapping("/{fileId}/versions/{versionNo}/ocr")
    public ApiResponse<OcrResultVO> recognize(@PathVariable Long fileId,
                                              @PathVariable Integer versionNo,
                                              @Valid @RequestBody(required = false)
                                              OcrRequestDTO request) {
        return ApiResponse.success(ocrService.recognize(
                SecurityUtil.requireUserId(), fileId, versionNo, request));
    }
}
