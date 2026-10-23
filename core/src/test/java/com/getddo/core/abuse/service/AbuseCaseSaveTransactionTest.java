package com.getddo.core.abuse.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.getddo.core.abuse.domain.AbuseEvidence;
import com.getddo.core.abuse.domain.AbuseTriggerScope;
import com.getddo.core.abuse.repository.AbuseCaseWriteRepository;
import com.getddo.core.common.ratelimit.EntryBlock;

class AbuseCaseSaveTransactionTest {
	@Test
	@DisplayName("삽입 실패를 롤백한 뒤 새 트랜잭션으로 중복을 확인한다")
	void failedInsertRollsBackBeforeFreshDuplicateCheck() {
		// given
		try (AnnotationConfigApplicationContext context = context()) {
			AbuseCaseWriteRepository repository = context.getBean(AbuseCaseWriteRepository.class);
			PlatformTransactionManager manager = context.getBean(PlatformTransactionManager.class);
			EntryBlock block = block();
			doThrow(new DataIntegrityViolationException("duplicate")).when(repository).insert(block);
			when(repository.isSameStored(block)).thenReturn(true);
			// when
			context.getBean(AbuseCaseSaveService.class).save(block);
			InOrder order = inOrder(repository, manager);
			// then
			order.verify(repository).insert(block);
			order.verify(manager).rollback(any());
			order.verify(repository).isSameStored(block);
			order.verify(manager, times(2)).commit(any());
			verify(manager, times(3)).getTransaction(any());
		}
	}

	@Test
	@DisplayName("다른 이력과의 제약 충돌은 저장 성공으로 처리하지 않는다")
	void mismatchedUniqueCollisionIsNotConsideredSuccess() {
		// given
		try (AnnotationConfigApplicationContext context = context()) {
			AbuseCaseWriteRepository repository = context.getBean(AbuseCaseWriteRepository.class);
			EntryBlock block = block();
			doThrow(new DataIntegrityViolationException("different history")).when(repository).insert(block);
			// when / then
			assertThatThrownBy(() -> context.getBean(AbuseCaseSaveService.class).save(block))
					.isInstanceOf(DataIntegrityViolationException.class);
		}
	}

	@Test
	@DisplayName("커밋 실패는 호출자에게 전달한다")
	void commitFailurePropagatesToCaller() {
		// given
		try (AnnotationConfigApplicationContext context = context()) {
			PlatformTransactionManager manager = context.getBean(PlatformTransactionManager.class);
			doThrow(new IllegalStateException("commit failed")).when(manager).commit(any());
			// when / then
			assertThatThrownBy(() -> context.getBean(AbuseCaseSaveService.class).save(block()))
					.isInstanceOf(IllegalStateException.class);
		}
	}

	private AnnotationConfigApplicationContext context() {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
		when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
		context.registerBean(PlatformTransactionManager.class, () -> manager);
		context.registerBean(AbuseCaseWriteRepository.class, () -> mock(AbuseCaseWriteRepository.class));
		context.register(Config.class, AbuseCaseTransactionService.class, AbuseCaseSaveService.class);
		context.refresh();
		return context;
	}

	private EntryBlock block() {
		Instant now = Instant.parse("2026-10-22T20:00:00Z");
		return new EntryBlock(UUID.randomUUID(), UUID.randomUUID(), null, now, now, now.plusSeconds(600),
				new AbuseEvidence("entry-v1", AbuseTriggerScope.USER, now, now.plusSeconds(60), 20, 20, 20));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableTransactionManagement
	static class Config {
	}
}
