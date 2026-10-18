package com.getddo.db.banner.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import com.getddo.core.banner.domain.Banner;
import com.getddo.db.banner.entity.BannerEntity;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface BannerMapper {
	Banner toDomain(BannerEntity entity);
}
