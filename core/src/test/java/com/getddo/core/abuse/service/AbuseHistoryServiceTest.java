package com.getddo.core.abuse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.getddo.core.abuse.domain.*;
import com.getddo.core.abuse.repository.AbuseHistoryJournalRepository;
import com.getddo.core.common.ratelimit.EntryBlock;

@ExtendWith(OutputCaptureExtension.class)
class AbuseHistoryServiceTest {
	private final AbuseHistoryJournalRepository journals = mock(AbuseHistoryJournalRepository.class);
	private final AbuseCaseSaveService saves = mock(AbuseCaseSaveService.class);
	private final Instant now = Instant.parse("2026-10-22T20:00:00Z");
	private final EntryBlock block = new EntryBlock(UUID.randomUUID(), UUID.randomUUID(), null, now,
			now, now.plusSeconds(600), new AbuseEvidence("entry-v1", AbuseTriggerScope.USER,
					now.minusSeconds(30), now.plusSeconds(30), 20, 20, 20));
	private final AbuseHistoryService service = new AbuseHistoryService(journals, saves,
			new AbuseHistoryRetryPolicy(Duration.ofMinutes(10), List.of(Duration.ofSeconds(10), Duration.ofMinutes(2))),
			Duration.ofSeconds(30), 100);
	private AbuseHistoryClaim claim;

	@BeforeEach
	void setUp() {
		claim = new AbuseHistoryClaim(block.getId(), UUID.randomUUID(), block, now, null, 0, AbuseHistoryQueue.NORMAL);
		when(journals.claimNext(eq(AbuseHistoryQueue.NORMAL), isNull(), any(), eq(100))).thenReturn(Optional.of(claim));
		when(journals.now()).thenReturn(now);
	}

	@Test
	@DisplayName("저장 호출이 반환된 뒤에만 Redis 완료를 표시한다")
	void acknowledgesOnlyAfterSaveReturns() {
		// when / then
		assertThat(service.processNext()).isTrue();
		InOrder order = inOrder(saves, journals);
		order.verify(journals).claimNext(eq(AbuseHistoryQueue.NORMAL), isNull(), any(), eq(100));
		order.verify(saves).save(block);
		order.verify(journals).acknowledge(claim);
		verify(journals, never()).reschedule(any(), any());
	}

	@Test
	@DisplayName("DB 저장 실패는 완료 표시 없이 해당 건만 재예약한다")
	void dbFailureDefersOnlyThisRecordWithoutAcknowledgement() {
		// given
		doThrow(new IllegalStateException("db down")).when(saves).save(block);
		// when / then
		assertThat(service.processNext()).isTrue();
		ArgumentCaptor<AbuseHistoryRetryState> state = ArgumentCaptor.forClass(AbuseHistoryRetryState.class);
		verify(journals).reschedule(eq(claim), state.capture());
		assertThat(state.getValue().getNextAttemptAt()).isEqualTo(now.plusSeconds(10));
		verify(journals, never()).acknowledge(any());
	}

	@Test
	@DisplayName("커밋 후 Redis 실패를 DB 실패로 집계하지 않는다")
	void redisFailureAfterCommitDoesNotCountAsDbFailure() {
		// given
		when(journals.acknowledge(claim)).thenThrow(new IllegalStateException("redis down"));
		// when / then
		assertThatThrownBy(service::processNext).isInstanceOf(IllegalStateException.class);
		verify(saves).save(block);
		verify(journals, never()).reschedule(any(), any());
	}

	@Test
	@DisplayName("예산이 소진되면 DB 호출 없이 배치로 넘긴다")
	void expiredRetryBudgetMovesToBatchWithoutCallingDb() {
		// given
		AbuseHistoryClaim old = new AbuseHistoryClaim(block.getId(), UUID.randomUUID(), block, now,
				now.minusSeconds(600), 8, AbuseHistoryQueue.NORMAL);
		when(journals.claimNext(any(), isNull(), any(), eq(100))).thenReturn(Optional.of(old));
		// when
		service.processNext();
		// then
		verifyNoInteractions(saves);
		ArgumentCaptor<AbuseHistoryRetryState> state = ArgumentCaptor.forClass(AbuseHistoryRetryState.class);
		verify(journals).reschedule(eq(old), state.capture());
		assertThat(state.getValue().getQueue()).isEqualTo(AbuseHistoryQueue.BATCH);
	}

