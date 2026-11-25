package com.smartirrigation.backend.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartirrigation.backend.dto.SnapshotResult;
import com.smartirrigation.backend.service.SnapshotService;

@RestController
@RequestMapping("/api/v1/scans")
public class ScanController {

    private final SnapshotService snapshotService;

    public ScanController(SnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @PostMapping("/take-snapshot")
    public SnapshotResult takeSnapshot() {
        return snapshotService.takeSnapshot();
    }
}
