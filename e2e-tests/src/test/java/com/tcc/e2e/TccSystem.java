package com.tcc.e2e;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;

/**
 * Brings up the full docker-compose stack (postgres + 4 services) for e2e tests.
 * Singleton: the same container set is shared across all test classes in the module
 * to keep the build fast.
 */
public final class TccSystem {

    private static volatile ComposeContainer compose;

    private TccSystem() {
    }

    public static synchronized void start() {
        if (compose != null) return;
        compose = new ComposeContainer(new File("../docker-compose.yml"))
                .withExposedService("postgres", 5432, Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(120)))
                .withExposedService("inventory-service", 8081, Wait.forHttp("/actuator/health").forStatusCode(200).forStatusCode(404).withStartupTimeout(Duration.ofMinutes(2)))
                .withExposedService("payment-service", 8082, Wait.forHttp("/actuator/health").forStatusCode(200).forStatusCode(404).withStartupTimeout(Duration.ofMinutes(2)))
                .withExposedService("order-service", 8083, Wait.forHttp("/actuator/health").forStatusCode(200).forStatusCode(404).withStartupTimeout(Duration.ofMinutes(2)))
                .withExposedService("coordinator-service", 8080, Wait.forHttp("/actuator/health").forStatusCode(200).forStatusCode(404).withStartupTimeout(Duration.ofMinutes(2)))
                .withLocalCompose(true);
        compose.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> compose.stop()));
    }

    public static String coordinatorUrl() {
        return "http://" + compose.getServiceHost("coordinator-service", 8080) + ":" + compose.getServicePort("coordinator-service", 8080);
    }

    public static String inventoryUrl() {
        return "http://" + compose.getServiceHost("inventory-service", 8081) + ":" + compose.getServicePort("inventory-service", 8081);
    }

    public static String paymentUrl() {
        return "http://" + compose.getServiceHost("payment-service", 8082) + ":" + compose.getServicePort("payment-service", 8082);
    }

    public static String orderUrl() {
        return "http://" + compose.getServiceHost("order-service", 8083) + ":" + compose.getServicePort("order-service", 8083);
    }

    public static Connection openConnection(String dbName, String user, String pass) throws SQLException {
        String host = compose.getServiceHost("postgres", 5432);
        int port = compose.getServicePort("postgres", 5432);
        return DriverManager.getConnection("jdbc:postgresql://" + host + ":" + port + "/" + dbName, user, pass);
    }

    public static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
