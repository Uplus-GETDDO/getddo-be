package com.getddo.db.abuse.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 최초 차단 시각·근거를 보존하는 이력. 최초 삽입 이후 기존 행은 수정하지 않는다. */
@Getter
@Entity
@Immutable
@Table(name = "abuse_cases", uniqueConstraints = @UniqueConstraint(name = "uq_abuse_user_source_blocked",
		columnNames = {"user_id", "source_type", "blocked_at"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AbuseCaseEntity {

	@Id
	@Column(nullable = false, updatable = false, length = 16)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false, length = 16)
	private UUID userId;

	@Column(name = "event_id", updatable = false, length = 16)
	private UUID eventId;

	@Column(name = "attendance_id", updatable = false, length = 16)
	private UUID attendanceId;

	@Column(name = "mission_submission_id", updatable = false, length = 16)
	private UUID missionSubmissionId;

	@Column(name = "game_play_id", updatable = false, length = 16)
	private UUID gamePlayId;

	@Column(name = "source_type", nullable = false, updatable = false, length = 40)
	private String sourceType;

	@Column(name = "detection_type", nullable = false, updatable = false, length = 50)
	private String detectionType;

	@Column(name = "detection_reason", nullable = false, updatable = false, columnDefinition = "text")
	private String detectionReason;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, updatable = false, columnDefinition = "json")
	private String evidence;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	@Column(name = "detected_at", nullable = false, updatable = false)
	private Instant detectedAt;

	@Column(name = "blocked_at", nullable = false, updatable = false)
	private Instant blockedAt;

	@Column(name = "blocked_until", nullable = false, updatable = false)
	private Instant blockedUntil;

	/** Mapper가 최초 저널 값을 그대로 삽입한다. 비응모 원본 식별자는 항상 null로 유지한다. */
	public AbuseCaseEntity(UUID id, UUID userId, UUID eventId, String sourceType, String detectionType,
			String detectionReason, String evidence, Instant occurredAt, Instant detectedAt,
			Instant blockedAt, Instant blockedUntil) {
		this.id = id;
		this.userId = userId;
		this.eventId = eventId;
		this.sourceType = sourceType;
		this.detectionType = detectionType;
		this.detectionReason = detectionReason;
		this.evidence = evidence;
		this.occurredAt = occurredAt;
		this.detectedAt = detectedAt;
		this.blockedAt = blockedAt;
		this.blockedUntil = blockedUntil;
	}
}
