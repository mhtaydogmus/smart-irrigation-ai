package com.smartirrigation.backend.dto;

import java.time.Instant;

public record SnapshotResult(
        boolean stressDetected,
        String stressType,
        double confidence,
        int imageWidth,
        int imageHeight,
        int imageSizeBytes,
        Instant capturedAt,
        String imageUrl) {
}
