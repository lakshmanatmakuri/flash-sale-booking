package com.example.booking.auth.service;

import com.example.booking.auth.dto.AuthResponse;
import com.example.booking.auth.dto.LoginRequest;
import com.example.booking.auth.dto.RegisterRequest;
import com.example.booking.auth.security.JwtService;
import com.example.booking.common.exception.AccountNotActiveException;
import com.example.booking.common.exception.EmailAlreadyExistsException;
import com.example.booking.common.exception.InvalidCredentialsException;
import com.example.booking.user.entity.Role;
import com.example.booking.user.entity.RoleName;
import com.example.booking.user.entity.User;
import com.example.booking.user.entity.UserStatus;
import com.example.booking.user.repository.RoleRepository;
import com.example.booking.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final String dummyHash;   // used to equalize timing when the email does not exist

    public AuthService(UserRepository users, RoleRepository roles,
                       PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String email = normalize(req.email());

        // Friendly fast-path check...
        if (users.existsByEmail(email)) {
            throw new EmailAlreadyExistsException();
        }

        // Public registration ALWAYS creates a plain USER. Never accept a role from the client
        // (that would be a privilege-escalation hole). Organizers/admins are granted separately.
        Role userRole = roles.findByName(RoleName.USER)
                .orElseThrow(() -> new IllegalStateException("USER role missing: check Flyway V1"));

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setFullName(req.fullName().trim());
        user.getRoles().add(userRole);

        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // ...but the REAL protection is the unique index. Two simultaneous registrations can both
            // pass existsByEmail(); exactly one INSERT wins and the other lands here.
            throw new EmailAlreadyExistsException();
        }
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        User user = users.findByEmail(normalize(req.email())).orElse(null);

        // Always run one BCrypt comparison and return one generic error, so an attacker cannot tell
        // "unknown email" from "wrong password" by message or by response time (user enumeration).
        boolean passwordOk = passwordEncoder.matches(req.password(),
                user != null ? user.getPasswordHash() : dummyHash);
        if (user == null || !passwordOk) {
            throw new InvalidCredentialsException();
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AccountNotActiveException();
        }
        return toResponse(user);
    }

    private AuthResponse toResponse(User user) {
        return new AuthResponse(
                jwtService.generateToken(user), "Bearer", jwtService.expiresInSeconds(),
                user.getId(), user.getEmail(),
                user.getRoles().stream().map(r -> r.getName().name()).collect(Collectors.toUnmodifiableSet()));
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}