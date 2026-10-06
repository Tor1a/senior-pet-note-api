-- =====================================================================
-- 시니어펫 노트 — 반려동물 사진 저장소 (M2 사진 1장, 선택 기능)
--
-- - 비공개 버킷 "pet-photos". 파일 경로 규칙: <user_id>/<파일명>
--   예: 3f1c.../profile.jpg  → pets.photo_path 에 이 경로를 저장
-- - 첫 번째 폴더명이 본인 user_id 인 파일만 읽기/쓰기/삭제 가능.
-- - 화면 표시는 createSignedUrl() 로 만든 임시 URL 을 사용한다.
-- - 범위 축소 1순위가 "사진 업로드 제외"이므로, 사진 기능을 빼면
--   이 파일은 적용하지 않아도 된다(다른 마이그레이션과 독립적).
-- =====================================================================

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('pet-photos', 'pet-photos', false, 2097152,            -- 2MB
        array['image/jpeg', 'image/png', 'image/webp'])
on conflict (id) do nothing;

create policy "pet-photos: 본인 조회" on storage.objects
  for select to authenticated
  using (bucket_id = 'pet-photos'
         and (storage.foldername(name))[1] = (select auth.uid())::text);

create policy "pet-photos: 본인 업로드" on storage.objects
  for insert to authenticated
  with check (bucket_id = 'pet-photos'
              and (storage.foldername(name))[1] = (select auth.uid())::text);

create policy "pet-photos: 본인 수정" on storage.objects
  for update to authenticated
  using (bucket_id = 'pet-photos'
         and (storage.foldername(name))[1] = (select auth.uid())::text)
  with check (bucket_id = 'pet-photos'
              and (storage.foldername(name))[1] = (select auth.uid())::text);

create policy "pet-photos: 본인 삭제" on storage.objects
  for delete to authenticated
  using (bucket_id = 'pet-photos'
         and (storage.foldername(name))[1] = (select auth.uid())::text);
