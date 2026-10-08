package com.oraegyeot.seniorpet.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/**
 * 시각 컬럼 회귀 테스트. med_logs.scheduled_time(스칼라 LocalTime)이 JVM 시간대와 무관하게 요청한 값 그대로 저장되는지
 * JDBC 로 직접 확인한다(과거 hibernate.jdbc.time_zone=UTC + KST JVM 에서 16:25 → 07:25 로 저장되던 문제).
 */
class TimeZoneStorageTest extends ApiTestSupport {

    @Test
    void 테스트_JVM_시간대는_UTC() {
        assertThat(TimeZone.getDefault().getID()).isEqualTo("UTC");
    }

    @Test
    void scheduled_time_은_JVM_시간대와_무관하게_입력한_그대로_저장된다() throws Exception {
        String t = newDisposableUserToken();
        String pet = createPet(t);
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : List.of("UTC", "Asia/Seoul", "America/New_York")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                String med = createMedication(t, pet, List.of("16:25"));
                call(jsonPost("/api/med-logs", Map.of("medicationId", med, "scheduledTime", "16:25")), t, 201);
                String stored = jdbc.sql("select scheduled_time::text from med_logs where medication_id = :m")
                        .param("m", java.util.UUID.fromString(med)).query(String.class).single();
                assertThat(stored).as("JVM " + zone).isEqualTo("16:25:00");
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void 투약_시각_배열도_입력한_그대로_저장된다() throws Exception {
        String t = newDisposableUserToken();
        String pet = createPet(t);
        String med = createMedication(t, pet, List.of("00:30", "16:25"));
        String stored = jdbc.sql("select times::text from medications where id = :m")
                .param("m", java.util.UUID.fromString(med)).query(String.class).single();
        assertThat(stored).isEqualTo("{00:30:00,16:25:00}");
    }
}
