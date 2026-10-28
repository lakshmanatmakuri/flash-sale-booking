package com.example.booking.auth.security;

import com.example.booking.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class JwtService {

    private final SecretKey key;
    private final JwtParser parser;
    private final Duration ttl;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-minutes}") long expirationMinutes) {

        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);

        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret must be at least 32 bytes (256 bits) for HS256"
            );
        }

        this.key = Keys.hmacShaKeyFor(bytes);

        this.parser = Jwts.parser()
                .verifyWith(key)
                .build();

        this.ttl = Duration.ofMinutes(expirationMinutes);
    }

    public String generateToken(User user) {

        Instant now = Instant.now();

        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim(
                        "roles",
                        user.getRoles()
                                .stream()
                                .map(role -> role.getName().name())
                                .toList()
                )
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /**
     * Verifies signature and expiry.
     * Throws JwtException / IllegalArgumentException if invalid.
     */
    public AuthenticatedUser parse(String token) {

        Claims claims = parser
                .parseSignedClaims(token)
                .getPayload();

        List<?> raw = claims.get("roles", List.class);

        Set<String> roles = raw == null
                ? Set.of()
                : raw.stream()
                .map(String::valueOf)
                .collect(Collectors.toUnmodifiableSet());

        return new AuthenticatedUser(
                UUID.fromString(claims.getSubject()),
                claims.get("email", String.class),
                roles
        );
    }

    public long expiresInSeconds() {
        return ttl.toSeconds();
    }
}