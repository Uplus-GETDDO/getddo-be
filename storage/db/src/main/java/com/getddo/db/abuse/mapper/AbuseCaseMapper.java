package com.getddo.db.abuse.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.getddo.core.abuse.domain.AbuseCaseSummary;
import com.getddo.core.common.ratelimit.EntryBlock;
import com.getddo.db.abuse.entity.AbuseCaseEntity;

/** 이력과 조회 시 조인한 표시값을 합치고 저장 JSON은 목록 모델로 옮기지 않는다. */
@Mapper(componentModel = "spring", uses = AbuseEvidenceMapper.class, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AbuseCaseMapper {

	/** 차단 시각을 새로 계산하지 않고 저장값을 그대로 옮긴다. */
	@Mapping(target = "userNickname", source = "userNickname")
	@Mapping(target = "eventTitle", source = "eventTitle")
	AbuseCaseSummary toSummary(AbuseCaseEntity entity, String userNickname, String eventTitle);

	/** ENTRY 고정 조합과 최초 시각·근거만 저장한다. 기존 조회 조인 표시값은 중복 저장하지 않는다. */
	@Mapping(target = "sourceType", constant = EntryBlock.SOURCE_TYPE)
	@Mapping(target = "detectionType", constant = EntryBlock.DETECTION_TYPE)
	@Mapping(target = "detectionReason", constant = EntryBlock.DETECTION_REASON)
	@Mapping(target = "detectedAt", source = "blockedAt")
	@Mapping(target = "attendanceId", ignore = true)
	@Mapping(target = "missionSubmissionId", ignore = true)
	@Mapping(target = "gamePlayId", ignore = true)
	AbuseCaseEntity toEntity(EntryBlock block);
}
