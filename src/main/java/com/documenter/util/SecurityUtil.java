package com.documenter.util;

import com.documenter.entity.User;
import com.documenter.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class SecurityUtil {
	public static User getUser(){
		Authentication authentication= SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof User user) {
			return user;
		}
		return null;
	}

	/**
	 * 取当前登录用户 ID，未认证直接报 401。
	 *
	 * <p>业务代码统一用这个方法的返回值做归属校验，不要信任前端传入的用户 ID（TODO 3.2）。
	 */
	public static Long requireUserId() {
		User user = getUser();
		if (user == null || user.getId() == null) {
			throw new BusinessException(401, "未授权访问");
		}
		return user.getId();
	}
}
