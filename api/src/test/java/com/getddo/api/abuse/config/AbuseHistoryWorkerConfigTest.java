package com.getddo.api.abuse.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;

import com.getddo.core.abuse.repository.AbuseHistoryJournalRepository;
import com.getddo.core.abuse.service.AbuseCaseSaveService;
import com.getddo.core.abuse.service.AbuseHistoryService;

class AbuseHistoryWorkerConfigTest {
	private final ApplicationContextRunner context = new ApplicationContextRunner()
			.withUserConfiguration(AbuseHistoryWorkerConfig.class)
			.withBean(AbuseHistoryJournalRepository.class, () -> mock(AbuseHistoryJournalRepository.class))
			.withBean(AbuseCaseSaveService.class, () -> mock(AbuseCaseSaveService.class));

	@Test
	@DisplayName("메모리 모드에서는 이력 작업자를 시작하지 않는다")
	void memoryModeDoesNotStartWorker() {
		// given
		context.withPropertyValues("getddo.rate-limit.store=memory").run(result ->
				// when / then
				assertThat(result).doesNotHaveBean(AbuseHistoryWorker.class));
	}

	@Test
	@DisplayName("Redis 작업자를 비활성화하면 소비자를 시작하지 않는다")
	void disabledRedisWorkerDoesNotStartConsumer() {
		// when / then
		context.withPropertyValues("getddo.rate-limit.store=redis", "getddo.abuse.history-worker.enabled=false")
				.run(result -> assertThat(result).doesNotHaveBean(AbuseHistoryService.class));
	}

	@Test
	@DisplayName("Redis 재시도 설정을 연결하고 공용 스케줄러를 대체하지 않는다")
	void enabledRedisModeBindsRetrySettingsWithoutReplacingGlobalScheduler() {
		// given
		context.withPropertyValues("getddo.rate-limit.store=redis").run(result -> {
			// when / then
			assertThat(result).hasSingleBean(AbuseHistoryService.class);
			assertThat(result).doesNotHaveBean(TaskScheduler.class);
			assertThat(result.getBean(AbuseHistoryWorkerProperties.class).retryDelays()).hasSize(4);
		});
	}
}
