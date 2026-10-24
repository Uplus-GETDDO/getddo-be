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
 * 공통 예외 처리기가 HTTP 503으로 응답할 DB 연결 오류를 선별하는 API 내부 보조 클래스다.
 * 예외를 새로 던지거나 DB 연결을 복구하지 않으며, 공통 오류 응답의 분류 책임이므로 common.exception에 둔다.
 *
 * <p>Controller까지 전달된 예외에는 Spring·JPA의 포장이 겹칠 수 있어 원인과 JDBC nextException을 함께 확인한다.
 * SQLState 08은 연결 예외 계열이며, 일반 쿼리 시간초과나 트랜잭션 실패 자체는 연결 장애의 근거가 아니다.</p>
 *
 * <p>Hikari의 풀 고갈도 연결 획득 실패이지만 RDS 장애를 뜻하지 않는다. SQLState 08이 있는 통신 실패와
 * 연결 획득·연결 전용 wrapper를 구분해 로그에 남긴다. 예외 메시지나 클래스 이름 문자열은 판별에 쓰지 않는다.</p>
 */
final class DatabaseConnectionFailures {

	private DatabaseConnectionFailures() {
	}

	/**
	 * 원인과 JDBC nextException을 순환 없이 살펴본다.
	 *
	 * @return 연결 장애가 확인되면 원문을 제외한 로그용 분류·SQLState·vendor code·예외 타입,
	 *         근거가 없으면 null. HTTP 상태와 공개 메시지는 공통 예외 처리기가 결정한다.
	 */
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

	/**
	 * 원인 사슬에 실제 트랜잭션 실패가 있는지 확인한다. JPA 롤백 오류가 원래 단절 원인을 덮어쓸 때만
	 * 현재 요청에 보관한 JDBC 근거를 사용하기 위한 조건이다. 무관한 후속 SQL 오류까지 503으로 바꾸지 않는다.
	 */
	static boolean isTransactionFailure(Throwable failure) {
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
			if (cause instanceof org.hibernate.TransactionException
					|| cause instanceof org.springframework.transaction.TransactionException) {
				return true;
			}
		}
		return false;
	}

}
