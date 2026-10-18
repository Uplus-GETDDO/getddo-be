package com.getddo.db.ticket;

import java.time.Clock;
import java.time.Instant;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.getddo.core.common.time.TimeProvider;
import com.getddo.db.common.config.JpaAuditingConfig;
import com.getddo.db.support.MySqlTestConfiguration;

/**
 * 응모권·출석·응모 통합 테스트 컨텍스트.
 *
 * <p>응모권·출석·응모·미션의 Entity·JPA Repository만 등록하고, core의 Service와 storage:db의 저장소 구현을 함께 올려
 * 실제 호출 경로를 검증한다. 출석은 응모권 지급을, 응모는 응모권 차감을 호출하므로 도메인을 한 컨텍스트에서 검증한다.</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = {
		"com.getddo.core.ticket", "com.getddo.db.ticket",
		"com.getddo.core.attendance", "com.getddo.db.attendance",
		"com.getddo.core.entry", "com.getddo.db.entry",
		"com.getddo.core.mission", "com.getddo.db.mission",
		"com.getddo.core.stats", "com.getddo.db.stats",
		"com.getddo.core.audit", "com.getddo.db.audit.repository", "com.getddo.db.audit.mapper"})
@EntityScan(basePackages = {"com.getddo.db.ticket", "com.getddo.db.attendance", "com.getddo.db.entry",
		"com.getddo.db.mission", "com.getddo.db.audit.entity"})
@EnableJpaRepositories(basePackages = {"com.getddo.db.ticket", "com.getddo.db.attendance", "com.getddo.db.entry",
		"com.getddo.db.mission", "com.getddo.db.audit.repository"})
@Import({JpaAuditingConfig.class, MySqlTestConfiguration.class})
public class TicketIntegrationTestApplication {

	public static final Instant INITIAL_TIME = Instant.parse("2026-09-15T03:00:00Z");

	@Bean
	MutableClock clock() {
		return new MutableClock(INITIAL_TIME);
	}

	@Bean
	TimeProvider timeProvider(Clock clock) {
		return new TimeProvider(clock);
	}
}
