package com.smartirrigation.backend.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.smartirrigation.backend.config.SystemLockProperties;
import com.smartirrigation.backend.state.LockHandle;
import com.smartirrigation.backend.state.SystemState;

@Service
public class SystemStateService {

    private static final Logger log = LoggerFactory.getLogger(SystemStateService.class);

    private record Snapshot(SystemState state, LockHandle lock) {
    }

    private static final Snapshot IDLE = new Snapshot(SystemState.IDLE, null);

    private final AtomicReference<Snapshot> current = new AtomicReference<>(IDLE);
    private final Duration timeout;

    public SystemStateService(SystemLockProperties props) {
        this.timeout = props.timeout();
    }

    public Optional<LockHandle> tryAcquireLock(String owner) {
        LockHandle handle = new LockHandle(owner, Instant.now());
        if (current.compareAndSet(IDLE, new Snapshot(SystemState.BUSY, handle))) {
            log.info("State IDLE -> BUSY (owner={})", owner);
            return Optional.of(handle);
        }
        LockHandle holder = current.get().lock();
        log.warn("Scan request from {} rejected: system is BUSY (held by {} since {})",
                owner, holder == null ? "?" : holder.owner(), holder == null ? "?" : holder.lockedAt());
        return Optional.empty();
    }

    public boolean release(LockHandle handle) {
        Snapshot snapshot = current.get();
        if (snapshot.lock() == handle && current.compareAndSet(snapshot, IDLE)) {
            log.info("State BUSY -> IDLE (owner={}, held {} ms)",
                    handle.owner(), Duration.between(handle.lockedAt(), Instant.now()).toMillis());
            return true;
        }
        log.warn("Lock of {} was already force-reset by the failsafe, nothing to release", handle.owner());
        return false;
    }

    public SystemState getState() {
        return current.get().state();
    }

    @Scheduled(fixedDelayString = "${system.lock.failsafe-check-interval-ms:10000}")
    public void recoverStuckLock() {
        Snapshot snapshot = current.get();
        if (snapshot.state() != SystemState.BUSY) {
            return;
        }
        Duration held = Duration.between(snapshot.lock().lockedAt(), Instant.now());
        if (held.compareTo(timeout) > 0 && current.compareAndSet(snapshot, IDLE)) {
            log.warn("FAILSAFE: system was BUSY for {} s (owner={}, limit {} s), forced reset to IDLE",
                    held.toSeconds(), snapshot.lock().owner(), timeout.toSeconds());
        }
    }
}
