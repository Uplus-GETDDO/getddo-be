package com.getddo.api.game.controller;

import java.util.UUID;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.getddo.api.common.context.CurrentUser;
import com.getddo.api.common.ratelimit.RateLimited;
import com.getddo.api.common.response.ResponseEnvelope;
import com.getddo.api.game.dto.request.GameResultRequest;
import com.getddo.api.game.dto.response.GameResultResponse;
import com.getddo.core.game.domain.GamePlayResult;
import com.getddo.core.game.domain.GameResultStatus;
import com.getddo.core.game.service.GamePlayResultService;
import com.getddo.core.user.domain.User;

/** 게임 종료 기록(G04). 실제 동시 처리만 202이며 확정 결과는 200으로 반환한다. */
@RestController
@RequestMapping("/api/v1/games/{gameId}/plays/{playId}/result")
@RequiredArgsConstructor
public class GameResultController {
	private final GamePlayResultService service;

	@PutMapping
	@RateLimited(policy = "game-result")
	public ResponseEntity<ResponseEnvelope<GameResultResponse>> submit(
			@CurrentUser(membershipRequired = false) User user, @PathVariable UUID gameId,
			@PathVariable UUID playId, @Valid @RequestBody GameResultRequest request) {
		GamePlayResult result = service.submit(user, request.toDomain(gameId, playId));
		return ResponseEntity.status(result.getStatus() == GameResultStatus.PROCESSING ? HttpStatus.ACCEPTED : HttpStatus.OK)
				.body(ResponseEnvelope.success(GameResultResponse.from(result)));
	}
}
