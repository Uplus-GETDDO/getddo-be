package com.getddo.api.common.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.GenericApplicationContext;

import com.zaxxer.hikari.HikariDataSource;

import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrometheusManagementTest {

	@Test
	@DisplayName("별도 관리 서버에만 health·Prometheus와 HTTP histogram·풀·Tomcat·JVM 지표를 제공한다")
	void privateManagementMetrics() throws Exception {
		// given: 실제 HTTP 서버 두 개와 연결 대역을 쓰는 Hikari 풀로 binder·포트 경계를 검증한다.
		CloudWatchAsyncClient client = mock(CloudWatchAsyncClient.class);
		when(client.putMetricData(any(PutMetricDataRequest.class)))
			.thenReturn(CompletableFuture.completedFuture(PutMetricDataResponse.builder().build()));
		SpringApplication application = new SpringApplication(TestApplication.class);
		application.addInitializers(context -> {
			GenericApplicationContext beans = (GenericApplicationContext) context;
			beans.registerBean(CloudWatchAsyncClient.class, () -> client);
			beans.registerBean(AwsCredentialsProvider.class, () -> mock(AwsCredentialsProvider.class));
		});
		try (ConfigurableApplicationContext context = application.run(
			"--spring.profiles.active=monitoring", "--server.port=0", "--management.server.port=0",
			"--spring.flyway.enabled=false", "--spring.data.jpa.repositories.enabled=false",
			"--spring.jpa.hibernate.ddl-auto=none", "--management.health.redis.enabled=false", "--getddo.metrics.enabled=true",
			"--getddo.metrics.server=a", "--getddo.metrics.region=ap-northeast-2")) {
			int appPort = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
			int managementPort = context.getEnvironment().getRequiredProperty("local.management.port", Integer.class);
			assertThat(appPort).isNotEqualTo(managementPort);
			try (Connection connection = context.getBean(DataSource.class).getConnection()) {
				assertThat(connection.isValid(1)).isTrue();
			}
			MeterRegistry registry = context.getBean(PrometheusMeterRegistry.class);
			Timer.builder("test.other.timer").register(registry).record(Duration.ofMillis(10));

			// when: 알 수 없는 경로의 실제 HTTP 요청도 URI 값으로 직접 태깅하지 않는다.
			assertThat(get(appPort, "/unknown-one").statusCode()).isEqualTo(404);
			assertThat(get(appPort, "/unknown-two").statusCode()).isEqualTo(404);
			String metrics = get(managementPort, "/actuator/prometheus").body();

			// then
			assertThat(get(managementPort, "/actuator/health").statusCode()).isEqualTo(200);
			for (String path : new String[] {"/actuator", "/actuator/prometheus", "/actuator/health",
				"/actuator/metrics", "/actuator/env", "/actuator/heapdump", "/actuator/configprops"}) {
				assertThat(get(appPort, path).statusCode()).isEqualTo(404);
				if (!path.equals("/actuator/prometheus") && !path.equals("/actuator/health")) {
					assertThat(get(managementPort, path).statusCode()).isEqualTo(404);
				}
			}
			assertThat(metrics).contains("http_server_requests_seconds_count", "http_server_requests_seconds_sum",
				"http_server_requests_seconds_bucket", "hikaricp_connections_active", "hikaricp_connections_idle",
				"hikaricp_connections_max", "hikaricp_connections_pending", "hikaricp_connections_acquire_seconds",
				"hikaricp_connections_timeout_total", "tomcat_threads_busy_threads", "tomcat_threads_current_threads",
				"tomcat_threads_config_max_threads", "jvm_memory_used_bytes", "jvm_threads_live_threads", "jvm_gc_pause_seconds")
				.doesNotContain("test_other_timer_seconds_bucket", "unknown-one", "unknown-two");
			assertThat(metrics.lines().filter(line -> line.startsWith("tomcat_threads_busy_threads")))
				.anyMatch(line -> line.contains("http-nio-auto-"));
			for (String metric : new String[] {"http_server_requests_seconds_bucket", "jvm_memory_used_bytes",
				"tomcat_threads_busy_threads", "hikaricp_connections_max"}) {
				assertThat(hasFiniteSample(metrics, metric)).as(metric).isTrue();
			}
			CloudWatchMeterRegistry cloudWatch = context.getBean(CloudWatchMeterRegistry.class);
			assertThat(cloudWatch.find("http.server.requests").timer()).isNull();
			assertThat(cloudWatch.get("hikaricp.connections.active").gauge().getId().getTag("environment")).isEqualTo("dev");
			assertThat(cloudWatch.get("hikaricp.connections.active").gauge().getId().getTag("server")).isEqualTo("a");
			assertThat(metrics).doesNotContain("environment=", "server=");
			long bucketCount = metrics.lines().filter(line -> line.startsWith("http_server_requests_seconds_bucket")
				&& line.contains("status=\"404\"")).count();
			assertThat(bucketCount).isBetween(2L, 100L);
		}
	}

	@Test
	@DisplayName("필수 지표의 주석·이름·잘못된 숫자·무한대는 정상 샘플로 인정하지 않는다")
	void finiteSampleValidation() {
		// given / when / then
		for (String sample : new String[] {"# HELP test_metric description", "test_metric",
			"test_metric 1..2", "test_metric 1e999", "test_metric NaN", "test_metric +Inf"}) {
			assertThat(hasFiniteSample(sample, "test_metric")).as(sample).isFalse();
		}
		assertThat(hasFiniteSample("test_metric{pool=\"api\"} 1.25e+2", "test_metric")).isTrue();
		assertThat(hasFiniteSample("test_metric 0", "test_metric")).isTrue();
	}

	/** 필수 지표의 실제 샘플 행에 문법상 올바른 유한 숫자가 있는지 확인한다. */
	private boolean hasFiniteSample(String metrics, String metricName) {
		Pattern samplePattern = Pattern.compile("^" + Pattern.quote(metricName)
			+ "(?:\\{.*\\})?\\s+([-+]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?)(?:\\s+[0-9]+)?$");
		return metrics.lines()
			.map(String::trim)
			.map(samplePattern::matcher)
			.filter(matcher -> matcher.matches())
			.anyMatch(matcher -> Double.isFinite(Double.parseDouble(matcher.group(1))));
	}

	private HttpResponse<String> get(int port, String path) throws Exception {
		return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
			.timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableAutoConfiguration(excludeName = "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration")
	@Import(CloudWatchMetricsConfig.class)
	static class TestApplication {
		@Bean(destroyMethod = "close")
		HikariDataSource dataSource() throws Exception {
			DataSource source = mock(DataSource.class);
			when(source.getConnection()).thenAnswer(invocation -> {
				Connection connection = mock(Connection.class);
				DatabaseMetaData metadata = mock(DatabaseMetaData.class);
				when(metadata.getDatabaseProductName()).thenReturn("test");
				when(connection.getMetaData()).thenReturn(metadata);
				when(connection.isValid(anyInt())).thenReturn(true);
				when(connection.getAutoCommit()).thenReturn(true);
				when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
				return connection;
			});
			HikariDataSource pool = new HikariDataSource();
			pool.setDataSource(source);
			pool.setPoolName("getddo");
			pool.setMaximumPoolSize(2);
			return pool;
		}
	}
}
