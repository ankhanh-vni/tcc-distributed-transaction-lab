package com.tcc.coordinator.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(TccProperties.class)
public class TccConfiguration {

    @Bean
    public RestClient.Builder restClientBuilder(TccProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) props.getClient().getConnectTimeoutMs());
        factory.setReadTimeout((int) props.getClient().getReadTimeoutMs());
        return RestClient.builder().requestFactory(factory);
    }

    @Bean
    public RestClient participantRestClient(RestClient.Builder builder) {
        return builder.build();
    }

    @Bean
    public RestClientCustomizer participantTimeouts(TccProperties props) {
        return restClientBuilder -> restClientBuilder.requestFactory(timeoutFactory(props));
    }

    private static SimpleClientHttpRequestFactory timeoutFactory(TccProperties props) {
        var f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofMillis(props.getClient().getConnectTimeoutMs()));
        f.setReadTimeout(Duration.ofMillis(props.getClient().getReadTimeoutMs()));
        return f;
    }
}
