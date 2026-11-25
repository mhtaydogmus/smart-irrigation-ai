package com.smartirrigation.backend.exception;

public class SystemBusyException extends RuntimeException {

    public SystemBusyException() {
        super("A scan is already in progress (system is BUSY). Try again in a few seconds.");
    }
}
