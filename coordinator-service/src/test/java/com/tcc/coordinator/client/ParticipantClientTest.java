package com.tcc.coordinator.client;

import com.tcc.coordinator.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ParticipantClientTest {
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ParticipantClient client = new ParticipantClient(builder.build());
    private final UUID id = UUID.randomUUID();
    private final TransactionParticipant p = new TransactionParticipant(id, "inventory", "http://participant",
            "/tcc/inventory/reservations", ParticipantTxState.TRIED, "{}");

    @Test
    void rejectsWrongTransactionInSuccessfulResponse() {
        server.expect(requestTo("http://participant/tcc/inventory/reservations/" + id + "/confirm"))
                .andRespond(withSuccess("{\"txId\":\"" + UUID.randomUUID() + "\",\"state\":\"CONFIRMED\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.callConfirm(p, null)).isInstanceOf(ParticipantCallException.class);
        server.verify();
    }
    @Test
    void rejectsCancelResponseThatIsStillTried() {
        server.expect(requestTo("http://participant/tcc/inventory/reservations/" + id))
                .andRespond(withSuccess("{\"txId\":\"" + id + "\",\"state\":\"TRIED\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.callCancel(id, p)).isInstanceOf(ParticipantCallException.class);
        server.verify();
    }
    @Test
    void acceptsMatchingConfirmation() {
        server.expect(requestTo("http://participant/tcc/inventory/reservations/" + id + "/confirm"))
                .andRespond(withSuccess("{\"txId\":\"" + id + "\",\"state\":\"CONFIRMED\"}", MediaType.APPLICATION_JSON));
        client.callConfirm(p, null);
        server.verify();
    }
    @Test
    void rejectsEmptySuccessBody() {
        server.expect(requestTo("http://participant/tcc/inventory/reservations/" + id + "/confirm"))
                .andRespond(withSuccess());
        assertThatThrownBy(() -> client.callConfirm(p, null)).isInstanceOf(ParticipantCallException.class);
        server.verify();
    }
}
