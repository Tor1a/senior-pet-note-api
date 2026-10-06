package com.oraegyeot.seniorpet.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 회원 저장소. users 는 "소유자 범위" 대상이 아닌 유일한 테이블이다
 * (인증 처리에서만 사용하고, 리소스 API 에서 직접 쓰지 않는다).
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
