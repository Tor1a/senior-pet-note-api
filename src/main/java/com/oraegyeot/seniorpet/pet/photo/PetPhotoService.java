package com.oraegyeot.seniorpet.pet.photo;

import com.oraegyeot.seniorpet.common.ApiException;
import com.oraegyeot.seniorpet.pet.Pet;
import com.oraegyeot.seniorpet.pet.PetService;
import com.oraegyeot.seniorpet.pet.PetService.PhotoChange;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 반려동물 사진 업로드·조회·삭제.
 * 모든 메서드는 petService.getOwned 로 소유를 먼저 확인한다(남의 pet = 404).
 */
@Service
public class PetPhotoService {

    /** 5MB. application.yml 의 spring.servlet.multipart.max-file-size 와 같은 값 */
    public static final long MAX_BYTES = 5L * 1024 * 1024;

    private final PetService petService;
    private final PhotoStorage storage;

    public PetPhotoService(PetService petService, PhotoStorage storage) {
        this.petService = petService;
        this.storage = storage;
    }

    public record Photo(byte[] data, String contentType) {
    }

    public Pet upload(UUID userId, UUID petId, MultipartFile file) {
        petService.getOwned(userId, petId); // 파일을 쓰기 전에 소유부터 확인
        if (file == null || file.isEmpty()) {
            throw ApiException.invalidFile("사진 파일이 비어 있습니다.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.fileTooLarge();
        }
        ImageType declared = ImageType.fromContentType(file.getContentType())
                .orElseThrow(() -> ApiException.invalidFile("jpeg, png, webp 사진만 올릴 수 있습니다."));
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (data.length > MAX_BYTES) {
            throw ApiException.fileTooLarge();
        }
        ImageType actual = ImageType.detect(data)
                .orElseThrow(() -> ApiException.invalidFile("사진 파일 내용이 올바르지 않습니다."));
        if (actual != declared) {
            throw ApiException.invalidFile("파일 형식과 내용이 일치하지 않습니다.");
        }

        String newPath = storage.save(userId, data, actual);
        PhotoChange change;
        try {
            change = petService.changePhotoPath(userId, petId, newPath);
        } catch (RuntimeException e) {
            storage.delete(newPath); // DB 반영 실패 시 새 파일 정리
            throw e;
        }
        if (change.oldPath() != null) {
            storage.delete(change.oldPath()); // 교체 시 이전 파일 삭제
        }
        return change.pet();
    }

    public Photo load(UUID userId, UUID petId) {
        Pet pet = petService.getOwned(userId, petId);
        String path = pet.getPhotoPath();
        if (path == null || !path.startsWith(userId + "/")) {
            throw ApiException.notFound();
        }
        String ext = path.substring(path.lastIndexOf('.') + 1);
        ImageType type = ImageType.fromExtension(ext).orElseThrow(ApiException::notFound);
        byte[] data = storage.load(path).orElseThrow(ApiException::notFound);
        return new Photo(data, type.contentType());
    }

    public void delete(UUID userId, UUID petId) {
        PhotoChange change = petService.changePhotoPath(userId, petId, null);
        if (change.oldPath() != null) {
            storage.delete(change.oldPath());
        }
    }
}
