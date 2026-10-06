package com.oraegyeot.seniorpet.pet.photo;

import com.oraegyeot.seniorpet.pet.PetDtos.PetResponse;
import com.oraegyeot.seniorpet.pet.photo.PetPhotoService.Photo;
import com.oraegyeot.seniorpet.security.CurrentUserId;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 반려동물 사진 API (docs/api-today.md 1절). 정적 공개 경로 없이 로그인 API 로만 접근한다. */
@RestController
@RequestMapping("/api/pets/{id}/photo")
public class PetPhotoController {

    private final PetPhotoService photoService;

    public PetPhotoController(PetPhotoService photoService) {
        this.photoService = photoService;
    }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PetResponse upload(@CurrentUserId UUID userId, @PathVariable UUID id,
                              @RequestPart("file") MultipartFile file) {
        return PetResponse.from(photoService.upload(userId, id, file));
    }

    @GetMapping
    public ResponseEntity<byte[]> get(@CurrentUserId UUID userId, @PathVariable UUID id) {
        Photo photo = photoService.load(userId, id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.contentType()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(photo.data());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
        photoService.delete(userId, id);
    }
}
