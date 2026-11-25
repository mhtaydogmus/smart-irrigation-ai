package com.smartirrigation.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "watcher")
public record WatcherProperties(String directory) {
}
