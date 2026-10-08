package com.oraegyeot.seniorpet.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 회원 저장소. users 는 "소유자 범위" 대상이 아닌 유일한 테이블이다
 * (인증 처리에서만 사용하고, 리소스 API 에서 직접 쓰지 않는다).
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** 토큰 검증용: 사용자가 있으면 현재 token_version, 없으면 빈 값. */
    @Query("select u.tokenVersion from User u where u.id = :id")
    Optional<Integer> findTokenVersionById(@Param("id") UUID id);

    /**
     * 비밀번호 변경(낙관적 조건): token_version 이 검증 시점 값 그대로일 때만 바꾸고 버전을 올린다.
     * 0 이면 그 사이 다른 변경/탈퇴가 있었다는 뜻. 호출하는 쪽이 트랜잭션을 연다.
     */
    @Modifying(clearAutomatically = true)
    @Query("update User u set u.passwordHash = :hash, u.tokenVersion = u.tokenVersion + 1 "
            + "where u.id = :id and u.tokenVersion = :version")
    int changePasswordIfVersion(@Param("id") UUID id, @Param("hash") String hash, @Param("version") int version);

    /** 회원 탈퇴: users 한 행 삭제(자식 테이블은 DB 의 on delete cascade). 호출하는 쪽이 트랜잭션을 연다. */
    @Modifying
    @Query("delete from User u where u.id = :id")
    int deleteUserById(@Param("id") UUID id);
}
