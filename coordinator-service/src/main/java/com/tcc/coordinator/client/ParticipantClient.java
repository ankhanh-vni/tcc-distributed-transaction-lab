package com.tcc.coordinator.client;

import com.tcc.common.headers.TccHeaders;
import com.tcc.coordinator.domain.TransactionParticipant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

/**
 * Outbound HTTP client for TCC participants. Wraps a {@link RestClient} configured
 * with connect/read timeouts. Translates protocol-relevant exceptions into
 * {@link ParticipantCallException} so the orchestrator can drive recovery.
 */
@Component
public class ParticipantClient {

    private static final Logger log = LoggerFactory.getLogger(ParticipantClient.class);

    private final RestClient http;

    public ParticipantClient(RestClient participantRestClient) {
        this.http = participantRestClient;
    }

    public void callTry(TransactionParticipant p, String optionalFailHeader) {
        String url = p.getBaseUrl() + p.getResourcePath();
        log.debug("[try] -> {} tx={} payload={}", url, p.getTxId(), p.getPayloadJson());
        try {
            http.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(TccHeaders.TX_ID, p.getTxId().toString())
                    .headers(h -> { if (optionalFailHeader != null) h.add(TccHeaders.FAIL_AT, optionalFailHeader); })
                    .body(p.getPayloadJson())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new ParticipantCallException(p.getParticipant(), "TRY", ex.getStatusCode().value(), ex.getResponseBodyAsString(), ex);
        } catch (Exception ex) {
            throw new ParticipantCallException(p.getParticipant(), "TRY", -1, ex.getMessage(), ex);
        }
    }

    public void callConfirm(TransactionParticipant p) {
        callConfirm(p, null);
    }

    public void callConfirm(TransactionParticipant p, String optionalSleepMillis) {
        String url = p.getBaseUrl() + p.getResourcePath() + "/" + p.getTxId() + "/confirm";
        log.debug("[confirm] -> {}", url);
        try {
            http.put()
                    .uri(url)
                    .header(TccHeaders.TX_ID, p.getTxId().toString())
                    .headers(h -> { if (optionalSleepMillis != null) h.add(TccHeaders.SLEEP_MILLIS, optionalSleepMillis); })
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new ParticipantCallException(p.getParticipant(), "CONFIRM", ex.getStatusCode().value(), ex.getResponseBodyAsString(), ex);
        } catch (Exception ex) {
            throw new ParticipantCallException(p.getParticipant(), "CONFIRM", -1, ex.getMessage(), ex);
        }
    }

    public void callCancel(UUID txId, TransactionParticipant p) {
        String url = p.getBaseUrl() + p.getResourcePath() + "/" + txId;
        log.debug("[cancel] -> {}", url);
        try {
            http.delete()
                    .uri(url)
                    .header(TccHeaders.TX_ID, txId.toString())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new ParticipantCallException(p.getParticipant(), "CANCEL", ex.getStatusCode().value(), ex.getResponseBodyAsString(), ex);
        } catch (Exception ex) {
            throw new ParticipantCallException(p.getParticipant(), "CANCEL", -1, ex.getMessage(), ex);
        }
    }
}
