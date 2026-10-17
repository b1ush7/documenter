package com.documenter.controller;

import com.documenter.constant.ApiResponse;
import com.documenter.dto.OcrRequestDTO;
import com.documenter.service.OcrService;
import com.documenter.util.OcrTextFormatter;
import com.documenter.util.SecurityUtil;
import com.documenter.vo.OcrResultVO;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

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

    /** 使用同一 OCR 请求导出页间以换页符分隔的 UTF-8 纯文本。 */
    @PostMapping("/{fileId}/versions/{versionNo}/ocr/text")
    public ResponseEntity<byte[]> exportText(@PathVariable Long fileId,
                                             @PathVariable Integer versionNo,
                                             @Valid @RequestBody(required = false)
                                             OcrRequestDTO request) {
        OcrResultVO result = ocrService.recognize(
                SecurityUtil.requireUserId(), fileId, versionNo, request);
        byte[] content = OcrTextFormatter.format(result).getBytes(StandardCharsets.UTF_8);
        String fileName = "ocr-" + fileId + "-v" + versionNo + ".txt";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName).build().toString())
                .body(content);
    }
}
