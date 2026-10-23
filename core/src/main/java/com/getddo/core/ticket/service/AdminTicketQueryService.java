package com.getddo.core.ticket.service;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.ticket.domain.AdminTicketHistoryFilter;
import com.getddo.core.ticket.domain.AdminTicketHistoryView;
import com.getddo.core.ticket.domain.MyTickets;
import com.getddo.core.ticket.domain.TicketConsistencyResult;
import com.getddo.core.ticket.domain.TicketGrantFilter;
import com.getddo.core.ticket.domain.TicketGrantView;
import com.getddo.core.ticket.domain.TicketInconsistencyReport;
import com.getddo.core.ticket.domain.TicketRefundFilter;
import com.getddo.core.ticket.domain.TicketRefundView;
import com.getddo.core.ticket.exception.TicketErrorCode;
import com.getddo.core.ticket.exception.TicketException;
import com.getddo.core.ticket.repository.AdminTicketQueryRepository;
import com.getddo.core.ticket.repository.TicketQueryRepository;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.repository.UserNameRepository;

/**
 * 관리자의 응모권 지급(AO03)·처리(AO05)·반환(AO08) 이력과 정합성(AO04·AO09) 조회. 읽기 전용이며 감사 기록 대상이 아니다.
 *
 * <p>모든 조회는 활성 관리자만 할 수 있고, 시작 시각이 끝 시각보다 앞서지 않는 기간은 조회 조건 오류로 거절한다.</p>
 */
@Service
@RequiredArgsConstructor
public class AdminTicketQueryService {

	private final AdminTicketQueryRepository repository;
	private final TicketQueryRepository queryRepository;
	private final TimeProvider timeProvider;
	private final UserNameRepository userNames;

	/**
	 * 응모권 지급 이력의 한 페이지를 조회한다.
	 *
	 * @param admin  공통 사용자 문맥이 확인한 요청 사용자. 활성 관리자여야 한다.
	 * @param filter 조회 조건
	 * @param page   페이지 번호와 크기
	 * @throws TicketException 관리자가 아니거나 기간 조건이 올바르지 않은 경우
	 */
	@Transactional(readOnly = true)
	public PageResult<TicketGrantView> getGrants(User admin, TicketGrantFilter filter, PageQuery page) {
		requireActiveAdmin(admin);
		requireValidPeriod(filter.hasValidPeriod());
		return repository.findGrants(filter, page);
	}

	/** 응모권 처리 이력의 한 페이지를 조회한다. 권한·기간 규칙은 {@link #getGrants}와 같다. */
	@Transactional(readOnly = true)
	public PageResult<AdminTicketHistoryView> getHistories(User admin, AdminTicketHistoryFilter filter,
			PageQuery page) {
		requireActiveAdmin(admin);
		requireValidPeriod(filter.hasValidPeriod());
		return repository.findHistories(filter, page);
	}

	/** 응모권 반환 이력의 한 페이지를 조회한다. 권한·기간 규칙은 {@link #getGrants}와 같다. */
	@Transactional(readOnly = true)
	public PageResult<TicketRefundView> getRefunds(User admin, TicketRefundFilter filter, PageQuery page) {
		requireActiveAdmin(admin);
		requireValidPeriod(filter.hasValidPeriod());
		return repository.findRefunds(filter, page);
	}

	/**
	 * 한 사용자의 응모권 현재 상태와 마지막 처리 이력의 정합성을 확인한다(AO04).
	 *
	 * <p>사용 가능 수와 어긋난 응모권을 하나의 반복 가능한 읽기 스냅샷에서 구해 같은 기준 시점의 값이 되게 한다.</p>
	 *
	 * @throws TicketException 관리자가 아니거나 사용자가 없는 경우
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public TicketConsistencyResult getConsistency(User admin, UUID userId) {
		requireActiveAdmin(admin);
		if (!repository.userExists(userId)) {
			throw new TicketException(TicketErrorCode.TICKET_USER_NOT_FOUND);
		}
		Instant checkedAt = timeProvider.now();
		long available = MyTickets.of(queryRepository.findHoldings(userId, checkedAt), checkedAt).getAvailableCount();
		String userName = userNames.findNamesByIds(Set.of(userId)).get(userId);
		return new TicketConsistencyResult(userId, userName, available, repository.findInconsistenciesOf(userId),
				checkedAt);
	}

	/**
	 * 전체 사용자의 어긋난 응모권을 요청 시점에 계산해 한 페이지 돌려준다(AO09).
	 *
	 * @param userId 사용자로 좁히려면 사용자 ID, 전체면 null
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public TicketInconsistencyReport getInconsistencies(User admin, UUID userId, PageQuery page) {
		requireActiveAdmin(admin);
		Instant checkedAt = timeProvider.now();
		return new TicketInconsistencyReport(repository.findInconsistencies(userId, page), checkedAt);
	}

	private static void requireValidPeriod(boolean valid) {
		if (!valid) {
			throw new TicketException(TicketErrorCode.TICKET_INVALID_HISTORY_QUERY);
		}
	}

	private static void requireActiveAdmin(User user) {
		if (user == null || user.getRole() != UserRole.ADMIN || user.getStatus() != UserStatus.ACTIVE) {
			throw new TicketException(TicketErrorCode.TICKET_ADMIN_REQUIRED);
		}
	}
}
