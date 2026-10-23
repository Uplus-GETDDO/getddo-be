package com.getddo.api.abuse.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
	void memoryModeDoesNotStartWorker() {
		context.withPropertyValues("getddo.rate-limit.store=memory").run(result ->
				assertThat(result).doesNotHaveBean(AbuseHistoryWorker.class));
	}

	@Test
	void disabledRedisWorkerDoesNotStartConsumer() {
		context.withPropertyValues("getddo.rate-limit.store=redis", "getddo.abuse.history-worker.enabled=false")
				.run(result -> assertThat(result).doesNotHaveBean(AbuseHistoryService.class));
	}

	@Test
	void enabledRedisModeBindsRetrySettingsWithoutReplacingGlobalScheduler() {
		context.withPropertyValues("getddo.rate-limit.store=redis").run(result -> {
			assertThat(result).hasSingleBean(AbuseHistoryService.class);
			assertThat(result).doesNotHaveBean(TaskScheduler.class);
			assertThat(result.getBean(AbuseHistoryWorkerProperties.class).retryDelays()).hasSize(4);
		});
	}
}
