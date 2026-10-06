package com.oraegyeot.seniorpet.common;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

/**
 * 사용자 소유 리소스(pets, medications, med_logs, daily_logs, push_subscriptions, events) 저장소의 공통 부모.
 *
 * RLS 가 없으므로 "본인 데이터만"은 이 인터페이스로 보장한다.
 * - JpaRepository 를 상속하지 않는다: findById / findAll / deleteById 처럼
 *   user_id 조건이 없는 메서드가 아예 노출되지 않게 하기 위함이다.
 * - 조회·수정·삭제는 반드시 userId 를 함께 받는 메서드만 쓴다.
 * - 하위 인터페이스에 메서드를 추가할 때도 이름에 "AndUserId" / "ByUserId" 가 들어가야 한다.
 *
 * @param <T>  엔티티 타입 (user_id 컬럼을 가진 엔티티)
 * @param <ID> 기본키 타입
 */
@NoRepositoryBean
public interface OwnedRepository<T, ID> extends Repository<T, ID> {

    /** 본인 리소스 1건. 남의 것이거나 없으면 빈 값 → 서비스에서 404 로 응답. */
    Optional<T> findByIdAndUserId(ID id, UUID userId);

    List<T> findAllByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    /** 저장. 호출 전에 엔티티의 userId 가 로그인 사용자 id 인지 서비스에서 보장해야 한다. */
    <S extends T> S save(S entity);

    <S extends T> S saveAndFlush(S entity);

    /** 삭제. 반드시 findByIdAndUserId 로 찾은 엔티티만 넘긴다. */
    void delete(T entity);
}
