package com.getddo.api.common.exception;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** MVC의 예외 해석에 들어가기 전 분류 함수 자체의 순환 방어와 JDBC 체인을 검증한다. */
class DatabaseConnectionFailuresTest {

	@Test
	@DisplayName("순환하는 원인에도 종료하고 근거 없는 예외는 연결 장애로 바꾸지 않는다")
	void cyclicCausesTerminate() {
		// given
		RuntimeException first = new RuntimeException("first");
		RuntimeException second = new RuntimeException("second", first);
		first.initCause(second);
		// when / then
		assertThat(DatabaseConnectionFailures.classify(first)).isNull();
		assertThat(DatabaseConnectionFailures.isTransactionFailure(first)).isFalse();
	}

	@Test
	@DisplayName("순환하는 JDBC nextException에서 연결 SQLState를 발견한다")
	void followsJdbcChainWithoutLooping() {
		// given
		SQLException first = new SQLException("wrapper");
		SQLException second = new SQLException("connection", "08006");
		first.setNextException(second);
		second.setNextException(first);
		// when / then
		assertThat(DatabaseConnectionFailures.classify(first)).startsWith("JDBC_CONNECTION_FAILURE sqlState=08006 vendorCode=0");
	}
}
