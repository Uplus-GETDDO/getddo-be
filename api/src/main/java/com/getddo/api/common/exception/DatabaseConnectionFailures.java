package com.getddo.api.common.exception;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

import org.hibernate.exception.JDBCConnectionException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/**
 * HTTP 경계에서 JDBC 연결 실패만 판별한다. 쿼리 시간초과나 트랜잭션 실패 자체는 연결 장애의 근거가 아니다.
 *
 * <p>Hikari의 풀 고갈도 연결 획득 실패이지만 RDS 장애를 뜻하지 않는다. SQLState 08이 있는 통신 실패와
 * 연결 획득·연결 전용 wrapper를 구분해 로그에 남긴다. 예외 메시지나 클래스 이름 문자열은 판별에 쓰지 않는다.</p>
 */
final class DatabaseConnectionFailures {

	private DatabaseConnectionFailures() {
	}

	/** 원인과 JDBC nextException을 순환 없이 살펴보고, 연결 장애 근거가 없으면 null을 반환한다. */
	static String classify(Throwable failure) {
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		Deque<Throwable> pending = new ArrayDeque<>();
		pending.add(failure);
		String category = null;
		while (!pending.isEmpty()) {
			Throwable current = pending.removeFirst();
			if (!visited.add(current)) {
				continue;
			}
			if (current instanceof SQLException sql) {
				String state = sql.getSQLState();
				if (state != null && state.startsWith("08")) {
					return "JDBC_CONNECTION_FAILURE sqlState=" + state.replaceAll("[^A-Za-z0-9]", "?")
						+ " vendorCode=" + sql.getErrorCode() + " causeType=" + current.getClass().getName();
				}
				if (sql.getNextException() != null) {
					pending.addLast(sql.getNextException());
				}
			}
			if (current instanceof CannotGetJdbcConnectionException || current instanceof JDBCConnectionException) {
				category = "CONNECTION_RESOURCE_UNAVAILABLE causeType=" + current.getClass().getName();
			}
			if (current.getCause() != null) {
				pending.addLast(current.getCause());
			}
		}
		return category;
	}
}
