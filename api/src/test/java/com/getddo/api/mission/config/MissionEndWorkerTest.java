package com.getddo.api.mission.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.scheduling.annotation.Scheduled;

import com.getddo.core.mission.service.MissionEndService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class MissionEndWorkerTest {

	@Mock
	private MissionEndService service;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(MissionEndWorker.class)
			.withBean(MissionEndService.class, () -> mock(MissionEndService.class));

	@Test
	@DisplayName("워커는 기본으로 등록되고 enabled=false이면 등록되지 않는다")
	void registersWorkerOnlyWhenEnabled() {
		contextRunner.run(context -> assertThat(context).hasSingleBean(MissionEndWorker.class));
		contextRunner.withPropertyValues("getddo.mission.end-worker.enabled=true")
				.run(context -> assertThat(context).hasSingleBean(MissionEndWorker.class));
		contextRunner.withPropertyValues("getddo.mission.end-worker.enabled=false")
				.run(context -> assertThat(context).doesNotHaveBean(MissionEndWorker.class));
	}

	@Test
	@DisplayName("폴링 간격은 getddo.mission.end-worker.poll-delay 속성(기본 60000ms)을 따른다")
	void pollDelayFollowsProperty() throws NoSuchMethodException {
		// given
		Scheduled scheduled = MissionEndWorker.class.getMethod("poll").getAnnotation(Scheduled.class);
		// when
		// then
		assertThat(scheduled.fixedDelayString()).isEqualTo("${getddo.mission.end-worker.poll-delay:60000}");
		StandardEnvironment environment = new StandardEnvironment();
		TestPropertyValues.of("getddo.mission.end-worker.poll-delay=15000").applyTo(environment);
		assertThat(environment.resolvePlaceholders(scheduled.fixedDelayString())).isEqualTo("15000");
		assertThat(new StandardEnvironment().resolvePlaceholders(scheduled.fixedDelayString())).isEqualTo("60000");
	}

	@Test
	@DisplayName("폴링마다 종료 처리를 한 번 요청한다")
	void pollRequestsEndOnce() {
		// given
		MissionEndWorker worker = new MissionEndWorker(service);
		when(service.endDue()).thenReturn(0);

		// when
		worker.poll();

		// then
		verify(service, times(1)).endDue();
	}

	@Test
	@DisplayName("종료 처리가 실패해도 예외를 밖으로 던지지 않고 원문 메시지 없이 클래스만 기록해 다음 폴링을 막지 않는다")
	void pollSwallowsFailureWithoutMessage(CapturedOutput output) {
		// given
		MissionEndWorker worker = new MissionEndWorker(service);
		when(service.endDue()).thenThrow(new IllegalStateException("비밀 내부 메시지"));

		// when
		// then
		assertThatCode(worker::poll).doesNotThrowAnyException();
		assertThat(output.getOut()).contains("Mission end failed").contains("java.lang.IllegalStateException")
				.doesNotContain("비밀 내부 메시지");
	}
}
