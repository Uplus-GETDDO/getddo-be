package com.getddo.api.abuse.config;

import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;

import com.getddo.core.abuse.domain.AbuseHistoryBacklog;
import com.getddo.core.abuse.service.AbuseHistoryService;

/** 건별 실패를 분리해 일반 재시도·일일 배치·인덱스 복구를 실행한다. */
@Slf4j
public class AbuseHistoryWorker implements DisposableBean {
	private final AbuseHistoryService service;
	private final int batchSize;
	private final Executor batchExecutor;
	private final AtomicBoolean batchRunning = new AtomicBoolean();

	public AbuseHistoryWorker(AbuseHistoryService service, int batchSize) {
		this(service, batchSize, Executors.newSingleThreadExecutor(
				Thread.ofPlatform().name("abuse-history-batch").factory()));
	}

	AbuseHistoryWorker(AbuseHistoryService service, int batchSize, Executor batchExecutor) {
		this.batchExecutor = batchExecutor;
		this.service = service;
		this.batchSize = batchSize;
	}

	@Scheduled(fixedDelayString = "${getddo.abuse.history-worker.poll-delay:1000}")
	public void poll() {
		for (int count = 0; count < batchSize; count++) {
			try {
				if (!service.processNext()) {
					break;
				}
			} catch (RuntimeException failure) {
				log.warn("Abuse history polling failed: exceptionType={}", failure.getClass().getName());
				break;
			}
		}
	}

	/** CD 실행 여부와 무관하게 매일 KST 06시에 그 시점까지의 배치 대기를 처리한다. */
	@Scheduled(cron = "0 0 6 * * *", zone = "Asia/Seoul")
	public void batch() {
		if (!batchRunning.compareAndSet(false, true)) {
			log.warn("Abuse history batch skipped: previous batch is still running");
			return;
		}
		try {
			// 공용 스케줄러는 트리거만 수행한다. 별도 TaskScheduler 빈으로 다른 작업자의 설정을 바꾸지 않는다.
			batchExecutor.execute(this::runBatch);
		} catch (RuntimeException failure) {
			batchRunning.set(false);
			log.warn("Abuse history batch dispatch failed: exceptionType={}", failure.getClass().getName());
		}
	}

	private void runBatch() {
		long processed = 0;
		try {
			Instant cutoff = service.batchCutoff();
			log.info("Abuse history batch started: cutoff={}", cutoff);
			while (!Thread.currentThread().isInterrupted() && service.processNextBatch(cutoff)) {
				// DB 실패는 건별로 다음 배치에 인계된다. 다른 건 처리를 중단하지 않는다.
				processed++;
			}
		} catch (RuntimeException failure) {
			log.warn("Abuse history batch interrupted: exceptionType={}", failure.getClass().getName());
		} finally {
			log.info("Abuse history batch finished: processed={}", processed);
			batchRunning.set(false);
		}
	}

	@Scheduled(fixedDelayString = "${getddo.abuse.history-worker.recovery-delay:60000}")
	public void recover() {
		try {
			AbuseHistoryBacklog backlog = service.recover();
			log.info("Abuse history backlog: pending={}, batch={}, journals={}",
					backlog.getPending(), backlog.getBatch(), backlog.getJournals());
		} catch (RuntimeException failure) {
			log.warn("Abuse history recovery failed: exceptionType={}", failure.getClass().getName());
		}
	}

	/** 종료 시 새 건을 선점하지 않는다. 처리 중 중단된 건은 저널 소유권 만료 후 다시 전달된다. */
	@Override
	public void destroy() {
		if (batchExecutor instanceof ExecutorService executor) {
			executor.shutdownNow();
		}
	}

}
