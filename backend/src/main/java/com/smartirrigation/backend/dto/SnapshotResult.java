package com.smartirrigation.backend.dto;

import java.time.Instant;

public record SnapshotResult(
        String fileName,
        boolean stressDetected,
        String stressType,
        String diagnosis,
        double confidence,
        String inferenceSource,
        int dosageMl,
        int valveSec,
        MqttDispatch.Status mqttStatus,
        String mqttPayload,
        int imageWidth,
        int imageHeight,
        int imageSizeBytes,
        Instant capturedAt,
        String imageUrl) {
}
