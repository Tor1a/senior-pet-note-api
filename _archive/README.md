# _archive — 보관 폴더 (사용하지 않음)

- `supabase/`: 2026-10-06 기술 스택 변경(`docs/decisions/2026-10-06-기술-스택-변경.md`)으로 더 이상 쓰지 않는 Supabase 스키마.
  테이블 설계는 `src/main/resources/db/migration/V1__init_schema.sql` 로 옮겼다(RLS·storage·auth.uid() 제외).
  설계 배경(증상 3상태, 새벽 4시 규칙 등) 참고용으로만 남겨 둔다. 여기 파일을 실행하지 말 것.
