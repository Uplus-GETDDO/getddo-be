package com.getddo.api.common.exception;

import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.getddo.api.common.ratelimit.RateLimitExceededException;
import com.getddo.api.common.response.ResponseEnvelope;
import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.exception.ErrorCode;

/**
 * Spring MVC의 Controller 처리 과정에서 전달되는 예외를 공통 JSON 응답으로 변환한다.
 *
 * <p>업무 예외는 정의된 오류 계약을 사용하고, 표준 MVC 예외는 Spring이 정한 상태와 헤더를 유지한다.
 * DB 연결 장애는 공통 503으로, 그 외 예상하지 못한 예외는 공통 500 응답으로 처리한다.
 * ResponseEntityExceptionHandler를 상속하는 이유는 405·415 등의 요청 오류를
 * 일반 Exception 처리기가 잘못 500으로 바꾸지 않도록 하기 위해서다.</p>
 *
 * <p>성공 응답을 자동으로 감싸는 기능은 아니다. 성공 응답은 Controller에서 작성한다.
 * 또한 MVC 밖의 필터나 별도 비동기 작업에서 발생한 모든 예외를 처리하는 것은 아니다.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	/**
	 * 요청 빈도 한도를 넘은 요청을 429로 응답한다. 다른 업무 오류와 달리 {@code Retry-After} 헤더에 다시 시도할 수 있을 때까지
	 * 남은 초를 담는다. 더 구체적인 예외 타입이라 아래 {@link BusinessException} 처리기보다 먼저 선택된다.
	 */
	@ExceptionHandler(RateLimitExceededException.class)
	public ResponseEntity<ResponseEnvelope<Void>> handleRateLimitExceeded(RateLimitExceededException exception) {
		ErrorCode errorCode = exception.getErrorCode();
		return ResponseEntity.status(errorCode.getStatus())
				.header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.getRetryAfterSeconds()))
				.body(ResponseEnvelope.fail(errorCode));
	}

	/**
	 * 업무 코드가 전달한 상태·코드·메시지를 그대로 응답 계약으로 사용한다.
	 * <p>예외별로 Controller에 try-catch를 반복하지 않아도 된다.
	 * 오류 메시지는 ErrorCode에 정의된 공개용 문구를 사용하므로 오류 정의 시 노출 범위를 확인한다.</p>
	 */
	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ResponseEnvelope<Void>> handleBusinessException(BusinessException exception) {
		ErrorCode errorCode = exception.getErrorCode();
		return ResponseEntity.status(errorCode.getStatus()).body(ResponseEnvelope.fail(errorCode));
	}

	/**
	 * 부모 클래스가 분류한 표준 MVC 예외의 응답 본문을 우리 공통 형식으로 교체한다.
	 *
	 * @param exception Spring이 처리 중인 MVC 예외
	 * @param body Spring이 준비한 기본 오류 본문. 내부 상세를 그대로 노출하지 않고 교체한다.
	 * @param headers Spring이 결정한 응답 헤더. 405의 Allow 등도 포함된다.
	 * @param status Spring이 결정한 HTTP 상태. 공통 본문으로 바꿔도 상태는 유지한다.
	 * @param request 응답 완료 여부 등 부모 클래스의 처리에 필요한 현재 요청
	 */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(
			Exception exception, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request
	) {
		ResponseEnvelope<Void> response;
		if (status.is5xxServerError()) {
			// 응답 변환 실패 등 서버 측 MVC 오류는 로그에 원인을 남기고 응답에는 고정 문구만 담는다.
			logger.error("Unhandled MVC server error", exception);
			response = ResponseEnvelope.fail(CommonErrorCode.INTERNAL_ERROR);
		} else if (status.value() == 400) {
			// 요청을 해석하지 못한 경우와, 해석은 했지만 입력 조건을 만족하지 못한 경우를 구분한다.
			boolean invalidFormat = exception instanceof HttpMessageNotReadableException
					|| exception instanceof TypeMismatchException;
			response = ResponseEnvelope.fail(invalidFormat
					? CommonErrorCode.INVALID_FORMAT : CommonErrorCode.INVALID_INPUT);
		} else {
			// 404·405·406·415 등의 상태를 보존하고 예외 원문은 공개하지 않는다.
			response = new ResponseEnvelope<>(false, "HTTP-" + status.value(),
					"요청을 처리할 수 없습니다.", null);
		}
		// Allow 등의 헤더와 이미 응답이 전송된 경우의 처리는 Spring에 맡긴다.
		return super.handleExceptionInternal(exception, response, headers, status, request);
	}

	/**
	 * 업무 예외나 표준 MVC 예외로 분류되지 않은 오류의 마지막 처리 경로다.
	 * 원인 분석을 위해 서버 로그에는 예외를 남기지만 응답에는 예외 메시지·스택 트레이스를 싣지 않는다.
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ResponseEnvelope<Void>> handleUnexpected(Exception exception) {
		String category = DatabaseConnectionFailures.classify(exception);
		if (category == null && DatabaseConnectionFailures.isTransactionFailure(exception)) {
			// 실제 JDBC 연결 오류가 같은 요청에서 확인됐을 때만 롤백·정리 오류에 가려진 근거를 사용한다.
			category = RequestDatabaseConnectionFailure.currentFailure();
		}
		if (category != null) {
			// SQL·호스트·개인정보가 들어갈 수 있는 원문과 스택은 이 경로에서 기록하지 않는다.
			logger.warn("Database request unavailable: code=COMMON-007 category=" + category
					+ " exceptionType=" + exception.getClass().getName());
			ErrorCode unavailable = CommonErrorCode.DB_TEMPORARILY_UNAVAILABLE;
			return ResponseEntity.status(unavailable.getStatus()).body(ResponseEnvelope.fail(unavailable));
		}
		logger.error("Unhandled request exception", exception);
		ErrorCode errorCode = CommonErrorCode.INTERNAL_ERROR;
		return ResponseEntity.status(errorCode.getStatus()).body(ResponseEnvelope.fail(errorCode));
	}
}
