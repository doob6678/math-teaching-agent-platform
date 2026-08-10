package com.doob.mathagent.system;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.stereotype.Component;

/** Performs short, real dependency probes used by readiness instead of reporting configuration-only health. */
@Component
public class InfrastructureDependencyProbe {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);
    private final ObjectProvider<DataSource> dataSources;
    private final ObjectProvider<StringRedisTemplate> redisTemplates;
    private final ObjectProvider<RabbitTemplate> rabbitTemplates;
    private final Environment environment;
    private final HttpClient httpClient;

    @Autowired
    public InfrastructureDependencyProbe(
            ObjectProvider<DataSource> dataSources,
            ObjectProvider<StringRedisTemplate> redisTemplates,
            ObjectProvider<RabbitTemplate> rabbitTemplates,
            Environment environment) {
        this.dataSources = dataSources;
        this.redisTemplates = redisTemplates;
        this.rabbitTemplates = rabbitTemplates;
        this.environment = environment;
        this.httpClient = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build();
    }

    /** Returns an intentionally unprobed result for isolated service unit tests. */
    public static InfrastructureDependencyProbe disabled() {
        return new InfrastructureDependencyProbe(null, null, null, null, null);
    }

    private InfrastructureDependencyProbe(
            ObjectProvider<DataSource> dataSources,
            ObjectProvider<StringRedisTemplate> redisTemplates,
            ObjectProvider<RabbitTemplate> rabbitTemplates,
            Environment environment,
            HttpClient httpClient) {
        this.dataSources = dataSources;
        this.redisTemplates = redisTemplates;
        this.rabbitTemplates = rabbitTemplates;
        this.environment = environment;
        this.httpClient = httpClient;
    }

    /** Probes MySQL, Redis, RabbitMQ, the Python Worker, and the Flyway history table. */
    public Result probe() {
        if (environment == null) {
            return Result.unprobed();
        }
        boolean mysql = mysqlReachable();
        boolean flyway = mysql && flywayReady();
        return new Result(true, mysql, redisReachable(), rabbitReachable(), workerReachable(), flyway);
    }

    private boolean mysqlReachable() {
        if (dataSources == null) {
            return false;
        }
        try (Connection connection = dataSources.getIfAvailable().getConnection()) {
            return connection.isValid((int) PROBE_TIMEOUT.toSeconds());
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean flywayReady() {
        try (Connection connection = dataSources.getIfAvailable().getConnection();
                var statement = connection.prepareStatement(
                        "SELECT installed_rank FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1");
                var result = statement.executeQuery()) {
            return result.next() && result.getInt(1) > 0;
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean redisReachable() {
        if (redisTemplates == null) {
            return false;
        }
        try {
            // RedisTemplate exposes both RedisCallback and SessionCallback overloads;
            // the explicit callback type keeps this real readiness probe compiling
            // against current Spring Data Redis versions and makes the intended
            // single-connection PING operation unambiguous.
            RedisCallback<String> pingCallback = connection -> connection.ping();
            String response = redisTemplates.getIfAvailable().execute(pingCallback);
            return response != null && !response.isBlank();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean rabbitReachable() {
        if (rabbitTemplates == null) {
            return false;
        }
        try {
            RabbitTemplate template = rabbitTemplates.stream().findFirst().orElse(null);
            if (template == null) {
                return false;
            }
            ConnectionFactory factory = template.getConnectionFactory();
            var connection = factory.createConnection();
            try {
                return connection.isOpen();
            } finally {
                connection.close();
            }
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean workerReachable() {
        String baseUrl = environment.getProperty("math-agent.vector-index.embedding-base-url", "");
        if (baseUrl.isBlank()) {
            return false;
        }
        String healthUrl = baseUrl.replaceFirst("/v1/?$", "") + "/health";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(healthUrl))
                    .timeout(PROBE_TIMEOUT)
                    .GET()
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception exception) {
            return false;
        }
    }

    /** Safe dependency state exposed to readiness and admin diagnostics. */
    public record Result(
            boolean probed,
            boolean mysql,
            boolean redis,
            boolean rabbitmq,
            boolean worker,
            boolean flyway) {

        static Result unprobed() {
            return new Result(false, true, true, true, true, true);
        }
    }
}
