package com.oraegyeot.seniorpet.security;

import java.util.UUID;

/** SecurityContext 에 들어가는 로그인 사용자 정보(principal). 토큰에서 검증된 id 만 담는다. */
public record AuthenticatedUser(UUID id) {
}
