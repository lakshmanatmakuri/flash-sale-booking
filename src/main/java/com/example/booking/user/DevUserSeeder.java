package com.example.booking.user;

import com.example.booking.user.entity.RoleName;
import com.example.booking.user.entity.User;
import com.example.booking.user.repository.RoleRepository;
import com.example.booking.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevUserSeeder implements ApplicationRunner {

    private static final String DEV_PASSWORD = "Password123!";

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder encoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed("admin@example.com", "Dev Admin", RoleName.ADMIN);
        seed("organizer@example.com", "Dev Organizer", RoleName.ORGANIZER);
    }

    private void seed(String email, String name, RoleName extraRole) {

        if (users.existsByEmail(email)) {
            return;
        }

        User u = new User();

        u.setEmail(email);
        u.setFullName(name);
        u.setPasswordHash(encoder.encode(DEV_PASSWORD));

        // Every seeded user gets USER
        u.getRoles().add(
                roles.findByName(RoleName.USER).orElseThrow()
        );

        // Then give the additional role
        u.getRoles().add(
                roles.findByName(extraRole).orElseThrow()
        );

        users.save(u);

        log.info(
                "Seeded dev user {} with role {}",
                email,
                extraRole
        );
    }
}