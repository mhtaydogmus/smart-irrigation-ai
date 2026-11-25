package com.smartirrigation.backend.service;

import java.nio.charset.StandardCharsets;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartirrigation.backend.config.MqttProperties;
import com.smartirrigation.backend.dto.IrrigationDecision;
import com.smartirrigation.backend.dto.MqttCommand;
import com.smartirrigation.backend.dto.MqttDispatch;
import com.smartirrigation.backend.dto.MqttDispatch.Status;

import jakarta.annotation.PreDestroy;

@Service
public class MqttPublisherService {

    private static final Logger log = LoggerFactory.getLogger(MqttPublisherService.class);
    private static final int QOS_AT_LEAST_ONCE = 1;
    private static final int CONNECT_TIMEOUT_SEC = 3;
    private static final long PUBLISH_TIMEOUT_MS = 5000;

    private final MqttProperties props;
    private final ObjectMapper objectMapper;
    private MqttClient client;

    public MqttPublisherService(MqttProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        log.info("MQTT {} (broker={}, topic={})", props.enabled() ? "ENABLED" : "SIMULATION mode",
                props.broker().url(), props.topic().command());
    }

    public synchronized MqttDispatch publish(IrrigationDecision decision) {
        String topic = props.topic().command();
        String payload = toJson(new MqttCommand(decision.valveSec(), decision.dosageMl(), decision.stressType()));

        if (decision.valveSec() == 0) {
            log.info("valve_sec=0 for '{}', no MQTT command sent", decision.stressType());
            return new MqttDispatch(Status.SKIPPED, topic, payload);
        }

        if (!props.enabled()) {
            log.info("[MQTT SIMULATION] topic={} qos={} payload={}", topic, QOS_AT_LEAST_ONCE, payload);
            return new MqttDispatch(Status.SIMULATED, topic, payload);
        }

        try {
            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            message.setQos(QOS_AT_LEAST_ONCE);
            connectedClient().publish(topic, message);
            log.info("[MQTT] Published to {}: {}", topic, payload);
            return new MqttDispatch(Status.PUBLISHED, topic, payload);
        } catch (MqttException e) {
            log.error("[MQTT] Publish to {} via {} failed: {}", topic, props.broker().url(), e.getMessage());
            return new MqttDispatch(Status.FAILED, topic, payload);
        }
    }

    private MqttClient connectedClient() throws MqttException {
        if (client == null) {
            client = new MqttClient(props.broker().url(), props.clientId(), new MemoryPersistence());
            client.setTimeToWait(PUBLISH_TIMEOUT_MS);
        }
        if (!client.isConnected()) {
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setConnectionTimeout(CONNECT_TIMEOUT_SEC);
            client.connect(options);
            log.info("[MQTT] Connected to {}", props.broker().url());
        }
        return client;
    }

    private String toJson(MqttCommand command) {
        try {
            return objectMapper.writeValueAsString(command);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize MQTT command " + command, e);
        }
    }

    @PreDestroy
    synchronized void shutdown() {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (MqttException e) {
            log.warn("[MQTT] Error while closing client: {}", e.getMessage());
        }
    }
}
