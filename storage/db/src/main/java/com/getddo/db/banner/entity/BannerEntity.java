package com.getddo.db.banner.entity;

import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.getddo.core.banner.domain.BannerSettings;
import com.getddo.db.common.entity.BaseUpdatableEntity;
import com.getddo.db.event.entity.EventEntity;

@Getter
@Entity
@Table(name = "banners")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BannerEntity extends BaseUpdatableEntity {
	@Column(name = "event_id", length = 16)
	private UUID eventId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "event_id", insertable = false, updatable = false)
	private EventEntity event;

	@Column(name = "image_key", nullable = false, length = 500)
	private String imageKey;

	@Column(name = "display_order", nullable = false)
	private int displayOrder;

	/** 기존 URL은 신규 응답·연결에 사용하지 않으며 순서 재배치 때도 원문을 보존한다. */
	@Column(name = "event_url", length = 500)
	private String eventUrl;

	public BannerEntity(BannerSettings settings, int availablePosition) {
		eventId = settings.getEventId();
		imageKey = settings.getImageKey();
		displayOrder = availablePosition;
	}

	/** 순서는 별도 원자적 재배치에서 변경한다. */
	public void changeSettings(BannerSettings settings) {
		eventId = settings.getEventId();
		imageKey = settings.getImageKey();
	}
}
