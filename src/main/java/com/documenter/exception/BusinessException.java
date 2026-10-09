package com.documenter.exception;

import lombok.Data;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
	private Integer code;
	private String message;
	
	public BusinessException(Integer code, String message) {
		super(message);
		this.code = code;
	}
	
	public BusinessException(String message) {
		this(500, message);
	}
}