	@Test
	@DisplayName("실패한 배치 기록이 다음 기록의 저장을 막지 않는다")
	void failedBatchRecordDoesNotPreventNextRecordFromBeingSaved() {
		// given
		Instant firstFailure = now.minusSeconds(3600);
		AbuseHistoryClaim failed = new AbuseHistoryClaim(block.getId(), UUID.randomUUID(), block, now,
				firstFailure, 8, AbuseHistoryQueue.BATCH);
		EntryBlock other = new EntryBlock(UUID.randomUUID(), UUID.randomUUID(), null, now, now,
				now.plusSeconds(600), block.getEvidence());
		AbuseHistoryClaim success = new AbuseHistoryClaim(other.getId(), UUID.randomUUID(), other, now,
				firstFailure, 8, AbuseHistoryQueue.BATCH);
		when(journals.claimNext(eq(AbuseHistoryQueue.BATCH), eq(now), any(), eq(100)))
				.thenReturn(Optional.of(failed), Optional.of(success), Optional.empty());
		doThrow(new IllegalStateException("db failure")).when(saves).save(block);
		// when / then
		assertThat(service.processNextBatch(now)).isTrue();
		assertThat(service.processNextBatch(now)).isTrue();
		assertThat(service.processNextBatch(now)).isFalse();
		verify(journals, never()).acknowledge(failed);
		verify(journals).acknowledge(success);
		verify(journals).reschedule(eq(failed), any());
	}

	@Test
	@DisplayName("만료된 차단도 최초 시각을 유지해 저장한다")
	void expiredBlockIsSavedWithOriginalTimesAndNoNewBlockIsCreated() {
		// given
		AbuseHistoryClaim expired = new AbuseHistoryClaim(block.getId(), UUID.randomUUID(), block,
				now.plusSeconds(3600), null, 0, AbuseHistoryQueue.NORMAL);
		when(journals.claimNext(eq(AbuseHistoryQueue.NORMAL), isNull(), any(), eq(100)))
				.thenReturn(Optional.of(expired));
		// when
		service.processNext();
		// then
		verify(saves).save(block);
		verify(journals).acknowledge(expired);
	}

	@Test
	@DisplayName("손상된 원본은 저장하거나 완료 표시하지 않는다")
	void unreadableOriginalIsNeitherSavedNorAcknowledged() {
		// given
		AbuseHistoryClaim broken = new AbuseHistoryClaim(block.getId(), UUID.randomUUID(), null, now, null, 0, AbuseHistoryQueue.NORMAL);
		when(journals.claimNext(any(), isNull(), any(), eq(100))).thenReturn(Optional.of(broken));
		// when / then
		assertThat(service.processNext()).isTrue();
		verifyNoInteractions(saves);
		verify(journals, never()).acknowledge(any());
		verify(journals, never()).reschedule(any(), any());
	}
	@Test
	@DisplayName("소유권을 잃은 재예약은 강제 갱신 없이 경고한다")
	void failedRescheduleIsLoggedWithoutForcingUpdate(CapturedOutput output) {
		// given
		doThrow(new IllegalStateException("db down")).when(saves).save(block);
		when(journals.reschedule(eq(claim), any())).thenReturn(false);
		// when
		service.processNext();
		// then
		assertThat(output.getOut()).contains("reschedule skipped after ownership loss", block.getId().toString());
		verify(journals, times(1)).reschedule(eq(claim), any());
		verify(journals, never()).acknowledge(any());
	}

	@Test
	@DisplayName("배치 이동의 소유권 상실도 경고한다")
	void expiredBudgetRescheduleFailureIsAlsoReported(CapturedOutput output) {
		// given
		AbuseHistoryClaim expired = new AbuseHistoryClaim(block.getId(), UUID.randomUUID(), block, now,
				now.minusSeconds(600), 8, AbuseHistoryQueue.NORMAL);
		when(journals.claimNext(eq(AbuseHistoryQueue.NORMAL), isNull(), any(), eq(100))).thenReturn(Optional.of(expired));
		when(journals.reschedule(eq(expired), any())).thenReturn(false);
		// when
		service.processNext();
		// then
		assertThat(output.getOut()).contains("reschedule skipped after ownership loss", "queue=BATCH");
		verifyNoInteractions(saves);
	}

	@Test
	@DisplayName("필수 의존성이 없으면 생성 시점에 거절한다")
	void missingRequiredDependencyFailsAtConstruction() {
		// given
		AbuseHistoryRetryPolicy policy = new AbuseHistoryRetryPolicy(Duration.ofMinutes(10), List.of(Duration.ofSeconds(10)));
		// when / then
		assertThatThrownBy(() -> new AbuseHistoryService(null, saves, policy, Duration.ofSeconds(30), 100))
				.isInstanceOf(NullPointerException.class).hasMessage("journals");
		assertThatThrownBy(() -> new AbuseHistoryService(journals, null, policy, Duration.ofSeconds(30), 100))
				.isInstanceOf(NullPointerException.class).hasMessage("saves");
		assertThatThrownBy(() -> new AbuseHistoryService(journals, saves, null, Duration.ofSeconds(30), 100))
				.isInstanceOf(NullPointerException.class).hasMessage("retries");
	}

}
