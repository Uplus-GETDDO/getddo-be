package com.getddo.api.game.config;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.getddo.api.common.exception.CommonErrorCode;
import com.getddo.api.common.response.ResponseEnvelope;
import com.getddo.api.game.controller.GameResultController;
import com.getddo.core.game.exception.GameResultFormatException;

/** G04의 게임별 입력 형식 불일치만 기존 COMMON-005로 응답한다. 공통 예외 처리기는 변경하지 않는다. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = GameResultController.class)
public class GameResultExceptionAdvice {

	@ExceptionHandler(GameResultFormatException.class)
	public ResponseEntity<ResponseEnvelope<Void>> handleFormat(GameResultFormatException exception) {
		CommonErrorCode error = CommonErrorCode.INVALID_FORMAT;
		return ResponseEntity.status(error.getStatus()).body(ResponseEnvelope.fail(error));
	}
}
