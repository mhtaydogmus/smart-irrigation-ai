package com.smartirrigation.backend.service;

import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.smartirrigation.backend.client.EdgeVisionClient;
import com.smartirrigation.backend.dto.CaptureResponse;
import com.smartirrigation.backend.dto.IrrigationDecision;
import com.smartirrigation.backend.dto.MqttDispatch;
import com.smartirrigation.backend.dto.SnapshotResult;
import com.smartirrigation.backend.exception.AiServiceException;
import com.smartirrigation.backend.exception.NoImageAvailableException;
import com.smartirrigation.backend.exception.SystemBusyException;
import com.smartirrigation.backend.state.LockHandle;
import com.smartirrigation.backend.store.SnapshotStore;

@Service
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);
    static final String LATEST_IMAGE_URL = "/api/v1/diagnostics/latest-image";

    private final EdgeVisionClient edgeVisionClient;
    private final SnapshotStore snapshotStore;
    private final SystemStateService systemStateService;
    private final DecisionEngineService decisionEngineService;
    private final MqttPublisherService mqttPublisherService;

    public SnapshotService(EdgeVisionClient edgeVisionClient,
                           SnapshotStore snapshotStore,
                           SystemStateService systemStateService,
                           DecisionEngineService decisionEngineService,
                           MqttPublisherService mqttPublisherService) {
        this.edgeVisionClient = edgeVisionClient;
        this.snapshotStore = snapshotStore;
        this.systemStateService = systemStateService;
        this.decisionEngineService = decisionEngineService;
        this.mqttPublisherService = mqttPublisherService;
    }

    public SnapshotResult takeSnapshot() {
        return runScan(ScanTrigger.REST_API, null).orElseThrow(SystemBusyException::new);
    }

    public Optional<SnapshotResult> processDroppedImage(Path image) {
        return runScan(ScanTrigger.FOLDER_DROP, image.toAbsolutePath().toString());
    }

    public byte[] getLatestImage() {
        return snapshotStore.findLatest()
                .map(c -> decode(c.imageBase64()))
                .orElseThrow(NoImageAvailableException::new);
    }

    private Optional<SnapshotResult> runScan(ScanTrigger trigger, String imagePath) {
        Optional<LockHandle> lock = systemStateService.tryAcquireLock(trigger.name());
        if (lock.isEmpty()) {
            return Optional.empty();
        }

        SnapshotResult result;
        boolean released;
        try {
            result = executePipeline(imagePath);
        } finally {
            released = systemStateService.release(lock.get());
        }

        printSummary(trigger, result, released);
        return Optional.of(result);
    }

    private SnapshotResult executePipeline(String imagePath) {
        CaptureResponse capture = edgeVisionClient.capture(imagePath);
        byte[] jpeg = decode(capture.imageBase64());
        snapshotStore.save(capture);

        IrrigationDecision decision = decisionEngineService.decide(capture.stressType());
        MqttDispatch dispatch = mqttPublisherService.publish(decision);

        return new SnapshotResult(
                capture.fileName(),
                capture.stressDetected(),
                decision.stressType(),
                capture.confidence(),
                capture.inferenceSource(),
                decision.dosageMl(),
                decision.valveSec(),
                dispatch.status(),
                dispatch.payload(),
                capture.imageWidth(),
                capture.imageHeight(),
                jpeg.length,
                capture.capturedAt(),
                LATEST_IMAGE_URL);
    }

    private static void printSummary(ScanTrigger trigger, SnapshotResult r, boolean released) {
        String action = switch (r.mqttStatus()) {
            case SKIPPED -> "No irrigation required";
            case SIMULATED -> "[MQTT SIMULATION] " + r.mqttPayload();
            case PUBLISHED -> "[MQTT PUBLISHED] " + r.mqttPayload();
            case FAILED -> "[MQTT FAILED, broker unreachable] " + r.mqttPayload();
        };
        String diagnosis = "%s (%.1f%% confidence, %s inference)".formatted(
                r.stressType(), r.confidence() * 100, r.inferenceSource() == null ? "unknown" : r.inferenceSource());
        String lock = released ? "BUSY -> IDLE" : "BUSY -> IDLE (already reset by failsafe)";

        log.info("""

                ==============================================================
                  SMART IRRIGATION - PROTOTYPE SCAN SUMMARY
                --------------------------------------------------------------
                  Trigger        : {}
                  File           : {}
                  AI Diagnosis   : {}
                  Stress         : {}
                  Decision       : {} ml -> valve open {} sec
                  Action         : {}
                  Lock           : {}
                  Image          : {}x{}, {} KB, captured {}
                ==============================================================""",
                trigger, r.fileName(), diagnosis, r.stressDetected() ? "DETECTED" : "none",
                r.dosageMl(), r.valveSec(), action, lock,
                r.imageWidth(), r.imageHeight(), String.format(Locale.ROOT, "%.1f", r.imageSizeBytes() / 1024.0),
                r.capturedAt());
    }

    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new AiServiceException("Edge vision service returned invalid Base64 image data", e);
        }
    }
}
