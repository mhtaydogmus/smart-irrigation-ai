package com.smartirrigation.backend.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.smartirrigation.backend.dto.CaptureResponse;
import com.smartirrigation.backend.exception.AiServiceException;

@Component
public class EdgeVisionClient {

    private static final Logger log = LoggerFactory.getLogger(EdgeVisionClient.class);

    private final RestClient restClient;

    public EdgeVisionClient(RestClient aiServiceRestClient) {
        this.restClient = aiServiceRestClient;
    }

    public CaptureResponse capture() {
        log.info("Requesting snapshot from edge vision service: POST /capture");
        try {
            CaptureResponse response = restClient.post()
                    .uri("/capture")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(CaptureResponse.class);

            if (response == null || response.imageBase64() == null || response.imageBase64().isBlank()) {
                throw new AiServiceException("Edge vision service returned an empty image payload");
            }
            return response;
        } catch (RestClientException e) {
            Throwable root = e.getMostSpecificCause();
            String reason = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
            throw new AiServiceException("Edge vision service call failed (is it running at the configured ai.service.url?): " + reason, e);
        }
    }
}
