package com.tcc.coordinator.client;

public class ParticipantCallException extends RuntimeException {

    private final String participant;
    private final String phase;
    private final int httpStatus; // -1 means transport error (timeout/connect refused)

    public ParticipantCallException(String participant, String phase, int httpStatus, String body, Throwable cause) {
        super("participant=" + participant + " phase=" + phase + " status=" + httpStatus + " body=" + body, cause);
        this.participant = participant;
        this.phase = phase;
        this.httpStatus = httpStatus;
    }

    public String getParticipant() { return participant; }
    public String getPhase() { return phase; }
    public int getHttpStatus() { return httpStatus; }

    public boolean isConflict() {
        return httpStatus == 409;
    }

    public boolean isTransport() {
        return httpStatus == -1;
    }
}
