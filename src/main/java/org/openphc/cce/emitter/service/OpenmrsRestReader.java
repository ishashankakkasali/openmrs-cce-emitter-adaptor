package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.OpenmrsConfig;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/** Reads one OpenMRS REST resource (e.g. {@code /visit/{uuid}?v=…}) as JSON, with the emitter's OpenMRS credentials. */
@Component
@RequiredArgsConstructor
public class OpenmrsRestReader {

    private final EmitterProperties properties;
    private final RestTemplate restTemplate;
    private final AuthService authService;
    private final ObjectMapper objectMapper;

    /**
     * @param pathAndQuery path under {@code /ws/rest/v1}, e.g. {@code /visit/8c5520d3-…?v=custom:(stopDatetime)}
     * @return the response body, or {@code null} when it is empty
     */
    public JsonNode get(String pathAndQuery) throws Exception {
        OpenmrsConfig openmrs = properties.getOpenmrs();
        String baseUrl = openmrs.getBaseUrl() == null ? "" : openmrs.getBaseUrl().replaceAll("/+$", "");
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        String authHeader = authService.resolveOpenmrsAuthHeader(openmrs.getAuth());
        if (authHeader != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authHeader);
        }
        ResponseEntity<String> response = restTemplate.exchange(baseUrl + "/ws/rest/v1" + pathAndQuery,
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        String body = response.getBody();
        return body == null || body.isBlank() ? null : objectMapper.readTree(body);
    }
}
