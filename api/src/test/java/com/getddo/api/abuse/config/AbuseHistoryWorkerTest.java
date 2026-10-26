package com.getddo.api.abuse.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.getddo.core.abuse.service.AbuseHistoryService;

@ExtendWith(OutputCaptureExtension.class)
class AbuseHistoryWorkerTest {
	@Test
	@DisplayName("일반 폴링은 설정한 최대 처리량에서 멈춘다")
	void pollingStopsAtConfiguredLimit() {
		// given
		AbuseHistoryService service = mock(AbuseHistoryService.class);
		when(service.processNext()).thenReturn(true);
		// when
		new AbuseHistoryWorker(service, 20, Runnable::run).poll();
		// then
		verify(service, times(20)).processNext();
	}

	@Test
	@DisplayName("배치는 하나의 기준 시각으로 처리 대상을 소진한다")
	void batchDrainsDueRecordsUsingOneCutoff() {
		// given
		AbuseHistoryService service = mock(AbuseHistoryService.class);
		Instant cutoff = Instant.parse("2026-10-22T21:00:00Z");
		when(service.batchCutoff()).thenReturn(cutoff);
		when(service.processNextBatch(cutoff)).thenReturn(true, true, false);
		// when
		new AbuseHistoryWorker(service, 20, Runnable::run).batch();
		// then
		verify(service, times(3)).processNextBatch(cutoff);
		verify(service).batchCutoff();
	}
	@Test
	@DisplayName("중복 배치 시작을 경고하고 종료 후 다음 시작을 허용한다")
	void runningBatchIsReportedAndNextTriggerCanRunAfterCompletion(CapturedOutput output) {
		// given
		AbuseHistoryService service = mock(AbuseHistoryService.class);
		List<Runnable> dispatched = new ArrayList<>();
		AbuseHistoryWorker worker = new AbuseHistoryWorker(service, 20, dispatched::add);
		// when
		worker.batch();
		worker.batch();
		// then
		assertThat(dispatched).hasSize(1);
		assertThat(output.getOut()).contains("batch skipped: previous batch is still running");
		dispatched.getFirst().run();
		worker.batch();
		assertThat(dispatched).hasSize(2);
	}

}
