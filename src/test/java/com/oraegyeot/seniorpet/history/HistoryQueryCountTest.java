package com.oraegyeot.seniorpet.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.oraegyeot.seniorpet.ApiTestSupport;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** 지난 기록 조회의 DB 쿼리 수 실측(일정 이력 도입 후에도 늘지 않았는지). 인증 필터의 사용자 조회는 서비스 쿼리와 분리해 센다. */
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class HistoryQueryCountTest extends ApiTestSupport {

    @Autowired
    private EntityManagerFactory emf;

    @Test
    void 약이_있어도_지난_기록_조회는_서비스_쿼리_4회() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 1, 12, 0);
        createMedication(t, pet, List.of("08:00", "20:00"));
        createMedication(t, pet, List.of("09:00"));
        setSeoulTime(2026, 10, 8, 12, 0);
        call(get("/api/pets/" + pet + "/daily-logs"), t, 200); // 워밍업
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        call(get("/api/pets/" + pet + "/daily-logs?from=2026-09-09&to=2026-10-08"), t, 200);
        long total = stats.getPrepareStatementCount();
        // 인증 필터가 읽는 사용자 1회 + 서비스 4회(pet 소유 확인, 일정 버전, daily_logs, med_logs)
        assertThat(total).isEqualTo(5L);
    }
}
