package com.oraegyeot.seniorpet.pet;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** pets API 소유자 격리 테스트 — RLS 대신 API 계층이 "본인 데이터만"을 보장하는지 검증한다. */
class PetOwnershipTest extends ApiTestSupport {

    private String createPet(String token, String name) throws Exception {
        String body = mvc.perform(jsonPost("/api/pets", Map.of("name", name, "species", "dog", "birthYear", 2012))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.species").value("dog"))
                .andExpect(jsonPath("$.birthYear").value(2012))
                .andExpect(jsonPath("$.hasPhoto").value(false))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    @Test
    void 다른_사용자의_pet_조회는_404() throws Exception {
        String alice = newUserToken();
        String bob = newUserToken();
        String alicePetId = createPet(alice, "초코");

        // 본인은 조회 가능
        mvc.perform(get("/api/pets/" + alicePetId).header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alicePetId));

        // 남은 404 (403 이 아니라 404: 존재 여부를 알려주지 않는다)
        mvc.perform(get("/api/pets/" + alicePetId).header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 목록에는_본인_pet만_보인다() throws Exception {
        String alice = newUserToken();
        String bob = newUserToken();
        String alicePetId = createPet(alice, "초코");
        String bobPetId = createPet(bob, "나비");

        mvc.perform(get("/api/pets").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(alicePetId));
        mvc.perform(get("/api/pets").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(bobPetId));
    }

    @Test
    void 본문의_userId는_무시되고_로그인_사용자로_저장된다() throws Exception {
        String alice = newUserToken();
        String bob = newUserToken();
        String bobUserId = mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andReturn().getResponse().getContentAsString();
        bobUserId = objectMapper.readTree(bobUserId).get("id").asText();

        Map<String, Object> body = new HashMap<>();
        body.put("name", "몰래");
        body.put("species", "cat");
        body.put("userId", bobUserId); // 공격 시도: 남의 id 를 넣어 봄
        mvc.perform(jsonPost("/api/pets", body).header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isCreated());

        // bob 의 목록에는 나타나지 않고, alice 의 목록에만 있다
        mvc.perform(get("/api/pets").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/pets").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("몰래"));
    }

    @Test
    void 없는_pet과_잘못된_id는_404() throws Exception {
        String alice = newUserToken();
        mvc.perform(get("/api/pets/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/pets/not-a-uuid").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 로그인없이_pets는_401() throws Exception {
        mvc.perform(get("/api/pets")).andExpect(status().isUnauthorized());
    }

    @Test
    void MVP는_1마리만_등록_두번째는_409() throws Exception {
        String alice = newUserToken();
        createPet(alice, "초코");
        mvc.perform(jsonPost("/api/pets", Map.of("name", "둘째", "species", "cat"))
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PET_LIMIT_REACHED"));
    }

    @Test
    void 잘못된_종은_400() throws Exception {
        String alice = newUserToken();
        mvc.perform(jsonPost("/api/pets", Map.of("name", "꼬꼬", "species", "bird"))
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
