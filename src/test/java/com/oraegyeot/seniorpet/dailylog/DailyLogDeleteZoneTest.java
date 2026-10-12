package com.oraegyeot.seniorpet.dailylog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import com.oraegyeot.seniorpet.ApiTestSupport;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** app.zone=America/New_York 일 때 삭제 가능한 기록 날짜가 뉴욕 기준인지. */
@TestPropertySource(properties = "app.zone=America/New_York")
class DailyLogDeleteZoneTest extends ApiTestSupport {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private void setNy(int d, int h, int m) {
        clock.set(LocalDateTime.of(2026, 10, d, h, m).atZone(NY).toInstant());
    }

    @Test
    void 뉴욕_04시_컷오프로_삭제_가능한_날짜가_정해진다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        String base = "/api/pets/" + pet + "/daily-logs/";
        setNy(8, 3, 59);
        call(jsonPut(base + "2026-10-07", Map.of("symptoms", List.of(), "symptomsNone", true)), t, 200);
        assertThat(call(delete(base + "2026-10-08"), t, 400).get("code").asText()).isEqualTo("INVALID_RECORD_DATE");
        assertThat(count(pet)).isEqualTo(1);
        call(delete(base + "2026-10-07"), t, 204);
        assertThat(count(pet)).isZero();

        call(jsonPut(base + "2026-10-07", Map.of("symptoms", List.of(), "symptomsNone", true)), t, 200);
        setNy(8, 4, 0);
        assertThat(call(delete(base + "2026-10-07"), t, 400).get("code").asText()).isEqualTo("INVALID_RECORD_DATE");
        call(delete(base + "2026-10-08"), t, 204);
        assertThat(count(pet)).isEqualTo(1);
    }

    private int count(String pet) {
        return jdbc.sql("select count(*) from daily_logs where pet_id = :p").param("p", UUID.fromString(pet))
                .query(Integer.class).single();
    }
}
