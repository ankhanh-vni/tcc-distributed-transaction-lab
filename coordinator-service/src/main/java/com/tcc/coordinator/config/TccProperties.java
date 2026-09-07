package com.tcc.coordinator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "tcc")
public class TccProperties {

    private Map<String, ParticipantConfig> participants = Map.of();
    private Client client = new Client();
    private Recovery recovery = new Recovery();

    public Map<String, ParticipantConfig> getParticipants() { return participants; }
    public void setParticipants(Map<String, ParticipantConfig> p) { this.participants = p; }

    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }

    public Recovery getRecovery() { return recovery; }
    public void setRecovery(Recovery recovery) { this.recovery = recovery; }

    public static class ParticipantConfig {
        private String baseUrl;
        private String resourcePath;
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getResourcePath() { return resourcePath; }
        public void setResourcePath(String resourcePath) { this.resourcePath = resourcePath; }
    }

    public static class Client {
        private long connectTimeoutMs = 1000;
        private long readTimeoutMs = 2000;
        public long getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(long v) { this.connectTimeoutMs = v; }
        public long getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(long v) { this.readTimeoutMs = v; }
    }

    public static class Recovery {
        private long leaseMs = 30000;
        private int batchSize = 100;
        public long getLeaseMs() { return leaseMs; }
        public void setLeaseMs(long v) { if (v < 1 || v > 3_600_000) throw new IllegalArgumentException("lease-ms must be 1..3600000"); leaseMs = v; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int v) { if (v < 1 || v > 10000) throw new IllegalArgumentException("batch-size must be 1..10000"); batchSize = v; }
        private boolean enabled = true;
        private long fixedDelayMs = 5000;
        private long stuckAfterMs = 8000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getFixedDelayMs() { return fixedDelayMs; }
        public void setFixedDelayMs(long v) { this.fixedDelayMs = v; }
        public long getStuckAfterMs() { return stuckAfterMs; }
        public void setStuckAfterMs(long v) { this.stuckAfterMs = v; }
    }
}
