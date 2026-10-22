package com.getddo.db.abuse;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

import com.getddo.core.abuse.service.AbuseCaseQueryService;
import com.getddo.db.abuse.entity.AbuseCaseEntity;
import com.getddo.db.abuse.mapper.AbuseCaseMapper;
import com.getddo.db.abuse.repository.AbuseCaseRepositoryImpl;
import com.getddo.db.event.entity.EventEntity;
import com.getddo.db.user.entity.UserEntity;

/** 실제 차단 조회에 필요한 Entity·Mapper·Service만 등록한다. */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackageClasses = {AbuseCaseEntity.class, UserEntity.class, EventEntity.class})
@ComponentScan(basePackageClasses = AbuseCaseMapper.class)
@Import({AbuseCaseRepositoryImpl.class, AbuseCaseQueryService.class})
class AbusePersistenceTestConfiguration {
}
