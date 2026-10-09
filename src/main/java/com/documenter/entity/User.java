package com.documenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("`user`")
@Data
public class User implements Serializable {
	@Serial
	private static final long serialVersionUID=1L;

	/**
	 * 主键改为数据库自增，与 V1__baseline.sql 里的 AUTO_INCREMENT 保持一致。
	 * 原为 IdType.ASSIGN_ID（雪花 ID），会绕过自增列写入 19 位大整数，与表定义矛盾。
	 */
	@TableId(value = "id", type = IdType.AUTO)
	private Long id;
	private String phone;
	private String username;
	private String password;
	private String role;
	private BigDecimal balance;

	/** 账号状态：ENABLED / DISABLED。禁用后不允许登录，已签发会话需同时撤销。 */
	private String status;
	private LocalDateTime createTime;
	private LocalDateTime updateTime;
	private LocalDateTime lastLoginTime;

	/** 是否为启用状态（仅用于业务判断，不入库）。 */
	public boolean isEnabled() {
		return "ENABLED".equals(status);
	}

	/** 是否为管理员（仅用于业务判断，不入库）。 */
	public boolean isAdmin() {
		return "ADMIN".equals(role);
	}
}
