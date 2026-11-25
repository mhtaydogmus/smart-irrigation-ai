package com.smartirrigation.backend.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CaptureResponse(
        boolean stressDetected,
        String stressType,
        double confidence,
        String imageBase64,
        int imageWidth,
        int imageHeight,
        Instant capturedAt) {

    @Override
    public String toString() {
        return "CaptureResponse[stressDetected=%s, stressType=%s, confidence=%.3f, image=%dx%d, base64Length=%d, capturedAt=%s]"
                .formatted(stressDetected, stressType, confidence, imageWidth, imageHeight,
                        imageBase64 == null ? 0 : imageBase64.length(), capturedAt);
    }
}
