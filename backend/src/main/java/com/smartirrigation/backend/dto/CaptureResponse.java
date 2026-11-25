package com.smartirrigation.backend.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CaptureResponse(
        String fileName,
        boolean stressDetected,
        String stressType,
        double confidence,
        String inferenceSource,
        String imageBase64,
        int imageWidth,
        int imageHeight,
        Instant capturedAt) {

    @Override
    public String toString() {
        return "CaptureResponse[fileName=%s, stressDetected=%s, stressType=%s, confidence=%.3f, inferenceSource=%s, image=%dx%d, base64Length=%d, capturedAt=%s]"
                .formatted(fileName, stressDetected, stressType, confidence, inferenceSource, imageWidth, imageHeight,
                        imageBase64 == null ? 0 : imageBase64.length(), capturedAt);
    }
}
