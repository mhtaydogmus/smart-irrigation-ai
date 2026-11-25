package com.smartirrigation.backend.state;

import java.time.Instant;

public record LockHandle(String owner, Instant lockedAt) {
}
