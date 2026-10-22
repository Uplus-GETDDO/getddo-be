package com.getddo.db.support;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.drawing.service.DrawSnapshotService;
import com.getddo.core.event.service.EventStatusChangeService;
import com.getddo.core.user.repository.UserNameRepository;
import com.getddo.db.common.config.JpaAuditingConfig;
import com.getddo.db.drawing.mapper.DrawSnapshotMapper;
import com.getddo.db.drawing.repository.DrawSnapshotRepositoryImpl;
import com.getddo.db.drawing.repository.DrawSnapshotSourceReader;
import com.getddo.db.event.repository.EventRepositoryImpl;
import com.getddo.db.ticket.MutableClock;
import com.getddo.db.ticket.TicketIntegrationTestApplication;

/** 기존 도메인 테스트의 컴포넌트 스캔에 유입되지 않는 실제 응모·추첨 통합 테스트 설정이다. */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = {"com.getddo.core.ticket", "com.getddo.db.ticket",
		"com.getddo.core.entry", "com.getddo.db.entry", "com.getddo.db.event.mapper"},
		excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
				classes = TicketIntegrationTestApplication.class))
@EntityScan(basePackages = {"com.getddo.db.ticket", "com.getddo.db.entry", "com.getddo.db.drawing.entity", "com.getddo.db.event.entity"})
@EnableJpaRepositories(basePackages = {"com.getddo.db.ticket", "com.getddo.db.entry", "com.getddo.db.drawing.repository", "com.getddo.db.event.repository"})
@Import({JpaAuditingConfig.class, MySqlTestConfiguration.class, DrawSnapshotService.class,
		DrawSnapshotRepositoryImpl.class, DrawSnapshotSourceReader.class, DrawSnapshotMapper.class,
		EventStatusChangeService.class, EventRepositoryImpl.class})
public class EntrySnapshotTestApplication {
	public static final Instant INITIAL_TIME = Instant.parse("2026-09-15T03:00:00Z");

	/** 서비스와 JPA 감사에 같은 서버 시계를 제공한다. */
	@Bean
	MutableClock clock() { return new MutableClock(INITIAL_TIME); }

	/** 응모 컴포넌트 스캔에 들어오는 관리자 조회 서비스의 이름 조회 의존성이다. 이 테스트는 사용자 이름을 쓰지 않는다. */
	@Bean
	UserNameRepository userNames() { return ids -> Map.of(); }

	/** 테스트 시계로 기존 서비스의 시간 공급자를 구성한다. */
	@Bean
	TimeProvider timeProvider(Clock clock) { return new TimeProvider(clock); }
}
