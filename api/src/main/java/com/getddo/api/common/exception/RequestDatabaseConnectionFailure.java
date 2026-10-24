package com.getddo.api.common.exception;

import java.sql.SQLException;

import com.zaxxer.hikari.SQLExceptionOverride;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Hikari가 폐기할 연결의 오류 근거를 현재 MVC 요청에만 보관한다.
 *
 * <p>연결 단절 뒤 JPA 롤백이 실패하면 원래 SQLState가 없는 예외로 대체될 수 있다. JDBC 경계에서 확인한
 * 분류 결과만 보관하며 SQL·예외 원문은 보관하지 않는다. 요청 밖의 워커에서는 기록하지 않고, Hikari의
 * 기본 연결 폐기 판단과 쿼리 실행·재시도 동작은 변경하지 않는다.</p>
 */
public final class RequestDatabaseConnectionFailure implements SQLExceptionOverride {

	private static final String ATTRIBUTE = RequestDatabaseConnectionFailure.class.getName();

	/** Hikari가 직접 생성하는 콜백이므로 Spring Bean이나 사용자 문맥 조회에 의존하지 않는다. */
	public RequestDatabaseConnectionFailure() {
	}

	/** 원인 사슬에서 확인한 연결 실패 근거만 요청 수명 동안 보관하고 기본 폐기 절차를 계속한다. */
	@java.lang.Override
	public SQLExceptionOverride.Override adjudicate(SQLException exception) {
		String category = DatabaseConnectionFailures.classify(exception);
		RequestAttributes request = RequestContextHolder.getRequestAttributes();
		if (category != null && request != null) {
			request.setAttribute(ATTRIBUTE, category, RequestAttributes.SCOPE_REQUEST);
		}
		return SQLExceptionOverride.Override.CONTINUE_EVICT;
	}

	/** 다른 요청이나 워커로 근거가 전파되지 않도록 요청 속성만 사용한다. */
	static String currentFailure() {
		RequestAttributes request = RequestContextHolder.getRequestAttributes();
		return request == null ? null : (String) request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
	}
}
