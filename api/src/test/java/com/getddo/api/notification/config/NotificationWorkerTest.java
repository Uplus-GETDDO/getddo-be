package com.getddo.api.notification.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.getddo.core.notification.service.EventStartNotificationService;
import com.getddo.core.notification.service.NotificationJobService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class NotificationWorkerTest {
	@Mock private NotificationJobService jobs;
	@Mock private EventStartNotificationService starts;
	private NotificationWorker worker;

	@BeforeEach
	void setUp() {
		worker = new NotificationWorker(jobs, starts);
	}

	@Test
	@DisplayName("대상이 없으면 각 단계를 한 번 조회하고 등록·생성·발송 순서로 종료한다")
	void stopsEachStageWhenEmpty() {
		worker.poll();
		InOrder order = inOrder(starts, jobs);
		order.verify(starts).registerNextDueEvent();
		order.verify(jobs).processNextJob();
		order.verify(jobs).processNextDeliveries();
		order.verifyNoMoreInteractions();
	}

	@Test
	@DisplayName("등록·생성은 최대 20번, 발송은 최대 20건 묶음을 한 번 호출한다")
	void boundsEachStage() {
		when(starts.registerNextDueEvent()).thenReturn(true);
		when(jobs.processNextJob()).thenReturn(true);
		worker.poll();
		verify(starts, times(20)).registerNextDueEvent();
		verify(jobs, times(20)).processNextJob();
		verify(jobs).processNextDeliveries();
	}

	@Test
	@DisplayName("시작 작업 등록 실패에도 기존 작업을 처리하고 예외 원문은 로그에 남기지 않는다")
	void continuesAfterRegistrationFailure(CapturedOutput output) {
		when(starts.registerNextDueEvent()).thenThrow(new IllegalStateException("private event details"));
		worker.poll();
		verify(jobs).processNextJob();
		verify(jobs).processNextDeliveries();
		assertThat(output.getOut()).contains(IllegalStateException.class.getName())
				.doesNotContain("private event details");
	}
}
