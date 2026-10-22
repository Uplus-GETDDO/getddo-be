package com.getddo.db.user.repository;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.getddo.core.user.domain.User;
import com.getddo.core.user.repository.UserNameRepository;
import com.getddo.core.user.repository.UserRepository;
import com.getddo.db.user.entity.UserEntity;
import com.getddo.db.user.mapper.UserMapper;

@Repository
@RequiredArgsConstructor
public class UserRepositoryImpl implements UserRepository, UserNameRepository {

	private final UserJpaRepository userJpaRepository;
	private final UserMapper userMapper;

	@Override
	public Optional<User> findById(UUID userId) {
		return userJpaRepository.findById(userId).map(userMapper::toDomain);
	}

	@Override
	public Map<UUID, String> findNamesByIds(Collection<UUID> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		return userJpaRepository.findAllById(new HashSet<>(userIds)).stream()
				.collect(Collectors.toMap(UserEntity::getId, UserEntity::getName));
	}
}
