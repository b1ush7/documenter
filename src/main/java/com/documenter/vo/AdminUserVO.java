package com.documenter.vo;

import com.documenter.entity.User;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 管理员可见的用户账号信息，不包含密码哈希。 */
@Data
public class AdminUserVO {

    private Long id;
    private String username;
    private String phone;
    private String role;
    private String status;
    private BigDecimal balance;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime lastLoginTime;

    public static AdminUserVO from(User user) {
        AdminUserVO vo = new AdminUserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setPhone(user.getPhone());
        vo.setRole(user.getRole());
        vo.setStatus(user.getStatus());
        vo.setBalance(user.getBalance());
        vo.setCreateTime(user.getCreateTime());
        vo.setUpdateTime(user.getUpdateTime());
        vo.setLastLoginTime(user.getLastLoginTime());
        return vo;
    }
}
