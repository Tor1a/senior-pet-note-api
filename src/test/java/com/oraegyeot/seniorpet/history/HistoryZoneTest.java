package com.oraegyeot.seniorpet.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** app.zone=America/New_York 일 때 기록 날짜·history 기본 기간·투약 체크 날짜가 뉴욕 기준인지. */
@TestPropertySource(properties = "app.zone=America/New_York")
class HistoryZoneTest extends ApiTestSupport {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private void setNy(int d, int h, int m) {
        clock.set(LocalDateTime.of(2026, 10, d, h, m).atZone(NY).toInstant());
    }

    @Test
    void 뉴욕_04시_컷오프로_기본_기간과_체크_날짜가_정해진다() throws Exception {
        String t = newDisposableUserToken();
        String pet = createPet(t);
        setNy(8, 3, 59);
        JsonNode res = call(get("/api/pets/" + pet + "/daily-logs"), t, 200);
        assertThat(res.get("to").asText()).isEqualTo("2026-10-07");
        assertThat(res.get("from").asText()).isEqualTo("2026-09-08");
        assertThat(res.get("days")).hasSize(30);

        String med = createMedication(t, pet, java.util.List.of("08:00"));
        JsonNode log = call(jsonPost("/api/med-logs", Map.of("medicationId", med, "scheduledTime", "08:00")), t, 201);
        assertThat(log.get("recordDate").asText()).isEqualTo("2026-10-07");

        setNy(8, 4, 0);
        res = call(get("/api/pets/" + pet + "/daily-logs"), t, 200);
        assertThat(res.get("to").asText()).isEqualTo("2026-10-08");
        assertThat(res.get("from").asText()).isEqualTo("2026-09-09");
    }
}
