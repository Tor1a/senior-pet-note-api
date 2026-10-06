package com.oraegyeot.seniorpet.pet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 반려동물 수정·사진 API 테스트 (docs/api-today.md 1절). */
class PetPhotoApiTest extends ApiTestSupport {

    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4};
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 5, 6, 7, 8};
    static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 9, 9};

    @Value("${app.photo.dir}")
    String photoDir;

    private MockHttpServletRequestBuilder upload(String petId, String contentType, byte[] data) {
        return multipart("/api/pets/" + petId + "/photo")
                .file(new MockMultipartFile("file", "photo.bin", contentType, data))
                .with(r -> {
                    r.setMethod("PUT");
                    return r;
                });
    }

    private long fileCount(String userId) throws Exception {
        Path dir = Path.of(photoDir, userId);
        if (!Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.count();
        }
    }

    @Test
    void 사진_업로드_조회_교체_삭제() throws Exception {
        JsonNode user = signup(randomEmail(), PASSWORD);
        String t = user.get("accessToken").asText();
        String userId = user.get("user").get("id").asText();
        String pet = createPet(t);

        JsonNode res = call(upload(pet, "image/jpeg", JPEG), t, 200);
        assertThat(res.get("hasPhoto").asBoolean()).isTrue();
        assertThat(res.has("photoPath")).isFalse(); // 경로는 노출하지 않는다
        mvc.perform(get("/api/pets/" + pet + "/photo").header(HttpHeaders.AUTHORIZATION, bearer(t)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(JPEG));
        assertThat(fileCount(userId)).isEqualTo(1);

        // 교체: 이전 파일은 지워지고 새 파일 1개만 남는다
        call(upload(pet, "image/png", PNG), t, 200);
        mvc.perform(get("/api/pets/" + pet + "/photo").header(HttpHeaders.AUTHORIZATION, bearer(t)))
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(PNG));
        assertThat(fileCount(userId)).isEqualTo(1);
        call(upload(pet, "image/webp", WEBP), t, 200);
        assertThat(fileCount(userId)).isEqualTo(1);

        // 삭제
        call(delete("/api/pets/" + pet + "/photo"), t, 204);
        assertThat(fileCount(userId)).isZero();
        assertThat(call(get("/api/pets/" + pet), t, 200).get("hasPhoto").asBoolean()).isFalse();
        call(get("/api/pets/" + pet + "/photo"), t, 404);
        call(delete("/api/pets/" + pet + "/photo"), t, 204); // 없는 사진 삭제도 204
    }

    @Test
    void 형식_오류는_400_INVALID_FILE() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        // 허용하지 않는 Content-Type
        assertThat(call(upload(pet, "image/gif", "GIF89a....".getBytes()), t, 400).get("code").asText())
                .isEqualTo("INVALID_FILE");
        assertThat(call(upload(pet, "text/plain", "hello".getBytes()), t, 400).get("code").asText())
                .isEqualTo("INVALID_FILE");
        // Content-Type 은 이미지인데 내용이 다름(매직 바이트 불일치)
        assertThat(call(upload(pet, "image/png", JPEG), t, 400).get("code").asText()).isEqualTo("INVALID_FILE");
        assertThat(call(upload(pet, "image/jpeg", "<script>".getBytes()), t, 400).get("code").asText())
                .isEqualTo("INVALID_FILE");
        // 빈 파일, file 필드 없음
        assertThat(call(upload(pet, "image/jpeg", new byte[0]), t, 400).get("code").asText()).isEqualTo("INVALID_FILE");
        JsonNode noFile = call(multipart("/api/pets/" + pet + "/photo")
                .file(new MockMultipartFile("other", "a.jpg", "image/jpeg", JPEG))
                .with(r -> {
                    r.setMethod("PUT");
                    return r;
                }), t, 400);
        assertThat(noFile.get("code").asText()).isEqualTo("INVALID_FILE");
        assertThat(call(get("/api/pets/" + pet), t, 200).get("hasPhoto").asBoolean()).isFalse();
    }

    @Test
    void 오MB_초과는_413_FILE_TOO_LARGE() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        byte[] big = Arrays.copyOf(JPEG, 5 * 1024 * 1024 + 1);
        assertThat(call(upload(pet, "image/jpeg", big), t, 413).get("code").asText()).isEqualTo("FILE_TOO_LARGE");
        // 정확히 5MB 는 허용
        byte[] exact = Arrays.copyOf(JPEG, 5 * 1024 * 1024);
        call(upload(pet, "image/jpeg", exact), t, 200);
    }

    @Test
    void pet_수정() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        JsonNode updated = call(jsonPut("/api/pets/" + pet,
                Map.of("name", "초코2", "species", "cat", "birthYear", 2010, "conditions", "신부전")), t, 200);
        assertThat(updated.get("name").asText()).isEqualTo("초코2");
        assertThat(updated.get("species").asText()).isEqualTo("cat");
        assertThat(updated.get("birthYear").asInt()).isEqualTo(2010);
        assertThat(updated.get("conditions").asText()).isEqualTo("신부전");
        call(jsonPut("/api/pets/" + pet, Map.of("name", "", "species", "dog")), t, 400);
    }
}
