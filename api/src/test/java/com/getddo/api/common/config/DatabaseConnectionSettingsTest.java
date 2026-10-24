package com.getddo.api.common.config;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** DB 접속 없이 배포 프로파일 바인딩·드라이버 해석과 Java 21 보안 속성의 실제 적용을 검증한다. */
class DatabaseConnectionSettingsTest {

	/** JDBC URL의 중복 값보다 배포 data-source-properties의 유한 60초가 우선함을 실제 드라이버로 확인한다. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("기본 배포의 socket 60초는 JDBC URL의 중복 5초 값보다 우선한다")
	void deploymentSettingsBindToPoolAndDriver(boolean duplicateUrlLimit) {
		// given
		new ApplicationContextRunner()
				.withInitializer(new ConfigDataApplicationContextInitializer())
				.withPropertyValues("spring.profiles.active=monitoring",
						"spring.datasource.url=jdbc:mysql://localhost/test" + (duplicateUrlLimit ? "?socketTimeout=5000&connectTimeout=5000" : ""))
				.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
				.run(context -> {
					// when
					assertThat(context).hasNotFailed();
					HikariDataSource pool = context.getBean(HikariDataSource.class);
					Driver driver = DriverManager.getDriver(pool.getJdbcUrl());
					Map<String, String> properties = Arrays.stream(driver.getPropertyInfo(pool.getJdbcUrl(), pool.getDataSourceProperties()))
							.filter(property -> property.name.equals("connectTimeout") || property.name.equals("socketTimeout"))
							.collect(Collectors.toMap(property -> property.name, property -> property.value));
					// then
					assertThat(pool.getExceptionOverrideClassName()).isEqualTo(
							"com.getddo.api.common.exception.RequestDatabaseConnectionFailure");
					assertThat(pool.getConnectionTimeout()).isEqualTo(2000);
					assertThat(pool.getValidationTimeout()).isEqualTo(1000);
					assertThat(properties).containsEntry("connectTimeout", "2000").containsEntry("socketTimeout", "60000");
				});
	}

	/** 새 JVM으로 이미지에 포함할 보안 속성 파일을 읽어 DNS TTL이 실제 Java 보안 속성으로 적용되는지 검증한다. */
	@Test
	@DisplayName("컨테이너에 복사할 보안 속성 파일을 Java 21에 주입하면 DNS TTL 30초와 음성 TTL 5초가 적용된다")
	void securityFileAppliesToNewJvm(@TempDir Path temporary) throws Exception {
		// given
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");
		Path security = Path.of("..", "config", "rds-ha.security").toAbsolutePath().normalize();
		Path outputFile = temporary.resolve("security-settings.log");
		// when
		Process process = new ProcessBuilder(java.toString(), "-Djava.security.properties=" + security,
				"-XshowSettings:security:properties", "-version")
				.redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
		try {
			boolean exited = process.waitFor(10, TimeUnit.SECONDS);
			// then
			assertThat(exited).isTrue();
			String output = Files.readString(outputFile, StandardCharsets.UTF_8);
			assertThat(process.exitValue()).isZero();
			assertThat(output).contains("networkaddress.cache.ttl=30", "networkaddress.cache.negative.ttl=5");
		} finally {
			if (process.isAlive()) {
				process.destroyForcibly();
				process.waitFor(5, TimeUnit.SECONDS);
			}
		}
	}
}
