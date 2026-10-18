package com.getddo.core.event.repository;

import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.event.domain.EventQueryFilter;
import com.getddo.core.event.domain.EventView;

/** 삭제된 이벤트를 제외하고 조회한다. 사용자 필터는 응답에 표시하는 공개 상태를 기준으로 한다. */
public interface EventQueryRepository {
	PageResult<EventView> findAll(EventQueryFilter filter, PageQuery page, boolean publicView);
	Optional<EventView> findById(UUID eventId);

	/** 한 페이지의 이벤트에서 사용자의 정상 참여 기록을 일괄 조회한다. 응모권 차감 수량과 무관하다. */
	Set<UUID> findEnteredEventIds(UUID userId, List<UUID> eventIds);
}
