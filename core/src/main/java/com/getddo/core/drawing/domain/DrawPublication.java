package com.getddo.core.drawing.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import lombok.EqualsAndHashCode;
import lombok.Getter;

/** 실제 공개한 명단과 공개 시각·버전을 보존한다. 이후 취소나 재추첨이 있어도 과거 버전은 바꾸지 않는다. */
@Getter
@EqualsAndHashCode
public final class DrawPublication {
	private final UUID id;
	private final UUID eventId;
	private final UUID sourceDrawId;
	private final int revision;
	private final DrawPublicationType type;
	private final String reason;
	private final UUID confirmedBy;
	private final Instant confirmedAt;
	private final Instant publishedAt;
	private final List<Entry> entries;

	/** 필수 메타데이터와 포함 결과의 불변식을 검증하고 공개 버전을 구성한다. */
	public DrawPublication(UUID id, UUID eventId, UUID sourceDrawId, int revision,
			DrawPublicationType type, String reason, UUID confirmedBy, Instant confirmedAt,
			Instant publishedAt, List<Entry> entries) {
		Objects.requireNonNull(id, "id");
		validateMetadata(eventId, sourceDrawId, revision, type, reason, confirmedBy, confirmedAt, publishedAt);
		entries = validatedEntries(entries);

		this.id = id;
		this.eventId = eventId;
		this.sourceDrawId = sourceDrawId;
		this.revision = revision;
		this.type = type;
		this.reason = reason;
		this.confirmedBy = confirmedBy;
		this.confirmedAt = confirmedAt;
		this.publishedAt = publishedAt;
		this.entries = entries;
	}

	/** ID 발급 전 저장 메타데이터에도 같은 불변식을 적용한다. 공개 버전 ID를 가짜로 만들지 않는다. */
	public static void validateMetadata(UUID eventId, UUID sourceDrawId, int revision, DrawPublicationType type,
			String reason, UUID confirmedBy, Instant confirmedAt, Instant publishedAt) {
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(sourceDrawId, "sourceDrawId");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(publishedAt, "publishedAt");
		if (revision < 1 || (type == DrawPublicationType.INITIAL && revision != 1)
				|| (type == DrawPublicationType.UPDATE && revision < 2)) {
			throw new IllegalArgumentException("invalid publication revision");
		}
		if ((confirmedBy == null) != (confirmedAt == null)
				|| (confirmedAt != null && confirmedAt.isAfter(publishedAt))) {
			throw new IllegalArgumentException("invalid confirmation time");
		}
		if (type == DrawPublicationType.UPDATE && (confirmedBy == null || reason == null || reason.isBlank())) {
			throw new IllegalArgumentException("update requires confirmation and reason");
		}
	}

	/** 목록과 원소는 null일 수 없다. 복사·중복 검증으로 저장 전후의 명단 불변식을 지킨다. */
	public static List<Entry> validatedEntries(List<Entry> entries) {
		Objects.requireNonNull(entries, "entries");
		List<Entry> copied = List.copyOf(entries);
		if (copied.stream().map(Entry::getResultId).distinct().count() != copied.size())
		throw new IllegalArgumentException("duplicate publication result");
		return copied;
	}

	/** 유지 결과도 원본 실행과 원래 관리자 확인 식별자를 그대로 참조한다. */
	@Getter
	@EqualsAndHashCode
	public static final class Entry {
		private final UUID resultId;
		private final UUID runId;
		private final UUID confirmationId;

		/** 공개에 포함된 결과의 원본 실행과 선택적인 관리자 확인 참조를 보존한다. */
		public Entry(UUID resultId, UUID runId, UUID confirmationId) {
			Objects.requireNonNull(resultId, "resultId");
			Objects.requireNonNull(runId, "runId");

			this.resultId = resultId;
			this.runId = runId;
			this.confirmationId = confirmationId;
		}
	}

	/** 이 공개 버전에 반영된 최초·재추첨 실행의 확인자·시각·취소 근거다. 당첨자를 채우지 못한 실행도 포함한다. */
	@Getter
	@EqualsAndHashCode
	public static final class Execution {
		private final UUID runId;
		private final UUID confirmationId;
		private final UUID confirmedBy;
		private final Instant confirmedAt;
		private final List<DrawPublication.Cancellation> cancellations;

		/** 저장된 조회 필드를 보존하고 컬렉션은 외부 변경으로부터 보호한다. */
		public Execution(UUID runId, UUID confirmationId, UUID confirmedBy, Instant confirmedAt,
				List<DrawPublication.Cancellation> cancellations) {
			this.runId = Objects.requireNonNull(runId, "runId");
			this.confirmationId = Objects.requireNonNull(confirmationId, "confirmationId");
			this.confirmedBy = Objects.requireNonNull(confirmedBy, "confirmedBy");
			this.confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt");
			this.cancellations = List.copyOf(Objects.requireNonNull(cancellations, "cancellations"));
		}
	}

	/** 명단 구성에 필요한 기존 취소 정보다. 이 데이터 객체가 취소나 재추첨을 처리하지는 않는다. */
	@Getter
	public static final class Cancellation {
		private final UUID id;
		private final UUID resultId;
		private final UUID replacementRunId;
		private final String reason;

		/** 재추첨 실행 ID가 비어 있으면 아직 실행이 연결되지 않은 과거 취소 기록이다. */
		public Cancellation(UUID id, UUID resultId, UUID replacementRunId, String reason) {
			this.id = Objects.requireNonNull(id, "id");
			this.resultId = Objects.requireNonNull(resultId, "resultId");
			this.replacementRunId = replacementRunId;
			this.reason = reason;
		}
	}

	/** 해당 공개 버전에 포함된 경품 자리와 당첨자다. 이후 취소돼도 과거 공개 기록에는 남는다. */
	@Getter
	@EqualsAndHashCode
	public static final class Slot {
		private final UUID prizeId;
		private final int slotNumber;
		private final UUID resultId;
		private final UUID userId;

		/** 경품의 양수 자리 번호와 실제 공개된 결과·사용자를 검증해 보존한다. */
		public Slot(UUID prizeId, int slotNumber, UUID resultId, UUID userId) {
			this.prizeId = Objects.requireNonNull(prizeId, "prizeId");
			if (slotNumber < 1) throw new IllegalArgumentException("slotNumber must be positive");
			this.slotNumber = slotNumber;
			this.resultId = Objects.requireNonNull(resultId, "resultId");
			this.userId = Objects.requireNonNull(userId, "userId");
		}
	}
}
