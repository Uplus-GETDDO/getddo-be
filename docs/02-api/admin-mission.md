# GD-177 관리자 미션 조회·등록

- 작업 브랜치: `feat/GD-177`
- 공용 계약 원본: [미션 API](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/mission.md)의 AM01~AM03, 규칙은 [미션 도메인 규칙](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/02-domain/mission.md). 이 문서는 백엔드 구현 범위와 판단만 설명한다.

## 구현된 경로

기본 경로 `/api/v1/admin/missions`. 모든 경로는 활성 ADMIN만 호출할 수 있고 아니면 403 `MISSION-006`이다.

| ID | 메서드·경로 | 내용 |
| --- | --- | --- |
| AM01 | GET `/` | 미션 목록을 운영 시작 시각 최신순(같으면 ID 내림차순)으로 조회. `page,size,missionType,status,keyword,from,to`. 문항은 담지 않는다 |
| AM02 | GET `/{missionId}` | 정답 정보를 포함한 문항·선택지와 함께 조회. 없는 미션은 404 `MISSION-003` |
| AM03 | POST `/` | 미션과 문항·선택지·보상 정책을 한 트랜잭션에 등록. 201 |

`size`는 1~100(기본 20)이다. `from`·`to`는 시간대를 포함한 ISO-8601 시각이며 **운영 시작 시각**(`startsAt`)이 `[from, to)`인 미션을 거른다. 시작이 끝보다 앞서지 않으면 400 `COMMON-002`다. `status`는 저장된 운영 상태 값으로 거르고, `keyword`는 제목 부분 일치(대소문자 무시)다.

## 공용 스펙과 달라진 부분

- **초안(DRAFT) 상태 없음**: DB는 `ACTIVE`/`ENDED`만 저장하고 `Mission.register`도 등록한 미션을 바로 `ACTIVE`로 만든다. `AdminMission.status`는 `ACTIVE`/`ENDED`뿐이다. 사용자가 제출할 수 있는 기간은 `startsAt`·`endsAt`이 정한다. 시작 전 초안 수정·공개·삭제는 제공하지 않는다.
- **`rewardTicketCount`는 항상 1**: 미션 완료 보상은 등급 응모권 1장으로 고정이다. 요청에서는 생략하거나 1만 보낼 수 있고, 다른 값은 400 `MISSION-001`이다. 응답의 값은 항상 1이다. 미션마다 수량 1의 보상 정책(`reward_policies`, `MISSION`, 등급 비움)을 함께 만들어 `rewardPolicyId`로 연결한다.
- **목록은 문항을 담지 않는다**: AM01의 `AdminMission`에는 `questions`가 없다. 문항은 AM02·AM03 응답에만 있다.
- 퀴즈 문항에는 `required`가 없고(퀴즈는 모든 문항을 맞혀야 완료), 설문 문항에는 `correctAnswer`와 선택지의 `isCorrect`가 없다.

## 등록 검증 (AM03)

- 제목 1~200자, 설명 비어 있지 않음, 종료 시각이 시작 시각보다 뒤여야 한다. 이미지 경로는 500자 이하다.
- 퀴즈 미션은 퀴즈 문항(`OX`, `SINGLE_CHOICE`, `SHORT_ANSWER`)만, 설문 미션은 설문 문항(`SINGLE_CHOICE`, `FREE_TEXT`)만 담는다. 문항은 1개 이상이고 표시 순서가 겹치지 않는다. 설문 문항은 `required`가 필수이고 퀴즈 문항은 `required`를 보내지 않는다.
- 단답형은 정답 문자열이 필수이고 선택지가 없다. 선택형은 선택지가 있고 정답 문자열이 없다. 설문의 자유 서술형은 선택지가 없다.
- 선택형 퀴즈(`OX`, `SINGLE_CHOICE`)는 **선택지가 2개 이상이고 정답이 정확히 하나**여야 한다. 공용 스펙은 정답 개수와 OX 표현을 "미션 완료·채점 세부 계약"에 맡겨 두었는데, 정답이 없거나 여럿인 문항은 사용자가 풀 수 없거나 채점이 모호해져서 이번 구현에서 이렇게 막았다.
- 구성 오류는 모두 400 `MISSION-001`이다. 필수값 누락·길이 초과는 `COMMON-002`, JSON 형식 오류는 `COMMON-005`다.
- 등록은 감사 기록(`MISSION_CREATE`)을 남긴다. 공용 스펙에 정해진 작업 코드는 없어 이 구현에서 정했다.

## 저장

- 보상 정책 → 미션 → 퀴즈/설문 문항 → 선택지 순으로 같은 트랜잭션에서 저장하고, 중간에 실패하면 모두 취소한다. 모든 ID는 서버가 만든다.
- 운영 중인 미션의 문항·조건·보상은 수정하지 않으므로 수정 경로를 두지 않았다. 변경은 새 미션으로 등록한다.
- 현재 미션을 `ENDED`로 바꾸는 경로는 없다(저장된 `status`는 등록 때 `ACTIVE`로 남는다). 사용자 목록(M01)도 상태가 아니라 기간으로 진행 여부를 판단하므로 같은 방식이다.

## 검증

- 단위: `AdminMissionServiceTest`(권한, 보상 정책 연결, 정답 규칙, 기간), `AdminMissionControllerTest`(응답 필드, 필터, 요청 검증, 오류 코드).
- 작업 중 로컬 MySQL로 등록→상세 조회→기간 필터 조회→보상 정책·감사 기록 저장을 한 번 확인했고 그 테스트는 저장소에 남기지 않았다. 통합 테스트는 이번 작업에 포함하지 않았다.
- 기존 통합 테스트 컨텍스트(`TicketIntegrationTestApplication`)가 미션 서비스를 함께 올리므로 새 서비스가 쓰는 감사 로그 빈을 그 컨텍스트에 추가했다.
