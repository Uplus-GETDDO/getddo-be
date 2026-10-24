package com.getddo.api.common.exception;

import java.sql.SQLException;

import com.zaxxer.hikari.SQLExceptionOverride;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Hikari의 JDBC 예외 콜백에서 확인한 연결 오류 근거를 현재 MVC 요청에만 보관한다.
 *
 * <p>application.yaml의 spring.datasource.hikari.exception-override-class-name을 Spring Boot가 HikariConfig에
 * 바인딩하면, Hikari가 이 클래스를 리플렉션으로 생성하고 JDBC 오류를 검사할 때 adjudicate를 호출한다.
 * 생성과 호출은 Hikari가 담당하므로 이 객체는 Spring Bean 주입에 의존하지 않는다.</p>
 *
 * <p>통신 단절 → Hikari의 연결 폐기 → JPA 롤백 실패 순서에서는 마지막 오류가 원래 SQLState를 덮어쓸 수 있다.
 * 원래 통신 오류를 보지 못한 Advice가 500으로 응답하지 않도록 JDBC 경계에서 확인한 분류 결과만 보관한다.
 * SQL·예외 원문은 보관하지 않는다. 요청 밖의 워커에서는 기록하지 않고, Hikari의
 * 기본 연결 폐기 판단과 쿼리 실행·재시도 동작은 변경하지 않는다.</p>
 */
public final class RequestDatabaseConnectionFailure implements SQLExceptionOverride {

	private static final String ATTRIBUTE = RequestDatabaseConnectionFailure.class.getName();

	/** YAML의 클래스 이름으로 Hikari가 생성할 수 있도록 공개 기본 생성자를 제공한다. */
	public RequestDatabaseConnectionFailure() {
	}

	/**
	 * 연결 오류의 분류 결과만 요청 속성에 보관한다. 원문 예외·SQL은 보관하지 않는다.
	 * CONTINUE_EVICT는 Hikari의 기존 폐기 판단을 계속하라는 뜻이며, 쿼리 재실행을 지시하지 않는다.
	 */
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
