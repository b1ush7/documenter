package com.documenter.controller;

import com.documenter.constant.ApiResponse;
import com.documenter.dto.OcrRequestDTO;
import com.documenter.dto.SaveOcrCorrectionsDTO;
import com.documenter.service.OcrCorrectionService;
import com.documenter.service.OcrService;
import com.documenter.util.OcrTextFormatter;
import com.documenter.util.SecurityUtil;
import com.documenter.vo.OcrResultVO;
import com.documenter.vo.OcrCorrectionVO;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/** PDF 与图片 OCR 接口。 */
@RestController
@RequestMapping("/file")
public class OcrController {

    private final OcrService ocrService;
    private final OcrCorrectionService correctionService;

    public OcrController(OcrService ocrService, OcrCorrectionService correctionService) {
        this.ocrService = ocrService;
        this.correctionService = correctionService;
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

    /** 保存或覆盖一次 OCR 结果的逐页人工校正文本。 */
    @PutMapping("/{fileId}/versions/{versionNo}/ocr/corrections")
    public ApiResponse<OcrCorrectionVO> saveCorrections(
            @PathVariable Long fileId,
            @PathVariable Integer versionNo,
            @Valid @RequestBody SaveOcrCorrectionsDTO request) {
        return ApiResponse.success("OCR 校正文本已保存",
                correctionService.save(SecurityUtil.requireUserId(), fileId,
                        versionNo, request));
    }

    /** 读取指定 OCR 结果指纹对应的人工校正文本。 */
    @GetMapping("/{fileId}/versions/{versionNo}/ocr/corrections/{fingerprint}")
    public ApiResponse<OcrCorrectionVO> getCorrections(
            @PathVariable Long fileId, @PathVariable Integer versionNo,
            @PathVariable String fingerprint) {
        return ApiResponse.success(correctionService.get(
                SecurityUtil.requireUserId(), fileId, versionNo, fingerprint));
    }
}
