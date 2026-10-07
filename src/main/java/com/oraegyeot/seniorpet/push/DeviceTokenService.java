package com.oraegyeot.seniorpet.push;

import com.oraegyeot.seniorpet.common.ApiException;
import com.oraegyeot.seniorpet.push.DeviceTokenDtos.DeviceRequest;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기기 토큰 등록·해제. PetService 와 같은 소유자 검증 패턴(첫 파라미터 userId, 남의 것 404).
 * 예외(보안 규칙 9번): register 의 "다른 사용자의 같은 토큰 삭제"는 이 클래스에서만 한다.
 */
@Service
public class DeviceTokenService {

    /** 사용자당 최대 토큰 수. 넘치면 last_seen_at 이 가장 오래된 것부터 지운다(오류 아님). */
    public static final int MAX_TOKENS_PER_USER = 10;

    private final DeviceTokenRepository deviceTokenRepository;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final JdbcClient jdbc;

    public DeviceTokenService(DeviceTokenRepository deviceTokenRepository, Clock clock,
                              PlatformTransactionManager txManager, JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.deviceTokenRepository = deviceTokenRepository;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 등록(upsert). 같은 사용자의 같은 토큰이면 last_seen_at 갱신,
     * 다른 사용자에게 등록된 토큰이면 그 행을 지우고 현재 사용자로 새로 만든다(응답으로 드러내지 않음).
     */
    public DeviceToken register(UUID userId, DeviceRequest req) {
        try {
            return tx.execute(s -> save(userId, req.token(), req.platform()));
        } catch (DataIntegrityViolationException e) {
            // 같은 토큰 첫 등록이 동시에 들어와 unique 제약에 걸린 경우: 생긴 행을 갱신한다
            return tx.execute(s -> save(userId, req.token(), req.platform()));
        }
    }

    private DeviceToken save(UUID userId, String token, String platform) {
        // 같은 토큰의 동시 등록을 직렬화한다(트랜잭션 끝까지 유지되는 advisory lock). 락을 얻은 뒤의 조회는 앞선 커밋을 본다
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:token, 0))").param("token", token)
                .query().singleRow();
        var existing = deviceTokenRepository.findByTokenAndUserId(token, userId);
        if (existing.isPresent()) {
            DeviceToken d = existing.get();
            d.touch(platform, clock.instant());
            return deviceTokenRepository.saveAndFlush(d);
        }
        deviceTokenRepository.deleteByTokenAndUserIdNot(token, userId);
        List<DeviceToken> mine = deviceTokenRepository.findAllByUserIdOrderByLastSeenAtAscIdAsc(userId);
        for (int i = 0; i <= mine.size() - MAX_TOKENS_PER_USER; i++) {
            deviceTokenRepository.delete(mine.get(i)); // 오래된 것부터 지워 새 토큰 자리를 만든다
        }
        return deviceTokenRepository.saveAndFlush(new DeviceToken(userId, token, platform, clock.instant()));
    }

    /** 해제. 남의 것·없는 것 404. */
    @Transactional
    public void unregister(UUID userId, UUID deviceId) {
        DeviceToken d = deviceTokenRepository.findByIdAndUserId(deviceId, userId).orElseThrow(ApiException::notFound);
        deviceTokenRepository.delete(d);
    }

    /** 발송 대상 토큰 값 목록(알림 발송 작업용) */
    @Transactional(readOnly = true)
    public List<String> tokensOf(UUID userId) {
        return deviceTokenRepository.findAllByUserIdOrderByLastSeenAtAscIdAsc(userId).stream()
                .map(DeviceToken::getToken).toList();
    }

    /** FCM 이 무효라고 알려 준 본인 토큰 삭제(알림 발송 작업용) */
    @Transactional
    public void removeInvalid(UUID userId, Collection<String> tokens) {
        if (!tokens.isEmpty()) {
            deviceTokenRepository.deleteAllByUserIdAndTokenIn(userId, tokens);
        }
    }
}
