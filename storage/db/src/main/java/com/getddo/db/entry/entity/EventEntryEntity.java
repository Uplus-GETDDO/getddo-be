package com.getddo.db.entry.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

import com.getddo.core.entry.domain.EntryStatus;

/**
 * 응모 요청의 결과 Entity. 접수 완료와 거절 이력을 함께 담으며 추가만 하고 수정·삭제하지 않는다.
 *
 * <p>ID는 서버가 만들지 않고 클라이언트가 요청 전에 정한 UUID({@code Idempotency-Key})다. 직접 지정한 ID로 {@code save}를
 * 부르면 Spring Data가 새 행인지 알 수 없어 {@code merge}(조회 뒤 저장)로 가고, 같은 ID의 중복 응모가 조용히 통과할 수 있다.
 * {@link Persistable}로 항상 새 행으로 보아 {@code INSERT}를 하게 하고, 중복이면 PK 위반 예외가 나게 한다.</p>
 */
@Getter
@Entity
@Immutable
@Table(name = "event_entries")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventEntryEntity implements Persistable<UUID> {

	@Id
	@Column(name = "id", nullable = false, updatable = false, length = 16)
	private UUID id;

	@Column(name = "event_id", nullable = false, updatable = false, length = 16)
	private UUID eventId;

	/** 접수 완료된 응모의 응모자. 거절 건은 없다. */
	@Column(name = "participant_id", updatable = false, length = 16)
	private UUID participantId;

	@Column(name = "user_id", nullable = false, updatable = false, length = 16)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, updatable = false)
	private EntryStatus status;

	@Column(name = "rejection_code", updatable = false, length = 20)
	private String rejectionCode;

	@Column(name = "rejection_reason", updatable = false, length = 200)
	private String rejectionReason;

	@Column(name = "requested_ticket_count", nullable = false, updatable = false)
	private int requestedTicketCount;

	@Column(name = "deducted_ticket_count", nullable = false, updatable = false)
	private int deductedTicketCount;

	@Column(name = "requested_bronze_count", nullable = false, updatable = false)
	private int requestedBronzeCount;

	@Column(name = "requested_silver_count", nullable = false, updatable = false)
	private int requestedSilverCount;

	@Column(name = "requested_gold_count", nullable = false, updatable = false)
	private int requestedGoldCount;

	@Column(name = "requested_platinum_count", nullable = false, updatable = false)
	private int requestedPlatinumCount;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Transient
	private boolean newEntity = true;

	public EventEntryEntity(UUID id, UUID eventId, UUID participantId, UUID userId, EntryStatus status,
			String rejectionCode, String rejectionReason, int requestedTicketCount, int deductedTicketCount,
			int requestedBronzeCount, int requestedSilverCount, int requestedGoldCount, int requestedPlatinumCount,
			Instant createdAt) {
		this.id = id;
		this.eventId = eventId;
		this.participantId = participantId;
		this.userId = userId;
		this.status = status;
		this.rejectionCode = rejectionCode;
		this.rejectionReason = rejectionReason;
		this.requestedTicketCount = requestedTicketCount;
		this.deductedTicketCount = deductedTicketCount;
		this.requestedBronzeCount = requestedBronzeCount;
		this.requestedSilverCount = requestedSilverCount;
		this.requestedGoldCount = requestedGoldCount;
		this.requestedPlatinumCount = requestedPlatinumCount;
		this.createdAt = createdAt;
	}

	@Override
	public boolean isNew() {
		return newEntity;
	}

	@PostLoad
	@PostPersist
	private void markPersisted() {
		newEntity = false;
	}
}
