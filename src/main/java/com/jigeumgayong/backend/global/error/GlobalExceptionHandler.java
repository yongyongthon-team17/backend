package com.jigeumgayong.backend.global.error;

import com.jigeumgayong.backend.global.web.RequestIdHolder;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
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

	/** 필수 헤더·경로 변수 누락 등. 쿼리 파라미터 누락은 아래 전용 핸들러가 먼저 처리한다. */
	@ExceptionHandler(ServletRequestBindingException.class)
	public ResponseEntity<ErrorResponse> handleBinding(ServletRequestBindingException e) {
		String field = switch (e) {
			case MissingRequestHeaderException h -> h.getHeaderName();
			case MissingPathVariableException p -> p.getVariableName();
			default -> "request";
		};
		String reason = e instanceof MissingRequestHeaderException || e instanceof MissingPathVariableException
				? ErrorDetail.REQUIRED : ErrorDetail.INVALID_VALUE;
		return build(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage(),
				List.of(new ErrorDetail(field, reason)));
	}

	/** 컨트롤러 파라미터에 직접 붙인 @Min 등 검증 실패 (Spring 6.1+ 내장 메서드 검증). */
	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException e) {
		List<ErrorDetail> details = e.getParameterValidationResults().stream()
				.flatMap(r -> {
					String name = r.getMethodParameter().getParameterName();
					String field = name != null ? name : "arg" + r.getMethodParameter().getParameterIndex();
					return r.getResolvableErrors().stream()
							.map(err -> new ErrorDetail(field, reasonOf(constraintOf(err))));
				})
				.collect(Collectors.toList());
		return build(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage(), details);
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

	/** 존재하지 않는 경로. FACILITY_NOT_FOUND(시설 조회 전용)와 구분되는 NOT_FOUND를 쓴다. */
	@ExceptionHandler({ NoResourceFoundException.class, NoHandlerFoundException.class })
	public ResponseEntity<ErrorResponse> handleNoResource(Exception e) {
		return build(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.defaultMessage(), List.of());
	}

	/**
	 * DB 접근·트랜잭션 시작 실패(DB 다운 등)는 500이 아니라 503 DATA_UNAVAILABLE (07 3.5).
	 * 원인은 로그에만 남긴다.
	 */
	@ExceptionHandler({ DataAccessException.class, TransactionException.class })
	public ResponseEntity<ErrorResponse> handleDataAccess(Exception e) {
		log.error("Data access failure", e);
		return build(ErrorCode.DATA_UNAVAILABLE, ErrorCode.DATA_UNAVAILABLE.defaultMessage(), List.of());
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

	/** 오류 코드 목록(["Min.dayOfWeek", ..., "Min"])의 마지막 값이 제약 이름이다. */
	private String constraintOf(MessageSourceResolvable error) {
		String[] codes = error.getCodes();
		return codes == null || codes.length == 0 ? null : codes[codes.length - 1];
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
