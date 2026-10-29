package com.example.booking.hold.service;

import com.example.booking.common.exception.BadRequestException;
import com.example.booking.common.exception.ConflictException;
import com.example.booking.common.exception.HoldExpiredException;
import com.example.booking.common.exception.ResourceNotFoundException;
import com.example.booking.common.exception.SeatNotAvailableException;
import com.example.booking.common.idempotency.IdempotencyService;
import com.example.booking.event.dto.SaleInfo;
import com.example.booking.event.service.EventService;
import com.example.booking.hold.HoldProperties;
import com.example.booking.hold.dto.ConvertedHold;
import com.example.booking.hold.dto.CreateHoldRequest;
import com.example.booking.hold.dto.HoldResponse;
import com.example.booking.hold.entity.HoldStatus;
import com.example.booking.hold.entity.SeatHold;
import com.example.booking.hold.repository.SeatHoldDao;
import com.example.booking.hold.repository.SeatHoldRepository;
import com.example.booking.seat.dto.SeatPrice;
import com.example.booking.seat.entity.SeatStatus;
import com.example.booking.seat.service.EventSeatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HoldService {

    private final EventService eventService;
    private final EventSeatService eventSeatService;
    private final SeatHoldDao dao;
    private final SeatHoldRepository holds;
    private final HoldProperties props;

    /**
     * ALL-OR-NOTHING hold of 1..N seats in ONE transaction. If any seat is taken, the exception rolls back the
     * seats already claimed in this call, so a user never ends up with half of what they asked for.
     * (No external calls in here: it is a short, pure-database transaction.)
     */
    @Transactional
    public List<HoldResponse> createHolds(UUID userId, CreateHoldRequest req) {
        List<UUID> requested = req.eventSeatIds();
        if (new HashSet<>(requested).size() != requested.size()) {
            throw new BadRequestException("eventSeatIds must not contain duplicates");
        }
        SaleInfo sale = eventService.requireOpenForSale(req.eventId());

        // Per-user limit, made race-proof: parallel requests from this user queue up here, so each one sees
        // the previous one's committed holds when it counts.
        dao.lockUserForEvent(userId, req.eventId());
        int alreadyHeld = holds.countByUserIdAndEventIdAndStatus(userId, req.eventId(), HoldStatus.ACTIVE);
        if (alreadyHeld + requested.size() > sale.maxSeatsPerUser()) {
            throw new ConflictException("HOLD_LIMIT_EXCEEDED", "At most " + sale.maxSeatsPerUser()
                    + " seats per user for this event (you currently hold " + alreadyHeld + ")");
        }

        // DEADLOCK PREVENTION: every transaction locks seats in the SAME global order (sorted by id), regardless
        // of the order the client sent them. Without it: A locks s1 then wants s2, B locks s2 then wants s1 -> deadlock.
        List<UUID> ordered = requested.stream().sorted().toList();
        for (UUID seatId : ordered) {
            eventSeatService.claimForHold(req.eventId(), seatId);   // throws SeatNotAvailableException -> rollback all
        }

        // We hold the row locks now, so these prices cannot change before we freeze them into the holds.
        Map<UUID, SeatPrice> prices = eventSeatService.pricesOf(ordered);

        List<HoldResponse> result = new ArrayList<>(ordered.size());
        for (UUID seatId : ordered) {
            SeatPrice p = prices.get(seatId);
            try {
                SeatHoldDao.Inserted ins = dao.insert(seatId, req.eventId(), userId,
                        p.price(), p.currency(), props.ttlSeconds());
                result.add(new HoldResponse(ins.id(), seatId, req.eventId(), HoldStatus.ACTIVE,
                        p.price(), p.currency(), ins.expiresAt(), 0));
            } catch (DuplicateKeyException e) {
                // The partial unique index just caught an inconsistency our guard should have made impossible.
                log.error("uq_seat_holds_one_active_per_seat fired for seat {}: investigate!", seatId);
                throw new SeatNotAvailableException(seatId, "HELD");
            }
        }
        // PHASE 8: write the SeatHeld outbox event here, in this same transaction.
        return result;
    }

    @Transactional(readOnly = true)
    public List<HoldResponse> myActiveHolds(UUID userId) {
        return holds.findByUserIdAndStatusOrderByExpiresAtAsc(userId, HoldStatus.ACTIVE)
                .stream().map(HoldResponse::from).toList();
    }

    /** Extends the hold to a fresh full TTL, at most `maxRenewals` times, and only while it is still alive. */
    @Transactional
    public HoldResponse renew(UUID userId, UUID holdId) {
        if (dao.renew(holdId, userId, props.ttlSeconds(), props.maxRenewals()).isPresent()) {
            return HoldResponse.from(holds.findById(holdId).orElseThrow());
        }
        // 0 rows: find out why. Someone else's hold looks exactly like a missing one (404, no information leak).
        SeatHold hold = ownedHold(userId, holdId);
        if (hold.getStatus() == HoldStatus.ACTIVE && hold.getRenewalCount() >= props.maxRenewals()) {
            throw new ConflictException("HOLD_RENEWAL_LIMIT",
                    "A hold can be renewed at most " + props.maxRenewals() + " time(s)");
        }
        throw new HoldExpiredException(holdId);
    }

    /** Idempotent: releasing an already released/expired hold is a harmless no-op. */
    @Transactional
    public void release(UUID userId, UUID holdId) {
        Optional<UUID> seatId = dao.release(holdId, userId);
        if (seatId.isPresent()) {
            if (!eventSeatService.transition(seatId.get(), SeatStatus.HELD, SeatStatus.AVAILABLE)) {
                log.error("Hold {} released but seat {} was not HELD: data inconsistency", holdId, seatId.get());
            }
            // PHASE 8: SeatReleased outbox event (this is what wakes the waitlist).
            return;
        }
        SeatHold hold = ownedHold(userId, holdId);
        if (hold.getStatus() == HoldStatus.CONVERTED) {
            throw new ConflictException("HOLD_ALREADY_CONVERTED", "This hold already became a booking");
        }
        // RELEASED or EXPIRED: nothing to do.
    }

    /**
     * One sweeper batch, in one transaction. Returns how many holds were expired.
     * Safe to run on every app instance at once (SKIP LOCKED). This is a SEPARATE bean method from the
     * scheduler on purpose: @Transactional only works when called through the Spring proxy, never via `this`.
     */
    @Transactional
    public int expireDueBatch() {
        List<SeatHoldDao.Expired> expired = dao.expireDue(props.sweepBatchSize());
        expired.stream()
                .sorted(Comparator.comparing(SeatHoldDao.Expired::eventSeatId))   // same lock order everywhere
                .forEach(h -> {
                    if (!eventSeatService.transition(h.eventSeatId(), SeatStatus.HELD, SeatStatus.AVAILABLE)) {
                        log.error("Hold {} expired but seat {} was not HELD: data inconsistency",
                                h.holdId(), h.eventSeatId());
                    }
                });
        // PHASE 8: SeatHoldExpired + SeatReleased outbox events here.
        return expired.size();
    }

    private SeatHold ownedHold(UUID userId, UUID holdId) {
        return holds.findById(holdId)
                .filter(h -> h.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Hold", holdId));
    }
    private final IdempotencyService idempotency;

    public static final String SCOPE_CREATE = "HOLD_CREATE";

    /** The public entry point for the API: the key and the holds commit together, or not at all. */
    @Transactional
    public List<HoldResponse> createHoldsIdempotent(UUID userId, String idempotencyKey, CreateHoldRequest req) {
        return idempotency.executeJson(userId, SCOPE_CREATE, idempotencyKey, req,
                new TypeReference<List<HoldResponse>>() {}, () -> createHolds(userId, req));
    }

    /**
     * Checkout step 1: ACTIVE holds become CONVERTED and their seats move HELD -> PAYMENT_PENDING.
     * From this moment the sweeper can no longer expire them (it only touches ACTIVE holds), so a hold can never
     * expire while a payment is in flight. MANDATORY: runs inside the checkout transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ConvertedHold> convertForCheckout(UUID userId, List<UUID> holdIds) {
        if (new HashSet<>(holdIds).size() != holdIds.size()) {
            throw new BadRequestException("holdIds must not contain duplicates");
        }
        List<ConvertedHold> converted = new ArrayList<>(dao.convert(userId, holdIds));
        if (converted.size() != holdIds.size()) {
            // Expired, released, already converted, or not yours: all look identical to the caller (no info leak).
            Set<UUID> got = converted.stream().map(ConvertedHold::holdId).collect(Collectors.toSet());
            UUID missing = holdIds.stream().filter(id -> !got.contains(id)).findFirst().orElseThrow();
            throw new HoldExpiredException(missing);          // rolls back the ones already converted
        }
        if (converted.stream().map(ConvertedHold::eventId).distinct().count() != 1) {
            throw new BadRequestException("All holds in one payment must belong to the same event");
        }
        converted.sort(Comparator.comparing(ConvertedHold::eventSeatId));   // same lock order everywhere
        for (ConvertedHold h : converted) {
            if (!eventSeatService.transition(h.eventSeatId(), SeatStatus.HELD, SeatStatus.PAYMENT_PENDING)) {
                throw new IllegalStateException("Seat " + h.eventSeatId() + " was not HELD although hold "
                        + h.holdId() + " was ACTIVE");
            }
        }
        return converted;
    }
}