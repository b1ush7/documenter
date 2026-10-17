package com.documenter.controller;

import com.documenter.constant.ApiResponse;
import com.documenter.service.DocTemplateService;
import com.documenter.util.SecurityUtil;
import com.documenter.vo.DocTemplateVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Authenticated users can select enabled DOCX templates without access to administrator history APIs. */
@RestController
@RequestMapping("/templates")
public class TemplateController {
    private final DocTemplateService templates;
    public TemplateController(DocTemplateService templates) { this.templates = templates; }

    @GetMapping
    public ApiResponse<List<DocTemplateVO>> list() {
        SecurityUtil.requireUserId();
        return ApiResponse.success(templates.listEnabled());
    }
}
