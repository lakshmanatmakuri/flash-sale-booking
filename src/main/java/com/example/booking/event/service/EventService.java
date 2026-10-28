package com.example.booking.event.service;

import com.example.booking.auth.security.AuthenticatedUser;
import com.example.booking.common.dto.PageResponse;
import com.example.booking.common.exception.BadRequestException;
import com.example.booking.common.exception.ConflictException;
import com.example.booking.common.exception.ForbiddenException;
import com.example.booking.common.exception.ResourceNotFoundException;
import com.example.booking.event.dto.CreateEventRequest;
import com.example.booking.event.dto.EventResponse;
import com.example.booking.event.dto.EventSummary;
import com.example.booking.event.entity.Event;
import com.example.booking.event.entity.EventStatus;
import com.example.booking.event.repository.EventRepository;
import com.example.booking.seat.service.EventSeatService;
import com.example.booking.venue.service.VenueService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EventService {

    private static final Set<EventStatus> PUBLIC_STATUSES =
            EnumSet.of(EventStatus.PUBLISHED, EventStatus.ON_SALE, EventStatus.SOLD_OUT);
    private static final String CURRENCY = "INR";   // single currency in V1

    private final EventRepository events;
    private final VenueService venueService;
    private final EventSeatService eventSeatService;

    /** One transaction: the event and ALL of its seat inventory exist together, or not at all. */
    @Transactional
    public EventResponse create(AuthenticatedUser organizer, CreateEventRequest req) {
        if (!req.saleStartsAt().isBefore(req.saleEndsAt())) {
            throw new BadRequestException("saleEndsAt must be after saleStartsAt");
        }
        if (req.saleEndsAt().isAfter(req.startsAt())) {
            throw new BadRequestException("Ticket sales must end before the event starts");
        }
        if (venueService.requireSeatCount(req.venueId()) == 0) {
            throw new BadRequestException("The venue has no seats");
        }

        Event event = new Event();
        event.setVenueId(req.venueId());
        event.setOrganizerId(organizer.id());
        event.setName(req.name().trim());
        event.setDescription(req.description());
        event.setStartsAt(req.startsAt());
        event.setSaleStartsAt(req.saleStartsAt());
        event.setSaleEndsAt(req.saleEndsAt());
        if (req.maxSeatsPerUser() != null) {
            event.setMaxSeatsPerUser(req.maxSeatsPerUser());
        }
        // Flush first: the native INSERT ... SELECT below needs the event row to already exist (FK).
        events.saveAndFlush(event);

        int created = eventSeatService.generateInventory(
                event.getId(), event.getVenueId(), req.basePrice(), CURRENCY);
        return EventResponse.from(event, created);
    }

    @Transactional
    public EventResponse publish(AuthenticatedUser user, UUID eventId) {
        Event event = load(eventId);
        if (!canManage(user, event)) {
            throw new ForbiddenException("You do not manage this event");
        }
        if (event.getStatus() != EventStatus.DRAFT) {
            throw new ConflictException("INVALID_EVENT_STATE", "Only DRAFT events can be published");
        }
        event.setStatus(EventStatus.PUBLISHED);   // dirty checking writes the UPDATE at commit
        return EventResponse.from(event, eventSeatService.countAvailable(eventId));
    }

    /** `user` may be null (anonymous caller on a public endpoint). */
    @Transactional(readOnly = true)
    public EventResponse get(AuthenticatedUser user, UUID eventId) {
        Event event = loadVisible(user, eventId);
        return EventResponse.from(event, eventSeatService.countAvailable(eventId));
    }

    @Transactional(readOnly = true)
    public void requireVisible(AuthenticatedUser user, UUID eventId) {
        loadVisible(user, eventId);
    }

    @Transactional(readOnly = true)
    public PageResponse<EventSummary> list(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);   // cap page size: never let a client ask for 1M rows
        return PageResponse.of(events
                .findByStatusIn(PUBLIC_STATUSES, PageRequest.of(safePage, safeSize, Sort.by("startsAt")))
                .map(EventSummary::from));
    }

    private Event load(UUID id) {
        return events.findById(id).orElseThrow(() -> new ResourceNotFoundException("Event", id));
    }

    /** Drafts are invisible (404, not 403) to everyone except their organizer and admins. */
    private Event loadVisible(AuthenticatedUser user, UUID id) {
        Event event = load(id);
        if (!PUBLIC_STATUSES.contains(event.getStatus()) && !canManage(user, event)) {
            throw new ResourceNotFoundException("Event", id);
        }
        return event;
    }

    private boolean canManage(AuthenticatedUser user, Event event) {
        return user != null && (user.isAdmin() || user.id().equals(event.getOrganizerId()));
    }
}