package com.getddo.core.banner.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.getddo.core.audit.domain.AuditLogCommand;
import com.getddo.core.audit.service.AuditLogService;
import com.getddo.core.banner.domain.Banner;
import com.getddo.core.banner.domain.BannerSettings;
import com.getddo.core.banner.exception.BannerErrorCode;
import com.getddo.core.banner.repository.BannerRepository;
import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.repository.EventRepository;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

class BannerServiceTest {
	private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
	private static final UUID EVENT = UUID.randomUUID();
	private static final UUID A = UUID.randomUUID();
	private static final UUID B = UUID.randomUUID();
	private static final User ADMIN = actor(UserRole.ADMIN, UserStatus.ACTIVE);
	private final BannerRepository repository = mock(BannerRepository.class);
	private final EventRepository events = mock(EventRepository.class);
	private final AuditLogService audit = mock(AuditLogService.class);
	private final BannerImageConfiguration images = mock(BannerImageConfiguration.class);
	private final BannerService service = new BannerService(repository, events, audit,
			new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)), images);

	@Test
	@DisplayName("일반 사용자·비활성 관리자는 관리자 조회와 변경에 접근하지 못한다")
	void rejectsNonAdminBeforeRepositoryAccess() {
		// given / when / then
		for (User actor : List.of(actor(UserRole.USER, UserStatus.ACTIVE), actor(UserRole.ADMIN, UserStatus.INACTIVE))) {
			assertError(() -> service.listForAdmin(actor), BannerErrorCode.ADMIN_REQUIRED);
			assertError(() -> service.create(actor, settings(1)), BannerErrorCode.ADMIN_REQUIRED);
			assertError(() -> service.update(actor, A, settings(1)), BannerErrorCode.ADMIN_REQUIRED);
			assertError(() -> service.delete(actor, A), BannerErrorCode.ADMIN_REQUIRED);
			assertError(() -> service.reorder(actor, List.of()), BannerErrorCode.ADMIN_REQUIRED);
		}
		verifyNoInteractions(repository, events, audit);
	}

	@Test
	@DisplayName("활성 사용자 공개 목록은 저장소의 노출 대상만 사용한다")
	void delegatesVisibleList() {
		// given
		when(repository.findVisible()).thenReturn(List.of(banner(A, 2)));
		// when / then
		assertThat(service.list(actor(UserRole.USER, UserStatus.ACTIVE))).extracting(Banner::getId).containsExactly(A);
		assertError(() -> service.list(actor(UserRole.USER, UserStatus.INACTIVE)), BannerErrorCode.USER_INACTIVE);
		verify(repository, never()).findAll();
	}

	@Test
	@DisplayName("등록은 이벤트→목록 순서로 잠그고 삽입·감사 기록을 같은 호출에 연결한다")
	void createsAndShiftsWithAudit() {
		// given
		when(events.findStatusByIdForUpdate(EVENT)).thenReturn(java.util.Optional.of(EventStatus.OPEN));
		when(repository.findAll()).thenReturn(List.of(banner(A, 1)), List.of(banner(B, 1), banner(A, 2)));
		when(repository.append(any(), anyInt())).thenReturn(banner(B, 2));
		// when
		Banner result = service.create(ADMIN, settings(1));
		// then
		assertThat(result.getId()).isEqualTo(B);
		org.mockito.InOrder order = inOrder(events, repository);
		order.verify(events).findStatusByIdForUpdate(EVENT);
		order.verify(repository).lockCatalog();
		order.verify(repository).findAll();
		order.verify(repository).append(any(), eq(2));
		order.verify(repository).reorder(List.of(B, A), NOW);
		ArgumentCaptor<AuditLogCommand> saved = ArgumentCaptor.forClass(AuditLogCommand.class);
		verify(audit).record(saved.capture());
		assertThat(saved.getValue().getActorId()).isEqualTo(ADMIN.getId());
		assertThat(saved.getValue().getAction()).isEqualTo("BANNER_CREATE");
		assertThat(saved.getValue().getTargetId()).isEqualTo(B);
		assertThat(saved.getValue().getBeforeData()).containsKey("banners");
	}

	@Test
	@DisplayName("없는 이벤트와 취소 이벤트 연결은 목록 변경 전에 거절한다")
	void rejectsMissingAndCanceledEvent() {
		// given / when / then
		when(events.findStatusByIdForUpdate(EVENT)).thenReturn(java.util.Optional.empty());
		assertError(() -> service.create(ADMIN, settings(1)), BannerErrorCode.EVENT_NOT_FOUND);
		when(events.findStatusByIdForUpdate(EVENT)).thenReturn(java.util.Optional.of(EventStatus.CANCELED));
		assertError(() -> service.update(ADMIN, A, settings(1)), BannerErrorCode.EVENT_CANCELED);
		verifyNoInteractions(repository, audit);
	}

	@Test
	@DisplayName("숨김을 포함한 전체 다섯 개면 여섯 번째 저장을 호출하지 않는다")
	void enforcesHiddenInclusiveLimit() {
		// given
		when(events.findStatusByIdForUpdate(EVENT)).thenReturn(java.util.Optional.of(EventStatus.OPEN));
		when(repository.findAll()).thenReturn(List.of(banner(A, 1), banner(B, 2), banner(UUID.randomUUID(), 3),
				banner(UUID.randomUUID(), 4), banner(UUID.randomUUID(), 5)));
		// when / then
		assertError(() -> service.create(ADMIN, settings(1)), BannerErrorCode.LIMIT_EXCEEDED);
		verify(repository, never()).append(any(), anyInt());
		verifyNoInteractions(audit);
	}

	@Test
	@DisplayName("등록 가능 개수 밖의 위치는 저장 전에 거절한다")
	void rejectsGapBeforeAppend() {
		// given
		when(events.findStatusByIdForUpdate(EVENT)).thenReturn(java.util.Optional.of(EventStatus.OPEN));
		when(repository.findAll()).thenReturn(List.of());
		// when / then
		assertError(() -> service.create(ADMIN, settings(2)), BannerErrorCode.INVALID_SETTINGS);
		verify(repository, never()).append(any(), anyInt());
	}

	@Test
	@DisplayName("수정은 사이 배너를 이동하고 등록 설정과 변경 이력을 남긴다")
	void updatesAndMoves() {
		// given
		when(events.findStatusByIdForUpdate(EVENT)).thenReturn(java.util.Optional.of(EventStatus.CLOSED));
		when(repository.findAll()).thenReturn(List.of(banner(A, 1), banner(B, 2)), List.of(banner(B, 1), banner(A, 2)));
		BannerSettings settings = settings(2);
		// when / then
		assertThat(service.update(ADMIN, A, settings).getDisplayOrder()).isEqualTo(2);
		verify(repository).update(A, settings);
		verify(repository).reorder(List.of(B, A), NOW);
		verify(audit).record(any());
	}

	@Test
	@DisplayName("삭제는 빈자리를 당기고 이벤트 저장소를 변경하지 않는다")
	void deletesAndClosesGap() {
		// given
		when(repository.findAll()).thenReturn(List.of(banner(A, 1), banner(B, 2)), List.of(banner(B, 1)));
		// when
		service.delete(ADMIN, A);
		// then
		verify(repository).delete(A);
		verify(repository).reorder(List.of(B), NOW);
		verify(audit).record(any());
		verifyNoInteractions(events);
	}

	@Test
	@DisplayName("동시 변경된 목록의 ID 집합은 저장·감사 호출 없이 409로 거절한다")
	void rejectsStaleReorder() {
		// given
		when(repository.findAll()).thenReturn(List.of(banner(A, 1), banner(B, 2)));
		// when / then
		assertError(() -> service.reorder(ADMIN, List.of(A)), BannerErrorCode.LIST_CHANGED);
		verify(repository, never()).reorder(any(), any());
		verifyNoInteractions(audit);
	}

	@Test
	@DisplayName("빈 목록은 불필요한 감사 행을 만들지 않는다")
	void emptyAndUnchangedReorder() {
		// given
		when(repository.findAll()).thenReturn(List.of());
		// when / then
		assertThat(service.reorder(ADMIN, List.of())).isEmpty();
		verifyNoInteractions(audit);
	}

	@Test
	@DisplayName("이전 배너의 ID 순서가 같아도4번을1번으로 정리하면 감사 기록을 남긴다")
	void recordsCompactionWithUnchangedIds() {
		// given: 이전 URL 행은 이벤트 미연결이며 순서에 빈자리가 있을 수 있다.
		Banner legacy = new Banner(A, null, "banner/key.png", 4, NOW, NOW);
		Banner compacted = new Banner(A, null, "banner/key.png", 1, NOW, NOW);
		when(repository.findAll()).thenReturn(List.of(legacy), List.of(compacted));
		// when
		assertThat(service.reorder(ADMIN, List.of(A))).extracting(Banner::getDisplayOrder).containsExactly(1);
		// then
		ArgumentCaptor<AuditLogCommand> saved = ArgumentCaptor.forClass(AuditLogCommand.class);
		verify(audit).record(saved.capture());
		assertThat(saved.getValue().getAction()).isEqualTo("BANNER_REORDER");
	}

	@Test
	@DisplayName("이미 연속인 비어 있지 않은 목록의 동일 순서는 감사 기록을 추가하지 않는다")
	void skipsUnchangedNonEmptyOrder() {
		// given
		when(repository.findAll()).thenReturn(List.of(banner(A, 1), banner(B, 2)));
		// when
		service.reorder(ADMIN, List.of(A, B));
		// then
		verify(repository).reorder(List.of(A, B), NOW);
		verifyNoInteractions(audit);
	}

	@Test
	@DisplayName("공개 이미지 설정 오류는 이벤트·목록 변경 전에 실패한다")
	void rejectsMissingImageConfigurationBeforeWriting() {
		// given
		org.mockito.Mockito.doThrow(new IllegalStateException("missing public image config")).when(images).requireConfigured();
		// when / then
		assertThatThrownBy(() -> service.create(ADMIN, settings(1))).isInstanceOf(IllegalStateException.class);
		verifyNoInteractions(events, repository, audit);
	}

	private static User actor(UserRole role, UserStatus status) {
		return new User(UUID.randomUUID(), "테스트", role, status, null, null, null, null, null, NOW, NOW);
	}
	private static BannerSettings settings(int position) { return new BannerSettings(EVENT, "banner/key.png", position); }
	private static Banner banner(UUID id, int position) { return new Banner(id, EVENT, "banner/key.png", position, NOW, NOW); }
	private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, BannerErrorCode error) {
		assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
				failure -> assertThat(failure.getErrorCode()).isEqualTo(error));
	}
}
