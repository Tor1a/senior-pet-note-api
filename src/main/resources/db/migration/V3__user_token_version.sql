-- =====================================================================
-- 시니어펫 노트 — 비밀번호 변경 시 기존 토큰 무효화용 token_version (Flyway V3)
-- 근거: 계정 관리·탈퇴 기획서 3-4, docs/api-account.md
-- JWT 의 ver 클레임이 이 값과 다르면 401. ver 클레임이 없는 기존 토큰은 0 으로 본다(기본값 0 → 재로그인 불필요).
-- =====================================================================
alter table users add column token_version integer not null default 0;

comment on column users.token_version is '비밀번호를 바꿀 때마다 +1. JWT ver 클레임과 다르면 토큰을 거부한다.';
