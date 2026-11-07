package com.jigeumgayong.backend.global.error;

import com.jigeumgayong.backend.global.web.RequestIdHolder;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
		return build(e.getErrorCode(), e.getMessage(), e.getDetails());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleNotValid(MethodArgumentNotValidException e) {
		List<ErrorDetail> details = e.getBindingResult().getFieldErrors().stream()
				.map(f -> new ErrorDetail(f.getField(), reasonOf(f.getCode())))
				.collect(Collectors.toList());
		return build(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage(), details);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> handleConstraint(ConstraintViolationException e) {
		List<ErrorDetail> details = e.getConstraintViolations().stream()
				.map(v -> {
					String path = v.getPropertyPath().toString();
					String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
					String type = v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
					return new ErrorDetail(field, reasonOf(type));
				})
				.collect(Collectors.toList());
		return build(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage(), details);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
		return build(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage(),
				List.of(new ErrorDetail(e.getName(), ErrorDetail.INVALID_FORMAT)));
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e) {
		return build(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage(),
				List.of(new ErrorDetail(e.getParameterName(), ErrorDetail.REQUIRED)));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
		return build(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage(), List.of());
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
		ErrorCode code = ErrorCode.METHOD_NOT_ALLOWED;
		ResponseEntity.BodyBuilder builder = ResponseEntity.status(code.status());
		if (e.getSupportedHttpMethods() != null && !e.getSupportedHttpMethods().isEmpty()) {
			HttpHeaders headers = new HttpHeaders();
			headers.setAllow(e.getSupportedHttpMethods());
			builder.headers(headers);
		}
		return builder.body(body(code, code.defaultMessage(), List.of()));
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException e) {
		return build(ErrorCode.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE.defaultMessage(), List.of());
	}

	/** 07에 일반 404 코드가 없어 INVALID_REQUEST + 404 상태로 처리 (미확정 사항). */
	@ExceptionHandler({ NoResourceFoundException.class, NoHandlerFoundException.class })
	public ResponseEntity<ErrorResponse> handleNoResource(Exception e) {
		return ResponseEntity.status(404)
				.body(body(ErrorCode.INVALID_REQUEST, "요청한 경로를 찾을 수 없습니다.", List.of()));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
		log.error("Unexpected error", e);
		return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), List.of());
	}

	private ResponseEntity<ErrorResponse> build(ErrorCode code, String message, List<ErrorDetail> details) {
		return ResponseEntity.status(code.status()).body(body(code, message, details));
	}

	private ErrorResponse body(ErrorCode code, String message, List<ErrorDetail> details) {
		return ErrorResponse.of(code, message, details, RequestIdHolder.currentOrNew());
	}

	private String reasonOf(String constraint) {
		if (constraint == null) {
			return ErrorDetail.INVALID_VALUE;
		}
		return switch (constraint) {
			case "NotNull", "NotBlank", "NotEmpty" -> ErrorDetail.REQUIRED;
			case "Min", "Max", "Range", "DecimalMin", "DecimalMax", "Positive", "PositiveOrZero", "Size" ->
				ErrorDetail.OUT_OF_RANGE;
			case "Pattern" -> ErrorDetail.INVALID_FORMAT;
			default -> ErrorDetail.INVALID_VALUE;
		};
	}
}
