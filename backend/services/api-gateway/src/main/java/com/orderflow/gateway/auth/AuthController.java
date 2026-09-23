package com.orderflow.gateway.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Phát JWT cho người dùng demo — THAY CHO một auth service thật (ngoài phạm vi).
 *
 * <p>Người dùng khai trong cấu hình ({@code orderflow.gateway.demo-users}),
 * mật khẩu so khớp trực tiếp. Hệ thống thật: người dùng nằm ở database của
 * auth service, mật khẩu băm bằng bcrypt/argon2, có khoá tài khoản khi sai
 * nhiều lần. Endpoint này chỉ tồn tại để có token thật mà demo và test.
 *
 * <p>Token mang claim {@code customerId} — thứ gateway sẽ gắn xuống service
 * phía sau qua header {@code X-Customer-Id}.
 */
@RestController
@RequestMapping("/auth")
@EnableConfigurationProperties(AuthController.DemoUsers.class)
public class AuthController {

    @ConfigurationProperties(prefix = "orderflow.gateway")
    public record DemoUsers(Map<String, DemoUser> demoUsers) {
        public record DemoUser(String password, UUID customerId) {}
    }

    public record LoginRequest(String username, String password) {}

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {}

    private final JwtEncoder encoder;
    private final DemoUsers users;
    private final Duration ttl;

    public AuthController(JwtEncoder encoder, DemoUsers users,
                          @Value("${orderflow.gateway.jwt.ttl:PT1H}") Duration ttl) {
        this.encoder = encoder;
        this.users = users;
        this.ttl = ttl;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest request) {
        var user = request.username() == null ? null : users.demoUsers().get(request.username());
        if (user == null || request.password() == null || !constantTimeEquals(user.password(), request.password())) {
            // Cùng một câu trả lời cho "sai user" và "sai mật khẩu" — không cho
            // kẻ tấn công dò xem username nào tồn tại.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer("orderflow-gateway")
                .subject(request.username())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("customerId", user.customerId().toString())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        return ResponseEntity.ok(new TokenResponse(token, "Bearer", ttl.toSeconds()));
    }

    /** So sánh thời gian hằng — {@code equals} thoát sớm ở ký tự sai đầu tiên, lộ thông tin qua thời gian phản hồi. */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
