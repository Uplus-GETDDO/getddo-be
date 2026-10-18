package com.getddo.core.game.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * FE b43acc22의 takkoRun 물리·atlas를 고정 120Hz로 재생한다. 화면·음향·클라이언트 점수에 의존하지 않는다.
 * 충돌 데이터 변경 시 버전을 올려 기존 플레이의 판정 기준을 보존해야 한다.
 */
public final class TakkoRunReplay {

	private static final double DT = 1.0 / TakkoRunRules.TICK_RATE;
	private static final double[] SPEEDS = {420, 540, 660, 800};
	private static final int[] STAGE_SCORES = {0, 400, 1000, 1800};
	private static final double[] DOUBLE_CHANCES = {0, 0, 0.25, 0.4};
	// FE atlas의 원본 크기에 ATLAS_SCALE=0.5를 적용한 순서다. 동적 이미지 크기를 사용하지 않는다.
	private static final double[][] OBSTACLE_SIZES = {
			{50, 97.5}, {82, 64}, {57, 68}, {53.5, 89}, {76.5, 33.5}, {52, 112.5},
			{17.5, 101}, {57, 89.5}, {39.5, 107}, {57.5, 101.5}, {64.5, 97}, {56, 76},
			{42, 100}, {139, 84.5}, {96, 49}, {57, 24.5}, {82, 92.5}, {99, 89.5},
			{110.5, 86}, {93.5, 73}, {51.5, 102.5}, {134.5, 65.5}, {124, 49.5},
			{81.5, 81}, {82.5, 82.5}, {84, 73.5}, {112.5, 73}, {98, 53.5},
			{113.5, 55}, {129, 55}, {138, 84.5}, {147, 83}, {96, 68}, {68, 74.5},
			{91, 70.5}, {117.5, 56}, {92.5, 55.5}, {121, 99}, {160, 95.5},
			{67.5, 89.5}, {52, 71}
	};
	private static final int[][] STAGE_POOLS = {
			{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11},
			{12, 13, 14, 15, 16, 17, 18, 19},
			{20, 21, 22, 23, 24, 25, 26, 27, 28, 29},
			{30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40}
	};

	private final List<Obstacle> obstacles = new ArrayList<>();
	private int randomState;
	private boolean running;
	private boolean ended;
	private double height;
	private double velocity;
	private double jumpBuffer;
	private double distance;
	private double speed = SPEEDS[0];
	private double nextObstacleAt = 840 * 0.6;

	public TakkoRunReplay(long seed) {
		if (seed < 1 || seed > 0xffff_ffffL) {
			throw new IllegalArgumentException("0이 아닌 unsigned 32-bit seed가 필요합니다.");
		}
		this.randomState = (int) seed;
	}

	/** 입력을 즉시 반영하며 호출자는 같은 tick의 배열 순서를 지킨다. */
	public void apply(TakkoRunAction action) {
		if (ended) {
			throw new IllegalStateException("종료된 게임에는 입력할 수 없습니다.");
		}
		if (action == TakkoRunAction.RELEASE) {
			if (velocity > 420) {
				velocity = 420;
			}
			return;
		}
		running = true;
		if (onGround()) {
			launch();
		} else {
			jumpBuffer = 0.12;
		}
	}

	/** 활성 물리를 한 번 진행한다. 충돌한 tick에 true를 반환한다. */
	public boolean step() {
		if (!running || ended) {
			throw new IllegalStateException("진행 중인 게임의 tick만 계산할 수 있습니다.");
		}
		int stage = 0;
		for (int index = 1; index < STAGE_SCORES.length; index++) {
			if (getScore() >= STAGE_SCORES[index]) {
				stage = index;
			}
		}
		speed += (SPEEDS[stage] - speed) * Math.min(1, 5 * DT);
		double moved = speed * DT;
		distance += moved;
		jumpBuffer = Math.max(0, jumpBuffer - DT);
		if (!onGround() || velocity > 0) {
			velocity -= 2800 * DT;
			height = Math.max(0, height + velocity * DT);
			if (height == 0) {
				velocity = 0;
			}
		}
		if (onGround() && jumpBuffer > 0) {
			launch();
		}
		for (Obstacle obstacle : obstacles) {
			obstacle.x -= moved;
		}
		obstacles.removeIf(obstacle -> obstacle.x + obstacle.width <= 0);
		if (distance >= nextObstacleAt) {
			spawn(stage);
		}
		for (Obstacle obstacle : obstacles) {
			if (162 > obstacle.x + obstacle.width * 0.2
					&& 118 < obstacle.x + obstacle.width - obstacle.width * 0.2
					&& height + 8 < obstacle.height * 0.9) {
				ended = true;
				return true;
			}
		}
		return false;
	}

	public long getScore() {
		return (long) Math.floor(distance / TakkoRunRules.DISTANCE_PER_POINT);
	}

	private boolean onGround() {
		return height <= 0 && velocity <= 0;
	}

	private void launch() {
		velocity = 1000;
		jumpBuffer = 0;
	}

	private void spawn(int stage) {
		int[] pool = pool(stage, stage == 0 ? 90 : Double.POSITIVE_INFINITY);
		int first = choose(pool);
		obstacles.add(new Obstacle(860, OBSTACLE_SIZES[first]));
		if (random() < DOUBLE_CHANCES[stage]) {
			int second = choose(pool(stage, 80));
			obstacles.add(new Obstacle(860 + OBSTACLE_SIZES[first][0] + 46, OBSTACLE_SIZES[second]));
		}
		nextObstacleAt = distance + speed * (0.65 + random() * 0.7) + 180;
	}

	private static int[] pool(int stage, double maxHeight) {
		return Arrays.stream(STAGE_POOLS[stage])
				.filter(index -> OBSTACLE_SIZES[index][1] <= maxHeight).toArray();
	}

	private int choose(int[] pool) {
		return pool[(int) Math.floor(random() * pool.length)];
	}

	/** 색깔 게임과 동일한 xorshift32. JS 비트 연산과 unsigned 나눗셈 순서를 맞춘다. */
	private double random() {
		randomState ^= randomState << 13;
		randomState ^= randomState >>> 17;
		randomState ^= randomState << 5;
		return Integer.toUnsignedLong(randomState) / 4294967296.0;
	}

	private static final class Obstacle {
		private double x;
		private final double width;
		private final double height;

		private Obstacle(double x, double[] size) {
			this.x = x;
			this.width = size[0];
			this.height = size[1];
		}
	}
}
