package com.documenter.exception;

import com.documenter.constant.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {
	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ApiResponse<Object>> handleMissingServletRequestParameterException(Exception ex) {
		String errorMessage = "缺少请求参数：" + ex.getMessage();
		ApiResponse<Object> apiResponse = ApiResponse.error(400, errorMessage);
		return new ResponseEntity<>(apiResponse, HttpStatus.OK);
	}
	
	/**
	 * 处理参数校验异常（注释声明即可）
	 */
//	@ExceptionHandler(ConstraintViolationException.class)
//	public ResponseEntity<ApiResponse<Object>> handleConstraintViolationException(ConstraintViolationException ex) {
//		String errorMessage = ex.getMessage();
//		ApiResponse<Object> apiResponse = ApiResponse.error(400, errorMessage);
//		return new ResponseEntity<>(apiResponse, HttpStatus.OK);
//	}
	
	/**
	 * 处理参数校验异常（注释声明即可）
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Object>> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {
		ApiResponse<Object> apiResponse = ApiResponse.error(400, "参数传递有误或者缺少参数");
		return new ResponseEntity<>(apiResponse, HttpStatus.OK);
	}
	
	/**
	 * 处理业务逻辑异常（注释声明即可）
	 */
	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ApiResponse<Object>> handleBusinessException(BusinessException ex) {
		String errorMessage = ex.getMessage();
		ApiResponse<Object> apiResponse = ApiResponse.error(ex.getCode(), errorMessage);
		return new ResponseEntity<>(apiResponse, HttpStatus.OK);
	}
	
	/**
	 * 处理其他异常（注释声明即可）
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Object>> handleException(Exception ex) {
		String errorMessage = ex.getMessage();
		log.error("系统异常: {}", errorMessage, ex);  // 使用日志框架记录异常，包含完整堆栈
		ApiResponse<Object> apiResponse = ApiResponse.error(500, "服务器发生内部错误，您可以尝试刷新，如果问题依旧，请联系我们");
		return new ResponseEntity<>(apiResponse, HttpStatus.OK);
	}
	
	/**
	 * 处理方法不正确
	 * @param ex
	 * @return ResponseEntity
	 */
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ApiResponse<Object>> handleHttpRequestMethodNotSupportedException(Exception ex) {
		ApiResponse<Object> apiResponse = ApiResponse.error(405, "请求方法错误，请检查");
		return new ResponseEntity<>(apiResponse, HttpStatus.OK);
	}
}
