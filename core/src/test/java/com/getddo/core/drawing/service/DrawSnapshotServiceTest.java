package com.getddo.core.drawing.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.domain.DrawSnapshot;
import com.getddo.core.drawing.domain.DrawSnapshotSource.Entry;
import com.getddo.core.drawing.domain.DrawSnapshotSource.Event;
import com.getddo.core.drawing.domain.DrawSnapshotSource.Participant;
import com.getddo.core.drawing.domain.DrawSnapshotSource.Prize;
import com.getddo.core.drawing.domain.DrawSnapshotSource.Use;
import com.getddo.core.drawing.repository.DrawSnapshotRepository;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.service.EventStatusChangeService;
import com.getddo.core.event.domain.EventType;
import com.getddo.core.ticket.domain.TicketGrade;

import static com.getddo.core.drawing.exception.DrawingErrorCode.INVALID_EVIDENCE;
import static com.getddo.core.drawing.exception.DrawingErrorCode.NOT_EXECUTABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DrawSnapshotServiceTest {
	private static final Instant NOW = Instant.parse("2026-10-09T03:00:00Z");
	private final UUID eventId = UUID.randomUUID(), participantId = UUID.randomUUID(), userId = UUID.randomUUID();
	private DrawSnapshotRepository repository;
	private DrawSnapshotService service;
	private EventStatusChangeService eventStatuses;

	/** 서버 시각과 유효한 이벤트·응모 테스트 환경을 각 검증 전에 초기화한다. */
	@BeforeEach
	void setUp() {
		repository = mock(DrawSnapshotRepository.class);
		eventStatuses = mock(EventStatusChangeService.class);
		service = new DrawSnapshotService(repository, new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)), eventStatuses);
		when(repository.lockEvent(eventId)).thenReturn(Optional.of(event(EventType.TICKET, true, NOW.minusSeconds(300))));
		when(repository.findInitial(eventId)).thenReturn(Optional.empty());
		when(repository.saveInitial(any())).thenAnswer(call -> call.getArgument(0));
	}

	@Test
	@DisplayName("폴링 조회는 현재 시각에서 5분을 뺀 경계와 최대 20건을 사용한다")
	void queriesBoundedClosingBoundary() {
		// given
		when(repository.findInitialDue(NOW.minusSeconds(300), 20)).thenReturn(List.of(eventId));
		// when / then
		assertThat(service.findInitialDue()).containsExactly(eventId);
	}

	/** 추가 응모의 등급별 실제 차감량을 합산하고 원본 근거와 가중치를 보존하는지 검증한다. */
	@Test
	void sumsGradesAcrossAdditionalEntriesAndPreservesEvidence() {
		Entry first = entry(2, TicketGrade.GOLD, TicketGrade.SILVER);
		Entry additional = entry(1, TicketGrade.BRONZE);
		when(repository.findParticipants(eventId)).thenReturn(List.of(participant(3, first, additional)));
		DrawSnapshot snapshot = service.prepareInitial(eventId);
		assertThat(snapshot.status()).isEqualTo(DrawRunStatus.READY);
		DrawSnapshot.Candidate candidate = snapshot.candidates().getFirst();
		assertThat(candidate.ticketCount()).isEqualTo(3);
		assertThat(candidate.weight()).isEqualTo(9);
		assertThat(candidate.entryEvidence().entries()).containsExactly(first, additional);
		assertThat(candidate.entryEvidence().goldCount()).isEqualTo(1);
		assertThat(candidate.entryEvidence().silverCount()).isEqualTo(1);
		assertThat(candidate.entryEvidence().bronzeCount()).isEqualTo(1);
	}

	/** 누적 수량이 같아도 개별 응모의 차감 근거가 불일치하면 저장을 거절하는지 검증한다. */
	@Test
	void detectsPerEntryMismatchEvenWhenTotalsMatch() {
		when(repository.findParticipants(eventId)).thenReturn(List.of(participant(4,
				entry(1, TicketGrade.GOLD, TicketGrade.GOLD), entry(3, TicketGrade.BRONZE, TicketGrade.BRONZE))));
		assertError(INVALID_EVIDENCE);
		verify(repository, never()).saveInitial(any());
	}

	/** 서로 다른 추가 응모에서 같은 티켓을 차감한 경우 입력 확정을 거절하는지 검증한다. */
	@Test
	@DisplayName("추가 응모에 같은 티켓이 중복되면 입력 확정을 거절한다")
	void rejectsSameTicketAcrossAdditionalEntries() {
		// given
		UUID ticketId = UUID.randomUUID();
		Entry first = new Entry(UUID.randomUUID(), 1, NOW.minusSeconds(301),
				List.of(new Use(UUID.randomUUID(), ticketId, userId, TicketGrade.GOLD)));
		Entry additional = new Entry(UUID.randomUUID(), 1, NOW.minusSeconds(301),
				List.of(new Use(UUID.randomUUID(), ticketId, userId, TicketGrade.GOLD)));
		when(repository.findParticipants(eventId)).thenReturn(List.of(participant(2, first, additional)));

		// when / then
		assertError(INVALID_EVIDENCE);
		verify(repository, never()).saveInitial(any());
	}

	/** 누적 수량 불일치와 티켓 사용 이벤트의 0장 응모를 거절하는지 검증한다. */
	@Test
	void rejectsAccumulatedMismatchAndZeroTicketUse() {
		when(repository.findParticipants(eventId)).thenReturn(List.of(participant(2, entry(1, TicketGrade.GOLD))));
		assertError(INVALID_EVIDENCE);
		when(repository.findParticipants(eventId)).thenReturn(List.of(participant(0, entry(0))));
		assertError(INVALID_EVIDENCE);
	}

	/** 응모권 미사용·가중치 미적용 이벤트는 후보 가중치를 1로 고정하는지 검증한다. */
	@Test
	void noTicketAndUnweightedUseHaveWeightOne() {
		when(repository.lockEvent(eventId)).thenReturn(Optional.of(event(EventType.NO_TICKET, false, NOW.minusSeconds(300))));
		when(repository.findParticipants(eventId)).thenReturn(List.of(participant(0, entry(0))));
		assertThat(service.prepareInitial(eventId).candidates().getFirst().weight()).isEqualTo(1);
		when(repository.lockEvent(eventId)).thenReturn(Optional.of(event(EventType.TICKET, false, NOW.minusSeconds(300))));
		when(repository.findParticipants(eventId)).thenReturn(List.of(participant(1, entry(1, TicketGrade.GOLD))));
		assertThat(service.prepareInitial(eventId).candidates().getFirst().weight()).isEqualTo(1);
	}

	/** 응모자가 없는 신규 실행만 NO_ENTRIES로 종료하는지 검증한다. */
	@ParameterizedTest
	@EnumSource(value = EventStatus.class, names = {"SCHEDULED", "OPEN", "CLOSED"})
	@DisplayName("최초 추첨 허용 상태에서 응모자가 없으면 NO_ENTRIES로 종료한다")
	void terminatesWithoutCandidatesOnlyWhenThereAreNoEntries(EventStatus initialStatus) {
		// given
		Event source = event(EventType.NO_TICKET, false, NOW.minusSeconds(300));
		when(repository.lockEvent(eventId)).thenReturn(Optional.of(new Event(eventId, source.type(),
				source.weightingEnabled(), source.endsAt(), initialStatus, false, source.membershipRule(), source.prizes())));
		when(repository.findParticipants(eventId)).thenReturn(List.of());

		// when
		DrawSnapshot snapshot = service.prepareInitial(eventId);

		// then
		assertThat(snapshot.status()).isEqualTo(DrawRunStatus.NO_ENTRIES);
		assertThat(snapshot.candidates()).isEmpty();
		verify(eventStatuses).changeStatus(eventId, initialStatus, EventStatus.NO_ENTRANTS);
	}

	/** 제외 제공자 없이 모든 정상 응모를 후보로 저장하고 등급별 가중치를 유지하는지 검증한다. */
	@Test
	@DisplayName("모든 정상 응모를 후보로 확정하고 등급별 가중치를 보존한다")
	void includesEveryValidParticipantWithoutAnExclusionProvider() {
		// given
		Participant first = participant(1, entry(1, TicketGrade.GOLD));
		UUID otherUser = UUID.randomUUID();
		Entry otherEntry = new Entry(UUID.randomUUID(), 1, NOW.minusSeconds(301),
				List.of(new Use(UUID.randomUUID(), UUID.randomUUID(), otherUser, TicketGrade.SILVER)));
		Participant second = new Participant(UUID.randomUUID(), otherUser, 1, "VIP", "USER", List.of(otherEntry));
		when(repository.findParticipants(eventId)).thenReturn(List.of(first, second));

		// when
		DrawSnapshot snapshot = service.prepareInitial(eventId);

		// then
		assertThat(snapshot.status()).isEqualTo(DrawRunStatus.READY);
		assertThat(snapshot.candidates()).extracting(DrawSnapshot.Candidate::participantId)
				.containsExactly(first.id(), second.id());
		assertThat(snapshot.candidates()).extracting(DrawSnapshot.Candidate::weight).containsExactly(5L, 3L);
		assertThat(snapshot.candidates()).allSatisfy(candidate -> {
			assertThat(candidate.ticketCount()).isEqualTo(1);
			assertThat(candidate.eligibilityEvidence().excluded()).isFalse();
		});
	}

	/** 확정된 실행과 레거시 종료 상태는 원본 재조회·재저장 없이 반환하는지 검증한다. */
	@ParameterizedTest
	@EnumSource(value = DrawRunStatus.class, names = {"READY", "RUNNING", "CONFIRMED", "NO_ENTRIES", "NO_CANDIDATES"})
	void replaysFixedRunWithoutReadingOriginals(DrawRunStatus status) {
		DrawSnapshot saved = new DrawSnapshot(UUID.randomUUID(), eventId, status, NOW, "v1", null, List.of());
		when(repository.findInitial(eventId)).thenReturn(Optional.of(saved));
		assertThat(service.prepareInitial(eventId)).isSameAs(saved);
		verify(repository, never()).findParticipants(any());
		verify(repository, never()).saveInitial(any());
	}

	/** 마감 후 5분 전·취소·삭제 이벤트의 최초 준비를 거절하는지 검증한다. */
	@Test
	void waitsUntilFiveMinuteBoundaryAndRejectsCanceledDeletedEvents() {
		Event early = event(EventType.TICKET, true, NOW.minusSeconds(299));
		when(repository.lockEvent(eventId)).thenReturn(Optional.of(early));
		assertError(NOT_EXECUTABLE);
		when(repository.lockEvent(eventId)).thenReturn(Optional.of(new Event(eventId, early.type(), true,
				NOW.minusSeconds(300), EventStatus.CANCELED, false, "vip", early.prizes())));
		assertError(NOT_EXECUTABLE);
		when(repository.lockEvent(eventId)).thenReturn(Optional.of(new Event(eventId, early.type(), true,
				NOW.minusSeconds(300), EventStatus.CLOSED, true, "vip", early.prizes())));
		assertError(NOT_EXECUTABLE);
	}

	/** 다른 소유자의 티켓과 마감 시각 이후에 접수된 응모를 거절하는지 검증한다. */
	@Test
	void rejectsWrongTicketOwnerAndEntryAfterDeadline() {
		Use use = new Use(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TicketGrade.GOLD);
		when(repository.findParticipants(eventId)).thenReturn(List.of(participant(1,
				new Entry(UUID.randomUUID(), 1, NOW.minusSeconds(301), List.of(use)))));
		assertError(INVALID_EVIDENCE);
		when(repository.findParticipants(eventId)).thenReturn(List.of(participant(1,
				new Entry(UUID.randomUUID(), 1, NOW, entry(1, TicketGrade.GOLD).uses()))));
		assertError(INVALID_EVIDENCE);
		when(repository.findParticipants(eventId)).thenReturn(List.of(participant(1,
				new Entry(UUID.randomUUID(), 1, NOW.minusSeconds(300), entry(1, TicketGrade.GOLD).uses()))));
		assertError(INVALID_EVIDENCE);
	}

	/** 실제 사용 장수 한 장과 플래티넘 가중치 10을 구분해 신규 저장 형식에 보존한다. */
	@Test
	@DisplayName("플래티넘 한 장을 가중치 10과 신규 규칙·근거 버전으로 저장한다")
	void storesPlatinumWeightAndVersionedEvidence() {
		// given
		when(repository.findParticipants(eventId)).thenReturn(List.of(participant(1, entry(1, TicketGrade.PLATINUM))));
		// when
		DrawSnapshot snapshot = service.prepareInitial(eventId);
		// then
		assertThat(snapshot.algorithmVersion()).isEqualTo("weighted-without-replacement-v1");
		assertThat(snapshot.rules().version()).isEqualTo(3);
		assertThat(snapshot.rules().platinumWeight()).isEqualTo(10);
		assertThat(snapshot.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.ticketCount()).isEqualTo(1);
			assertThat(candidate.weight()).isEqualTo(10);
			assertThat(candidate.entryEvidence().version()).isEqualTo(2);
			assertThat(candidate.entryEvidence().platinumCount()).isEqualTo(1);
		});
	}

	/** 추가 응모에 섞인 네 등급을 모두 합산하고 개수와 가중치를 따로 보존한다. */
	@Test
	@DisplayName("네 등급 혼합 추가 응모는 실제 4장과 가중치 19를 보존한다")
	void sumsAllFourGradesAcrossEntries() {
		// given
		Entry first = entry(2, TicketGrade.PLATINUM, TicketGrade.GOLD);
		Entry additional = entry(2, TicketGrade.SILVER, TicketGrade.BRONZE);
		when(repository.findParticipants(eventId)).thenReturn(List.of(participant(4, first, additional)));
		// when
		DrawSnapshot.Candidate candidate = service.prepareInitial(eventId).candidates().getFirst();
		// then
		assertThat(candidate.ticketCount()).isEqualTo(4);
		assertThat(candidate.weight()).isEqualTo(19);
		assertThat(candidate.entryEvidence().bronzeCount()).isEqualTo(1);
		assertThat(candidate.entryEvidence().silverCount()).isEqualTo(1);
		assertThat(candidate.entryEvidence().goldCount()).isEqualTo(1);
		assertThat(candidate.entryEvidence().platinumCount()).isEqualTo(1);
		assertThat(candidate.entryEvidence().entries()).containsExactly(first, additional);
	}

	/** 추가 응모를 허용하지 않는 이벤트에서 여러 응모를 거절하는지 검증한다. */
	@Test
	@DisplayName("단일 응모 이벤트에 추가 응모가 있으면 준비를 거절하고 저장하지 않는다")
	void rejectsAdditionalEntriesForSingleEntryEvents() {
		// given
		when(repository.lockEvent(eventId)).thenReturn(Optional.of(event(EventType.NO_TICKET, false, NOW.minusSeconds(300))));
		when(repository.findParticipants(eventId)).thenReturn(List.of(participant(0, entry(0), entry(0))));

		// when
		Throwable failure = catchThrowable(() -> service.prepareInitial(eventId));

		// then
		assertThat(failure).isInstanceOf(BusinessException.class)
				.extracting(error -> ((BusinessException) error).getErrorCode()).isEqualTo(INVALID_EVIDENCE);
		verify(repository, never()).saveInitial(any());
	}

	/** 지정한 유형·가중치·마감 시각과 유효한 경품 조건의 이벤트를 만든다. */
	private Event event(EventType type, boolean weighted, Instant endsAt) {
		return new Event(eventId, type, weighted, endsAt, EventStatus.CLOSED, false, "vip",
				List.of(new Prize(UUID.randomUUID(), 1, 2)));
	}
	/** 현재 테스트 사용자의 누적 사용량과 응모 근거를 구성한다. */
	private Participant participant(long count, Entry... entries) {
		return new Participant(participantId, userId, count, "VIP", "USER", List.of(entries));
	}
	/** 마감 전에 접수된 응모와 지정한 등급의 실제 USE 근거를 만든다. */
	private Entry entry(long count, TicketGrade... grades) {
		return new Entry(UUID.randomUUID(), count, NOW.minusSeconds(301), java.util.Arrays.stream(grades)
				.map(grade -> new Use(UUID.randomUUID(), UUID.randomUUID(), userId, grade)).toList());
	}
	/** 최초 준비 실패가 기대한 BusinessException 오류 코드를 반환하는지 검증한다. */
	private void assertError(com.getddo.core.drawing.exception.DrawingErrorCode code) {
		assertThatThrownBy(() -> service.prepareInitial(eventId)).isInstanceOf(BusinessException.class)
				.extracting(error -> ((BusinessException) error).getErrorCode()).isEqualTo(code);
	}
}
