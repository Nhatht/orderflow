package com.orderflow.gateway.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Xác thực JWT tại RÌA hệ thống — gateway là nơi DUY NHẤT kiểm tra token.
 *
 * <p><b>Ký bằng HS256 (khoá bí mật dùng chung)</b> — đơn giản hoá có chủ đích.
 * Ở đây gateway vừa phát vừa kiểm token nên một khoá là đủ. Hệ thống thật tách
 * ra một auth server (Keycloak, Auth0...) ký bằng RS256: auth server giữ khoá
 * RIÊNG để ký, mọi service chỉ cần khoá CÔNG KHAI (qua JWKS) để kiểm. Lộ khoá
 * công khai không sao; lộ khoá HS256 là ai cũng phát được token.
 *
 * <p>Khoá lấy từ cấu hình; giá trị trong {@code application.yml} chỉ dành cho
 * dev. Production phải đưa qua biến môi trường / secret manager.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecretKey jwtSigningKey(@Value("${orderflow.gateway.jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            // HS256 cần khoá ≥ 256 bit; khoá ngắn hơn bị brute-force được.
            throw new IllegalStateException("orderflow.gateway.jwt.secret must be at least 32 bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    public ReactiveJwtDecoder jwtDecoder(SecretKey key) {
        // Kiểm chữ ký + hạn dùng (exp) — token hết hạn hoặc bị sửa đều trả 401.
        return NimbusReactiveJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    /**
     * {@code /auth/**} mở (để đăng nhập), health mở (cho load balancer), còn lại
     * phải có token hợp lệ. CSRF tắt: API không dùng cookie phiên, token đi trong
     * header {@code Authorization} nên không bị trình duyệt tự gửi kèm.
     */
    @Bean
    public SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(auth -> auth
                        .pathMatchers("/auth/**", "/actuator/health").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .build();
    }
}
