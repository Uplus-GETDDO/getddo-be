package com.getddo.api.abuse.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.getddo.core.abuse.domain.AbuseHistoryRetryPolicy;
import com.getddo.core.abuse.repository.AbuseHistoryJournalRepository;
import com.getddo.core.abuse.service.AbuseCaseSaveService;
import com.getddo.core.abuse.service.AbuseHistoryService;

/** Redis 모드에서만 소비자를 연결한다. 메모리 제한 모드에는 저장 저널이 없다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "getddo.rate-limit.store", havingValue = "redis")
public class AbuseHistoryWorkerConfig {
	@Configuration(proxyBeanMethods = false)
	@EnableScheduling
	@EnableConfigurationProperties(AbuseHistoryWorkerProperties.class)
	@ConditionalOnProperty(name = "getddo.abuse.history-worker.enabled", havingValue = "true", matchIfMissing = true)
	static class EnabledWorker {
		@Bean
		AbuseHistoryService abuseHistoryService(AbuseHistoryJournalRepository journals, AbuseCaseSaveService saves,
				AbuseHistoryWorkerProperties properties) {
			return new AbuseHistoryService(journals, saves,
					new AbuseHistoryRetryPolicy(properties.retryBudget(), properties.retryDelays()),
					properties.lease(), properties.scanSize());
		}

		@Bean
		AbuseHistoryWorker abuseHistoryWorker(AbuseHistoryService service, AbuseHistoryWorkerProperties properties) {
			return new AbuseHistoryWorker(service, properties.batchSize());
		}
	}
}
