package com.smartirrigation.backend.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartirrigation.backend.service.SnapshotService;

@RestController
@RequestMapping("/api/v1/diagnostics")
public class DiagnosticsController {

    private final SnapshotService snapshotService;

    public DiagnosticsController(SnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @GetMapping("/latest-image")
    public ResponseEntity<byte[]> latestImage() {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore())
                .body(snapshotService.getLatestImage());
    }
}
