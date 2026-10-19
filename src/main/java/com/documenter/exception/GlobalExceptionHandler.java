package com.documenter.exception;

import com.documenter.constant.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> handleUploadLimit(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error(413, "上传文件或请求超过大小限制，请缩小文件后重试"));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Object>> handleMalformedUpload(MultipartException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, "上传表单无法解析，请检查文件和 multipart 格式"));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Object>> handleMissingUploadPart(MissingServletRequestPartException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, "上传表单缺少文件或必填字段"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Object>> handleUnreadableRequest(HttpMessageNotReadableException ex) {
        return ResponseEntity.ok(ApiResponse.error(400, "请求体缺失、JSON 格式错误或字段类型不正确"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Object>> handleArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.ok(ApiResponse.error(400, "参数类型不正确"));
    }

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
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.dao.ConcurrencyFailureException
                    || cause instanceof java.sql.SQLException sql &&
                    (sql.getErrorCode() == 1020 || sql.getErrorCode() == 1205 || sql.getErrorCode() == 1213
                            || "40001".equals(sql.getSQLState()))) {
                return ResponseEntity.ok(ApiResponse.error(409, "数据已被其他请求修改，请刷新后重试"));
            }
            if (cause.getCause() == cause) break;
        }
        if (com.documenter.util.DependencyFailures.unavailable(ex)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error(503, "依赖服务暂不可用，请稍后重试"));
        }
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
