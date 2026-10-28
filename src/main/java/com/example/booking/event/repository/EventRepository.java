package com.example.booking.event.repository;

import com.example.booking.event.entity.Event;
import com.example.booking.event.entity.EventStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID> {
    Page<Event> findByStatusIn(Collection<EventStatus> statuses, Pageable pageable);
}
