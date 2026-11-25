package com.moyeolog.moyelog_BE.security;

import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    public static final String UNAUTHORIZED_MESSAGE = "로그인이 필요하거나 로그인이 만료됐어요. 다시 로그인해 주세요.";
    public static final String FORBIDDEN_MESSAGE = "접근 권한이 없어요.";
    public static final String REJECTED_MESSAGE = "요청 주소가 올바르지 않아요.";

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * 허용할 출처. 쉼표로 여러 개 지정 (환경변수 CORS_ALLOWED_ORIGINS).
     * 이 값에 적힌 출처만 허용한다. 비워 두면 다른 출처의 요청은 모두 막는다(같은 도메인의 nginx 경유 요청은 영향 없음).
     */
    @Value("${cors.allowed-origins:}")
    private List<String> allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                // 컨테이너 오류 화면(sendError → /error)은 원래 상태 코드를 그대로 보여 준다(401 로 덮어쓰지 않음).
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                // 비동기 요청(실시간 스트림 /api/events)이 끝날 때의 재디스패치. 첫 요청에서 이미 인증했고,
                // JWT 필터는 재디스패치에서 다시 돌지 않으므로 여기서 막으면 닫힌 스트림에 401 을 쓰려 한다.
                .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                .requestMatchers("/api/auth/**", "/uploads/**", "/api/search-image/**").permitAll()
                // 이메일 가입·인증·비밀번호 찾기(로그인 전 화면에서 부른다). 계정 유무를 드러내지 않게 서비스가 처리한다.
                .requestMatchers("/api/account/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, authException) ->
                        JsonErrorWriter.write(response, HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MESSAGE))
                .accessDeniedHandler((request, response, accessDeniedException) ->
                        JsonErrorWriter.write(response, HttpStatus.FORBIDDEN, FORBIDDEN_MESSAGE))
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 방화벽(StrictHttpFirewall)이 거부한 요청(경로에 %25·%2F·%00·../ 등)을 400 JSON 으로 돌려준다.
     * 기본 처리기는 sendError(400) 를 하는데, 그 오류 디스패치가 인증 단계에 걸려 401 로 바뀌었다(QA02-X01).
     */
    @Bean
    public RequestRejectedHandler requestRejectedHandler() {
        return (request, response, ex) ->
                JsonErrorWriter.write(response, HttpStatus.BAD_REQUEST, REJECTED_MESSAGE);
    }

    /** 비밀번호 해시(BCrypt). 이메일 가입·비밀번호 로그인이 함께 쓴다. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 폼 로그인·HTTP Basic 을 쓰지 않으므로 사용자 저장소가 필요 없다.
     * 이 빈이 없으면 Spring Boot 가 인메모리 사용자를 만들고 생성된 비밀번호를 로그에 남긴다.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("not supported");
        };
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = allowedOrigins == null ? List.of() : allowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .distinct()
                .toList();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
