package com.getddo.core.abuse.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
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
	void failedInsertRollsBackBeforeFreshDuplicateCheck() {
		try (var context = context()) {
			AbuseCaseWriteRepository repository = context.getBean(AbuseCaseWriteRepository.class);
			PlatformTransactionManager manager = context.getBean(PlatformTransactionManager.class);
			EntryBlock block = block();
			doThrow(new DataIntegrityViolationException("duplicate")).when(repository).insert(block);
			when(repository.isSameStored(block)).thenReturn(true);
			context.getBean(AbuseCaseSaveService.class).save(block);
			var order = inOrder(repository, manager);
			order.verify(repository).insert(block);
			order.verify(manager).rollback(any());
			order.verify(repository).isSameStored(block);
			order.verify(manager, times(2)).commit(any());
			verify(manager, times(3)).getTransaction(any());
		}
	}

	@Test
	void mismatchedUniqueCollisionIsNotConsideredSuccess() {
		try (var context = context()) {
			AbuseCaseWriteRepository repository = context.getBean(AbuseCaseWriteRepository.class);
			EntryBlock block = block();
			doThrow(new DataIntegrityViolationException("different history")).when(repository).insert(block);
			assertThatThrownBy(() -> context.getBean(AbuseCaseSaveService.class).save(block))
					.isInstanceOf(DataIntegrityViolationException.class);
		}
	}

	@Test
	void commitFailurePropagatesToCaller() {
		try (var context = context()) {
			PlatformTransactionManager manager = context.getBean(PlatformTransactionManager.class);
			doThrow(new IllegalStateException("commit failed")).when(manager).commit(any());
			assertThatThrownBy(() -> context.getBean(AbuseCaseSaveService.class).save(block()))
					.isInstanceOf(IllegalStateException.class);
		}
	}

	private AnnotationConfigApplicationContext context() {
		var context = new AnnotationConfigApplicationContext();
		var manager = mock(PlatformTransactionManager.class);
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
