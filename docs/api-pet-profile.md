# API 계약서: 반려동물 유형·생일·입양일 (v1)

- 2026-10-13 신규. 기획서: `초보-견주-온보딩-C-E-F.md` 단계 C(클라이언트 저장소 `.company/plans/`). 기존 문서의 문장은 바꾸지 않고, 이 문서가 **추가 필드만** 기술한다.
- 서버는 유형으로 판단·추천하지 않는다(화면 노출용 선택값). 날짜는 보호자가 입력한 값만 저장한다.
- 새 엔드포인트·새 오류 코드 없음. 서버 로그에 유형·날짜 값은 남기지 않는다.

## 1. 요청: `POST /api/pets`, `PUT /api/pets/{id}` 추가 필드 (모두 선택)

| 필드 | 값 | `POST` 생략 | `PUT` 생략 | 명시적 `null` |
|---|---|---|---|---|
| `profileType` | `"first_time"` / `"senior"` / null | null | **기존 값 유지** | 지움 |
| `birthDate` | `"YYYY-MM-DD"` / null | null | **기존 값 유지**(아래 연도 규칙 예외) | 지움 |
| `adoptedOn` | `"YYYY-MM-DD"` / null | null | **기존 값 유지** | 지움 |

기존 필드(`name`, `species`, `birthYear`, `conditions`)는 지금처럼 **전체 교체**(생략 = null)다. 바뀌지 않았다. 구현은 `common/PatchTextInput`(Jackson 에서 생략/null 구분, `dailylog/RoutineCountInput` 과 같은 방식)이다.

## 2. 규칙

- 날짜는 **달력 날짜**다. 서비스 시간대(`app.zone`, 기본 Asia/Seoul)의 오늘을 기준으로 하며 **새벽 4시 기록 날짜 규칙과 무관**하다(00:00~03:59 에도 오늘 날짜를 입력할 수 있다).
- `birthDate`, `adoptedOn`: 존재하는 날짜, 1980-01-01 이상, 오늘 이하.
- 입양일은 생일보다 빠를 수 없다(같은 날은 허용). `PUT` 에서 생략되어 유지되는 저장 값과 함께 검사한다.
- `birthDate` 가 오면 서버가 `birthYear` 를 그 연도로 맞춘다(요청에 `birthYear` 가 없어도 된다). 요청에 `birthYear` 도 있고 연도가 다르면 400.
- `PUT` 에서 `birthDate` 를 생략했는데 요청의 `birthYear` 가 저장된 `birthDate` 의 연도와 다르거나 `birthYear` 가 없으면, 저장된 `birthDate` 를 **null 로 지운다**(옛 클라이언트가 연도만 바꿨을 때의 모순 방지). 유형·입양일은 유지된다.
- 검증에 실패한 요청은 저장된 값을 바꾸지 않는다.

## 3. 오류 (400 `VALIDATION_ERROR`, 기존 형식 `{code, message}`, message 한국어)

미래 날짜 / 존재하지 않는 날짜·형식 오류·문자열이 아닌 값(`2025-02-30`, `2025/02/10`, 숫자) / 1980-01-01 이전 / 입양일 < 생일 / 생일 연도 ≠ `birthYear` / `profileType` 허용값 밖. `PET_LIMIT_REACHED`(409)·404 는 그대로다.

## 4. 응답: `Pet` (`POST/PUT/GET /api/pets`) 필드 추가

```json
{ "id": "…", "name": "초코", "species": "dog", "birthYear": 2015, "conditions": null, "hasPhoto": false,
  "profileType": "first_time", "birthDate": "2015-03-02", "adoptedOn": "2016-01-10",
  "createdAt": "…", "updatedAt": "…" }
```

`profileType`, `birthDate`, `adoptedOn` 은 항상 존재하며 값이 없으면 null 이다(기존 사용자 전부 null). 기존 필드·오류 코드는 불변이다.

## 5. 저장·삭제

- DB: `V6__pet_profile.sql` — `pets.profile_type`(CHECK first_time|senior), `birth_date`, `adopted_on`(CHECK 1980-01-01 이상), 입양일 ≥ 생일, 생일 연도 = `birth_year`. 모두 nullable.
- 회원 탈퇴 시 `pets` 행과 함께 삭제된다(`docs/api-account.md`). 본인 pet 만 읽고 쓴다(남의 pet 은 404).
