package com.smartirrigation.backend.exception;

public class NoImageAvailableException extends RuntimeException {

    public NoImageAvailableException() {
        super("No snapshot has been captured yet. Call POST /api/v1/scans/take-snapshot first.");
    }
}
