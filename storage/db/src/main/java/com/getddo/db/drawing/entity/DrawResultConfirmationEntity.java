package com.getddo.db.drawing.entity;

import java.time.Instant;
import java.util.Objects;
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
import org.hibernate.annotations.UuidGenerator;

import com.getddo.core.drawing.domain.DrawResultConfirmation;

/** 최초·재추첨 실행마다 한 번 기록하는 관리자 최종 확인. 업무 트랜잭션은 서비스가 담당한다. */
@Getter
@Entity
@Immutable
@Table(name = "draw_result_confirmations", uniqueConstraints = {
			@UniqueConstraint(name = "uq_draw_result_confirmations_run", columnNames = "draw_run_id"),
			@UniqueConstraint(name = "uq_draw_result_confirmations_id_run", columnNames = {
				"id", "draw_run_id"
			})
		})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DrawResultConfirmationEntity {
	@Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	@Column(nullable = false, updatable = false, length = 16)
	private UUID id;
	@Column(name = "draw_run_id", nullable = false, updatable = false, length = 16)
	private UUID runId;
	@Column(name = "confirmed_by", nullable = false, updatable = false, length = 16)
	private UUID confirmedBy;
	@Column(name = "confirmed_at", nullable = false, updatable = false)
	private Instant confirmedAt;

	/** 상태·관리자 권한 검증을 마친 서비스에서만 저장해야 한다. HTTP 확인 계약은 별도다. */
	public DrawResultConfirmationEntity(UUID runId, UUID confirmedBy, Instant confirmedAt) {
		this.runId = Objects.requireNonNull(runId, "runId");
		this.confirmedBy = Objects.requireNonNull(confirmedBy, "confirmedBy");
		this.confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt");
	}

	/** 저장된 식별자와 업무 참조·시각을 공개 업무의 도메인 값으로 복원한다. */
	public DrawResultConfirmation toDomain() {
		return new DrawResultConfirmation(id, runId, confirmedBy, confirmedAt);
	}
}
