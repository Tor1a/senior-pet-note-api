package com.oraegyeot.seniorpet.security;

import com.oraegyeot.seniorpet.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authorization: Bearer <token> 헤더를 검사해 로그인 사용자를 SecurityContext 에 넣는다.
 * 토큰이 없거나 잘못됐으면 아무것도 넣지 않는다 → 보호된 API 는 401 이 된다.
 * 탈퇴(삭제)한 사용자의 토큰, 비밀번호 변경 전에 발급한 토큰(token_version 불일치)도 거부한다.
 * (@Component 가 아니다: 서블릿 필터로 중복 등록되지 않도록 SecurityConfig 에서 직접 만든다.)
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length()).trim();
            // 사용자가 없거나(탈퇴) 토큰 버전이 다르면(비밀번호 변경) 인증하지 않는다
            jwtService.parse(token)
                    .filter(p -> userRepository.findTokenVersionById(p.userId())
                            .filter(v -> v == p.version()).isPresent())
                    .map(JwtService.Parsed::userId)
                    .ifPresent(userId -> {
                        var auth = new UsernamePasswordAuthenticationToken(
                                new AuthenticatedUser(userId), null, List.of());
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    });
        }
        chain.doFilter(request, response);
    }
}
