package com.example.booking.user.repository;

import com.example.booking.user.entity.Role;
import com.example.booking.user.entity.RoleName;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Short> {

    Optional<Role> findByName(RoleName name);
}