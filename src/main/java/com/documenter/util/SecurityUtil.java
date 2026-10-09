package com.documenter.util;

import com.documenter.entity.User;
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
}
