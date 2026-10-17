package com.documenter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class TemplateVersionQueryDTO {
    @Min(1)
    private long page = 1;
    @Min(1)
    @Max(100)
    private long size = 20;
}
