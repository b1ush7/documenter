package com.documenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

@TableName("`user`")
@Data
public class User implements Serializable {
	@Serial
	private static final long serialVersionUID=1L;
	
	@TableId(value = "id",type = IdType.ASSIGN_ID)
	private Long id;
	private String phone;
	private String username;
	private String password;
	private String role;
	private BigDecimal balance;
}
