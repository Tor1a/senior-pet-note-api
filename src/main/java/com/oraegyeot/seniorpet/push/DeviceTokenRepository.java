package com.oraegyeot.seniorpet.push;

import com.oraegyeot.seniorpet.common.OwnedRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 기기 토큰 저장소. 모든 조회에 userId 가 들어간다(OwnedRepository 규칙). */
interface DeviceTokenRepository extends OwnedRepository<DeviceToken, UUID> {

    Optional<DeviceToken> findByTokenAndUserId(String token, UUID userId);

    /** 사용자 토큰 목록(마지막 등록이 오래된 순). 개수 제한·발송에 쓴다. */
    List<DeviceToken> findAllByUserIdOrderByLastSeenAtAscIdAsc(UUID userId);

    /** 본인 토큰 중 FCM 이 무효라고 알려 준 것 삭제 */
    @Modifying
    @Query("delete from DeviceToken d where d.userId = :userId and d.token in :tokens")
    int deleteAllByUserIdAndTokenIn(@Param("userId") UUID userId, @Param("tokens") Collection<String> tokens);

    /**
     * 보안 규칙 9번의 예외: 같은 토큰이 "다른 사용자"로 등록돼 있으면 지운다(기기 이전).
     * 한 기기에서 A 가 해제 없이 로그아웃한 뒤 B 가 등록하면 A 의 알림이 B 의 기기로 가지 않게 하기 위함.
     * DeviceTokenService.register 에서만 호출한다. 결과(지운 개수)를 응답에 드러내지 않는다.
     */
    @Modifying
    @Query("delete from DeviceToken d where d.token = :token and d.userId <> :userId")
    int deleteByTokenAndUserIdNot(@Param("token") String token, @Param("userId") UUID userId);
}
