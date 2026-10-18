package com.getddo.core.drawing.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.drawing.domain.DrawPublication;
import com.getddo.core.drawing.domain.DrawPublicationRun;
import com.getddo.core.drawing.domain.DrawResultConfirmation;

/** 공개 전용 저장소. 변경은 서비스가 이벤트 선잠금을 가진 트랜잭션에서 수행한다. */
public interface DrawPublicationRepository {
	/** savePublication 이후 같은 트랜잭션에서 실행·확인 연결을 저장한다. */
	void saveExecutionEvidence(UUID publicationId, UUID eventId, List<DrawPublication.Execution> executions);
	/**
	 * 페이지·경계 이전 버전 또는 내부 전체 이력의 ID를 일괄 조회한다.
	 * 근거가 없는 공개 ID는 맵에서 생략한다. 호출자는 getOrDefault로 빈 목록을 사용한다. */
	Map<UUID, List<DrawPublication.Execution>> findExecutionEvidence(Set<UUID> publicationIds);
	/** 이벤트의 가장 높은 버전인 실제 공개 기록을 조회한다. */
	Optional<DrawPublication> findLatest(UUID eventId);
	/** 이벤트의 실제 공개 이력을 조회한다. 전체 조회는 버전 오름차순, 페이지 조회는 내림차순이다. */
	List<DrawPublication> findHistory(UUID eventId);
	/** 이벤트의 실제 공개 이력을 조회한다. 전체 조회는 버전 오름차순, 페이지 조회는 내림차순이다. */
	PageResult<DrawPublication> findHistory(UUID eventId, PageQuery query);
	/** 지정 버전보다 작은 버전 중 가장 최근 실제 공개 기록을 조회한다. */
	Optional<DrawPublication> findPrevious(UUID eventId, int revision);
	/** 이벤트 식별자의 저장 여부를 확인한다. */
	boolean eventExists(UUID eventId);
	/** 지정 공개 버전에 실제 포함된 결과의 경품 자리와 사용자를 조회한다. */
	List<DrawPublication.Slot> findPublishedSlots(UUID publicationId);
	/** 지정 공개 버전에 실제 포함된 결과의 경품 자리와 사용자를 조회한다. */
	Map<UUID, List<DrawPublication.Slot>> findPublishedSlots(Set<UUID> publicationIds);
	/** 결과가 현재 취소됐는지와 무관하게 과거 공개 포함 기록의 존재를 확인한다. */
	boolean wasPublished(UUID resultId);
	/** 정상 최초 공개의 빠른 경로와 취소·재추첨 명단 구성 경로를 구분한다. */
	boolean hasCancellationOrReplacement(UUID eventId);
	/** 정상 최초 공개 버전과 실제 선정 결과의 포함 기록을 함께 저장한다. */
	DrawPublication saveInitial(UUID eventId, UUID runId, List<UUID> selectedResultIds, Instant publishedAt);
	/** 최초 공개의 실제 발표자를 기존 실행별 확인자와 구분해 메타데이터에 저장한다. */
	DrawPublication saveReviewedInitial(UUID eventId, UUID sourceRunId, String reason, UUID publisherId,
			List<DrawPublication.Entry> entries, Instant publishedAt);
	/** 기존 추첨 실행이 속한 이벤트 식별자를 조회한다. */
	Optional<UUID> findEventId(UUID runId);
	/** 이벤트 잠금 이후 공개에 필요한 기존 실행과 결과만 조회한다. 상태·입력·취소를 변경하지 않는다. */
	List<DrawPublicationRun> findReplacementRuns(UUID eventId);
	/** 이벤트의 기존 결과에 연결된 취소 사유와 재추첨 실행 참조를 조회한다. */
	List<DrawPublication.Cancellation> findCancellations(UUID eventId);
	/** 최초·재추첨 실행에 이미 저장된 관리자 확인 기록을 조회한다. */
	Optional<DrawResultConfirmation> findConfirmation(UUID runId);
	/** 최초·재추첨 결과의 관리자 확인자와 확인 시각을 저장한다. */
	DrawResultConfirmation saveConfirmation(UUID runId, UUID actorId, Instant confirmedAt);
	/** 공개 메타데이터와 포함 결과를 저장하고 재조회한 실제 공개 버전을 반환한다. */
	DrawPublication savePublication(UUID eventId, UUID sourceRunId, int revision, String reason,
			DrawResultConfirmation confirmation, List<DrawPublication.Entry> entries, Instant publishedAt);
}
