package com.example.booking.support;

import com.example.booking.auth.security.JwtService;
import com.example.booking.user.entity.RoleName;
import com.example.booking.user.entity.User;
import com.example.booking.user.repository.RoleRepository;
import com.example.booking.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Test helper: creates a user with the given role directly in the DB and returns a ready-to-use JWT. */
@Component
public class TestUsers {

    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwt;

    public String tokenFor(RoleName extraRole) {
        User u = new User();
        u.setEmail("t-" + UUID.randomUUID() + "@example.com");
        u.setFullName("Test " + extraRole);
        u.setPasswordHash(encoder.encode("Password123!"));
        u.getRoles().add(roles.findByName(RoleName.USER).orElseThrow());
        if (extraRole != RoleName.USER) {
            u.getRoles().add(roles.findByName(extraRole).orElseThrow());
        }
        users.save(u);
        return jwt.generateToken(u);
    }
}
