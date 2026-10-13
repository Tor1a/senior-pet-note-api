package com.oraegyeot.seniorpet.todo;

import static org.assertj.core.api.Assertions.assertThat;

import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 시각 컬럼 회귀 테스트(TimeZoneStorageTest 방식). todos.due_time(스칼라 LocalTime)이 JVM 시간대와 무관하게
 * 입력한 HH:mm 그대로 저장·응답되는지 JDBC 로 직접 확인한다.
 */
class TodoTimeZoneStorageTest extends ApiTestSupport {

    @Test
    void due_time_은_JVM_시간대와_무관하게_입력한_그대로_저장되고_응답된다() throws Exception {
        setSeoulTime(2026, 10, 12, 12, 0);
        String t = newDisposableUserToken();
        String pet = createPet(t);
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : List.of("UTC", "Asia/Seoul", "America/New_York")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                var created = createTodo(t, pet, "접종 " + zone, "2026-10-20", "16:25");
                assertThat(created.get("dueTime").asText()).as("응답 JVM " + zone).isEqualTo("16:25");
                String stored = jdbc.sql("select due_time::text from todos where id = :id")
                        .param("id", UUID.fromString(created.get("id").asText())).query(String.class).single();
                assertThat(stored).as("저장 JVM " + zone).isEqualTo("16:25:00");
                // 다시 읽어도 같다
                var listed = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/pets/" + pet + "/todos"), t, 200).get("todos");
                for (var n : listed) {
                    assertThat(n.get("dueTime").asText()).as("목록 JVM " + zone).isEqualTo("16:25");
                }
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
