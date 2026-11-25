package com.smartirrigation.backend.store;

import java.util.Optional;

import com.smartirrigation.backend.dto.CaptureResponse;

public interface SnapshotStore {

    void save(CaptureResponse snapshot);

    Optional<CaptureResponse> findLatest();
}
