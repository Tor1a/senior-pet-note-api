package com.oraegyeot.seniorpet.pet.photo;

import java.util.Optional;
import java.util.UUID;

/**
 * 사진 파일 저장소. MVP 는 로컬 디스크(LocalPhotoStorage),
 * 운영 단계에서 오브젝트 스토리지로 바꿀 때는 이 인터페이스의 구현만 교체한다(대표 승인 필요).
 * 경로 규칙: "<user_id>/<랜덤파일명>.<확장자>" — DB pets.photo_path 에 이 값만 저장한다.
 */
public interface PhotoStorage {

    /** 새 파일로 저장하고 경로를 돌려준다. 기존 파일을 덮어쓰지 않는다. */
    String save(UUID userId, byte[] data, ImageType type);

    /** 파일 내용. 없으면 빈 값. */
    Optional<byte[]> load(String path);

    /** 파일 삭제. 없으면 무시한다(실패해도 예외를 던지지 않고 로그만 남긴다). */
    void delete(String path);

    /**
     * 사용자 폴더(<user_id>/) 전체 삭제 — 회원 탈퇴용. 폴더가 없으면 무시하고, 실패해도 예외를 던지지 않고 로그만 남긴다.
     * 오브젝트 스토리지 구현은 "<user_id>/" 접두사 일괄 삭제로 구현해야 한다.
     */
    void deleteAllOf(UUID userId);
}
