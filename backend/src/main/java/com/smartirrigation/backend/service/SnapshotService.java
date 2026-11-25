package com.smartirrigation.backend.service;

import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.smartirrigation.backend.client.EdgeVisionClient;
import com.smartirrigation.backend.dto.CaptureResponse;
import com.smartirrigation.backend.dto.SnapshotResult;
import com.smartirrigation.backend.exception.AiServiceException;
import com.smartirrigation.backend.exception.NoImageAvailableException;
import com.smartirrigation.backend.store.SnapshotStore;

@Service
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);
    static final String LATEST_IMAGE_URL = "/api/v1/diagnostics/latest-image";

    private final EdgeVisionClient edgeVisionClient;
    private final SnapshotStore snapshotStore;

    public SnapshotService(EdgeVisionClient edgeVisionClient, SnapshotStore snapshotStore) {
        this.edgeVisionClient = edgeVisionClient;
        this.snapshotStore = snapshotStore;
    }

    public SnapshotResult takeSnapshot() {
        CaptureResponse capture = edgeVisionClient.capture();
        byte[] jpeg = decode(capture.imageBase64());

        log.info("Snapshot received: stressDetected={}, stressType={}, confidence={}, image={}x{}, jpegBytes={}, capturedAt={}",
                capture.stressDetected(), capture.stressType(), capture.confidence(),
                capture.imageWidth(), capture.imageHeight(), jpeg.length, capture.capturedAt());

        snapshotStore.save(capture);

        return new SnapshotResult(
                capture.stressDetected(),
                capture.stressType(),
                capture.confidence(),
                capture.imageWidth(),
                capture.imageHeight(),
                jpeg.length,
                capture.capturedAt(),
                LATEST_IMAGE_URL);
    }

    public byte[] getLatestImage() {
        return snapshotStore.findLatest()
                .map(c -> decode(c.imageBase64()))
                .orElseThrow(NoImageAvailableException::new);
    }

    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new AiServiceException("Edge vision service returned invalid Base64 image data", e);
        }
    }
}
