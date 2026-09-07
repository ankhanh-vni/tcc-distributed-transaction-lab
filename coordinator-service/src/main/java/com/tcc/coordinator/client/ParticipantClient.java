package com.tcc.coordinator.client;

import com.tcc.common.headers.TccHeaders;
import com.tcc.common.dto.TccStatusResponse;
import com.tcc.common.tcc.ParticipantState;
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
            var response = http.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(TccHeaders.TX_ID, p.getTxId().toString())
                    .headers(h -> { if (optionalFailHeader != null) h.add(TccHeaders.FAIL_AT, optionalFailHeader); })
                    .body(p.getPayloadJson())
                    .retrieve()
                    .body(TccStatusResponse.class);
            validate(p, "TRY", response);
        } catch (ParticipantCallException ex) {
            throw ex;
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
            var response = http.put()
                    .uri(url)
                    .header(TccHeaders.TX_ID, p.getTxId().toString())
                    .headers(h -> { if (optionalSleepMillis != null) h.add(TccHeaders.SLEEP_MILLIS, optionalSleepMillis); })
                    .retrieve()
                    .body(TccStatusResponse.class);
            validate(p, "CONFIRM", response);
        } catch (ParticipantCallException ex) {
            throw ex;
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
            var response = http.delete()
                    .uri(url)
                    .header(TccHeaders.TX_ID, txId.toString())
                    .retrieve()
                    .body(TccStatusResponse.class);
            validate(p, "CANCEL", response);
        } catch (ParticipantCallException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            throw new ParticipantCallException(p.getParticipant(), "CANCEL", ex.getStatusCode().value(), ex.getResponseBodyAsString(), ex);
        } catch (Exception ex) {
            throw new ParticipantCallException(p.getParticipant(), "CANCEL", -1, ex.getMessage(), ex);
        }
    }
    private void validate(TransactionParticipant p, String phase, TccStatusResponse response) {
        boolean valid = response != null && p.getTxId().equals(response.txId()) && switch (phase) {
            case "TRY" -> response.state() == ParticipantState.TRIED || response.state() == ParticipantState.CONFIRMED;
            case "CONFIRM" -> response.state() == ParticipantState.CONFIRMED;
            case "CANCEL" -> response.state() == ParticipantState.CANCELLED;
            default -> false;
        };
        if (!valid) throw new ParticipantCallException(p.getParticipant(), phase, 502, "Invalid participant response", null);
    }
}
