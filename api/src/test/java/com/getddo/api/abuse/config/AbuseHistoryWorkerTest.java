package com.getddo.api.abuse.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.getddo.core.abuse.service.AbuseHistoryService;

class AbuseHistoryWorkerTest {
	@Test
	void pollingStopsAtConfiguredLimit() {
		AbuseHistoryService service = mock(AbuseHistoryService.class);
		when(service.processNext()).thenReturn(true);
		new AbuseHistoryWorker(service, 20, Runnable::run).poll();
		verify(service, times(20)).processNext();
	}

	@Test
	void batchDrainsDueRecordsUsingOneCutoff() {
		AbuseHistoryService service = mock(AbuseHistoryService.class);
		Instant cutoff = Instant.parse("2026-10-22T21:00:00Z");
		when(service.batchCutoff()).thenReturn(cutoff);
		when(service.processNextBatch(cutoff)).thenReturn(true, true, false);
		new AbuseHistoryWorker(service, 20, Runnable::run).batch();
		verify(service, times(3)).processNextBatch(cutoff);
		verify(service).batchCutoff();
	}
}
