package com.smartirrigation.backend.store;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Repository;

import com.smartirrigation.backend.dto.CaptureResponse;

@Repository
public class InMemorySnapshotStore implements SnapshotStore {

    private final AtomicReference<CaptureResponse> latest = new AtomicReference<>();

    @Override
    public void save(CaptureResponse snapshot) {
        latest.set(snapshot);
    }

    @Override
    public Optional<CaptureResponse> findLatest() {
        return Optional.ofNullable(latest.get());
    }
}
