# API 계약서: 지난 기록 보기 (v1)

- 작성: 개발자, 2026-10-08 (기획서 `.company/plans/지난-기록-보기.md` 3장 기준)
- 대상: web / mobile 개발자(호출)
- 기존 계약(`api-today.md`)은 바꾸지 않는다. 이 문서는 **조회 API 1개 추가**만 다룬다.
- 공통 규칙: JSON camelCase, 날짜 `YYYY-MM-DD`, 시각 ISO-8601 UTC, 오류 `{code, message}`, `Authorization: Bearer` 필수, 남의/없는 pet 은 404 `NOT_FOUND`.

## 1. 기간별 일일 기록 조회

`GET /api/pets/{petId}/daily-logs?from=YYYY-MM-DD&to=YYYY-MM-DD`

(같은 경로의 `PUT /api/pets/{petId}/daily-logs/{recordDate}` 와는 별개다.)

| 파라미터 | 필수 | 규칙 |
|---|---|---|
| `from` | 아니오 | 생략하면 `to - 29일`(30일치). 양 끝 포함 |
| `to` | 아니오 | 생략하면 **서버의 현재 기록 날짜**(새벽 4시 규칙). 현재 기록 날짜보다 미래면 400 |

- 기본은 **파라미터 없이 호출**한다. 서버가 "오늘"을 정하므로 클라이언트는 기록 날짜를 계산하지 않는다. 7일 보기는 받은 `days` 의 마지막 7개를 잘라 쓴다(재호출 불필요).
- 하루 상세는 `from=to=해당 날짜`로 호출할 수 있다.
- 기간은 양 끝 포함 최대 90일이다.

### 응답 200
```json
{
  "petId": "uuid",
  "from": "2026-09-09",
  "to": "2026-10-08",
  "recordDate": "2026-10-08",
  "medicationBasis": "current",
  "days": [
    { "recordDate": "2026-09-09",
      "dailyLog": null,
      "medication": { "scheduledCount": 3, "takenCount": 0 } },
    { "recordDate": "2026-09-10",
      "dailyLog": { "id": "uuid", "petId": "uuid", "recordDate": "2026-09-10",
                    "foodLevel": 2, "waterLevel": null, "waterMl": 350, "weightKg": 4.4,
                    "symptoms": ["vomit"], "symptomsNone": false, "symptomOther": null,
                    "memo": "", "updatedAt": "2026-09-10T13:20:00Z" },
      "medication": { "scheduledCount": 3, "takenCount": 3 } }
  ]
}
```

| 필드 | 설명 |
|---|---|
| `from`, `to` | 실제로 적용된 기간(기본값 반영) |
| `recordDate` | 서버의 **현재 기록 날짜**. "오늘(진행 중)" 표시·오늘 화면 링크 판단에 쓴다 |
| `medicationBasis` | 항상 `"current"`(값·형식 불변). 이력 이전 기간은 현재 등록된 약을 기준으로 환산한 근사값임을 뜻한다 |
| `days` | `from`~`to` 의 **모든 날짜를 오름차순으로 빠짐없이**(최대 90개). 클라이언트가 빈 날짜를 계산·보간하지 않는다 |
| `days[].dailyLog` | 그날 기록이 없으면 `null`. 있으면 `PUT` 응답의 `DailyLog` 와 같은 형식 |
| `days[].medication.scheduledCount` | 그 날짜의 예정 회차 수 = 그 날짜에 유효했던 약 일정의 시각 수 합(삭제한 약도 삭제 전 날짜는 포함) |
| `days[].medication.takenCount` | 같은 약·같은 시각에 체크된 회차 수 (`<= scheduledCount` 항상 성립) |

`dailyLog` 안의 값 해석은 기존과 같다: `null` = 안 적음(0이나 "보통"으로 채우지 않는다), `symptomsNone=true` = 명시적 "특이사항 없음", `symptoms=[] && !symptomsNone` = 증상 칸을 안 적음. `weightKg` 는 number.

`dailyLog` 의 산책·배변 횟수(`walkCount`, `poopCount`) 추가 필드는 `docs/api-routine.md`.

### 투약 집계의 기준 (화면에 "현재 등록된 약 기준이에요"로 알릴 것)
서버는 약 시각(일정)이 바뀐 이력을 날짜 범위로 남기고(2026-10-15~), 과거 날짜는 **그 날짜에 유효했던 일정**으로 센다.
- 약 시각을 바꾸면 **바꾼 날(기록 날짜, 새벽 4시 규칙)부터** 새 시각으로 센다. 바꾸기 전 날짜는 당시 시각의 개수로 세고, 당시 시각의 체크도 그대로 센다.
- 삭제(비활성)한 약도 **삭제한 날 전날까지는** 센다(삭제 전 날짜의 예정·체크가 집계에 남는다). 삭제한 날부터는 뺀다. 등록한 날 바로 지운 약은 과거에 예정이 없다.
- 약 등록일(기록 날짜)보다 이른 날짜는 그 약을 예정에서 뺀다.
- 이력을 쓰기 시작하기 전에 있던 약(V7 백필)은 정확한 과거 일정을 알 수 없어 **현재 시각을 등록일부터 적용한 근사값**이다. 그래서 `medicationBasis` 는 계속 `"current"` 이고 응답 형식은 바뀌지 않았다.
- 같은 날 이미 체크한 옛 시각은, 그날 시각을 바꾸면 그날 집계에서 빠진다(변경한 날부터 새 시각이 적용되므로).
- 이름·용량만 바꾼 변경은 이력을 만들지 않는다.
- 약이 없으면 `0/0` → 투약 줄을 숨긴다.
- 퍼센트·"준수율" 같은 평가 표현을 쓰지 말고 "투약 체크 5/6회"처럼 횟수만 보여 준다.
- 이력은 투약 시각과 적용 날짜만 담고 약 이름·용량은 담지 않는다. 회원 탈퇴·반려동물 삭제 때 함께 지워진다.

## 2. 오류
| HTTP | code | 언제 |
|---|---|---|
| 401 | `UNAUTHORIZED` | 토큰 없음·만료 |
| 404 | `NOT_FOUND` | 없는 pet 또는 남의 pet (403 아님). 파라미터 검증보다 먼저 판단한다 |
| 400 | `INVALID_DATE_RANGE` (신규) | `from`/`to` 형식 오류(`YYYY-MM-DD` 아님, 빈 값, 존재하지 않는 날짜), `from > to`, 구간 91일 이상, `to` 가 현재 기록 날짜보다 미래 |

`message` 는 한국어 한 문장이며 그대로 보여 줘도 된다. 분기는 `code` 로 한다.

## 3. 클라이언트 주의
- 새벽 0~4시에는 `to`/`recordDate` 가 전날이다. 기기 시계로 "오늘"을 만들어 `to` 에 넣으면 미래 날짜로 400 이 날 수 있으니, 기본 호출을 쓴다.
- 읽기 전용이다. 과거 날짜 수정은 여전히 `INVALID_RECORD_DATE` 로 막힌다.
- `/api/**` 응답은 캐시하지 않는다(건강 기록).
- 서버는 평균·증감 같은 통계를 계산하지 않는다. 화면에서 `days` 로 계산한다.
- 성능: 요청당 DB 조회 5회 고정 = 인증 필터의 사용자 확인 1회 + 서비스 4회(pet 소유 확인, 약 일정 이력, daily_logs, med_logs). N+1 없음(`HistoryQueryCountTest` 로 실측), 90일 응답은 수십 KB 이하.
