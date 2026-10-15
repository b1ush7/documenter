package com.documenter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 审计日志查询条件（TODO 4.3「管理员基础页面」）。
 *
 * <p>为什么需要它：审计记录此前的状态是「只写不读」——有实体、有 Mapper、
 * 有写入路径，但没有任何查询入口。这样做的实际后果是：
 * <ul>
 *   <li>管理员查看用户文档时确实留了痕，但事后无法查证；</li>
 *   <li>账号被禁用、角色被调整、密钥被更换这些敏感操作同样无法追溯；</li>
 *   <li>写入逻辑是否正确（字段有没有填、脱敏有没有做）也无法验证。</li>
 * </ul>
 * 也就是说，「审计」在没有查询入口时并不成立。
 *
 * <p>排序固定为时间倒序，不接受前端指定排序字段，避免 ORDER BY 注入。
 */
@Data
public class AuditQueryDTO {

    @Min(value = 1, message = "页码必须大于 0")
    private long page = 1;

    @Min(value = 1, message = "每页条数必须大于 0")
    @Max(value = 100, message = "每页最多 100 条")
    private long size = 20;

    /** 按操作者用户 ID 过滤。 */
    private Long userId;

    /** 按动作过滤，如 USER_DISABLE、ADMIN_FILE_VIEW，见 AuditAction。 */
    @Size(max = 64, message = "动作标识过长")
    private String action;

    /** 按目标类型过滤：USER / FILE / VERSION / TASK / MODEL_CONFIG。 */
    @Size(max = 32, message = "目标类型过长")
    private String targetType;

    /** 按目标标识过滤（通常是资源 ID）。 */
    @Size(max = 64, message = "目标标识过长")
    private String targetId;

    /** 按结果过滤：SUCCESS / FAILURE。 */
    @Size(max = 16, message = "结果标识过长")
    private String result;

    /** 起始时间（含），ISO-8601 本地时间。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime from;

    /** 结束时间（含）。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime to;

    /**
     * 按请求链路 ID 过滤。
     *
     * <p>用途：用户报告「某次操作异常」时，前端会把响应头里的 X-Request-ID 给他，
     * 用这个 ID 能直接定位到那一次调用产生的全部审计记录。
     */
    @Size(max = 64, message = "请求 ID 过长")
    private String requestId;
}
