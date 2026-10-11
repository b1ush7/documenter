package com.documenter.vo;

import com.documenter.entity.User;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 当前用户信息响应（TODO 3.1）。
 *
 * <p>刻意不包含 password 字段：实体直接返回到前端会把 BCrypt 哈希暴露出去，
 * 因此统一用本 VO 做出口，禁止把 User 实体写进 ApiResponse。
 */
@Data
public class UserInfoVO {

    private Long id;
    private String username;
    private String phone;
    private String role;
    private String status;
    private BigDecimal balance;
    private LocalDateTime createTime;
    private LocalDateTime lastLoginTime;

    public static UserInfoVO from(User user) {
        UserInfoVO vo = new UserInfoVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setPhone(user.getPhone());
        vo.setRole(user.getRole());
        vo.setStatus(user.getStatus());
        vo.setBalance(user.getBalance());
        vo.setCreateTime(user.getCreateTime());
        vo.setLastLoginTime(user.getLastLoginTime());
        return vo;
    }
}
