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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.getddo.core.abuse.domain.*;
import com.getddo.core.abuse.repository.AbuseHistoryJournalRepository;
import com.getddo.core.common.ratelimit.EntryBlock;

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
	void acknowledgesOnlyAfterSaveReturns() {
		assertThat(service.processNext()).isTrue();
		var order = inOrder(saves, journals);
		order.verify(journals).claimNext(eq(AbuseHistoryQueue.NORMAL), isNull(), any(), eq(100));
		order.verify(saves).save(block);
		order.verify(journals).acknowledge(claim);
		verify(journals, never()).reschedule(any(), any());
	}

	@Test
	void dbFailureDefersOnlyThisRecordWithoutAcknowledgement() {
		doThrow(new IllegalStateException("db down")).when(saves).save(block);
		assertThat(service.processNext()).isTrue();
		ArgumentCaptor<AbuseHistoryRetryState> state = ArgumentCaptor.forClass(AbuseHistoryRetryState.class);
		verify(journals).reschedule(eq(claim), state.capture());
		assertThat(state.getValue().getNextAttemptAt()).isEqualTo(now.plusSeconds(10));
		verify(journals, never()).acknowledge(any());
	}

	@Test
	void redisFailureAfterCommitDoesNotCountAsDbFailure() {
		when(journals.acknowledge(claim)).thenThrow(new IllegalStateException("redis down"));
		assertThatThrownBy(service::processNext).isInstanceOf(IllegalStateException.class);
		verify(saves).save(block);
		verify(journals, never()).reschedule(any(), any());
	}

	@Test
	void expiredRetryBudgetMovesToBatchWithoutCallingDb() {
		AbuseHistoryClaim old = new AbuseHistoryClaim(block.getId(), UUID.randomUUID(), block, now,
				now.minusSeconds(600), 8, AbuseHistoryQueue.NORMAL);
		when(journals.claimNext(any(), isNull(), any(), eq(100))).thenReturn(Optional.of(old));
		service.processNext();
		verifyNoInteractions(saves);
		ArgumentCaptor<AbuseHistoryRetryState> state = ArgumentCaptor.forClass(AbuseHistoryRetryState.class);
		verify(journals).reschedule(eq(old), state.capture());
		assertThat(state.getValue().getQueue()).isEqualTo(AbuseHistoryQueue.BATCH);
	}

	@Test
	void failedBatchRecordDoesNotPreventNextRecordFromBeingSaved() {
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
		assertThat(service.processNextBatch(now)).isTrue();
		assertThat(service.processNextBatch(now)).isTrue();
		assertThat(service.processNextBatch(now)).isFalse();
		verify(journals, never()).acknowledge(failed);
		verify(journals).acknowledge(success);
		verify(journals).reschedule(eq(failed), any());
	}

	@Test
	void expiredBlockIsSavedWithOriginalTimesAndNoNewBlockIsCreated() {
		AbuseHistoryClaim expired = new AbuseHistoryClaim(block.getId(), UUID.randomUUID(), block,
				now.plusSeconds(3600), null, 0, AbuseHistoryQueue.NORMAL);
		when(journals.claimNext(eq(AbuseHistoryQueue.NORMAL), isNull(), any(), eq(100)))
				.thenReturn(Optional.of(expired));
		service.processNext();
		verify(saves).save(block);
		verify(journals).acknowledge(expired);
	}

	@Test
	void unreadableOriginalIsNeitherSavedNorAcknowledged() {
		AbuseHistoryClaim broken = new AbuseHistoryClaim(block.getId(), UUID.randomUUID(), null, now, null, 0, AbuseHistoryQueue.NORMAL);
		when(journals.claimNext(any(), isNull(), any(), eq(100))).thenReturn(Optional.of(broken));
		assertThat(service.processNext()).isTrue();
		verifyNoInteractions(saves);
		verify(journals, never()).acknowledge(any());
		verify(journals, never()).reschedule(any(), any());
	}
}
