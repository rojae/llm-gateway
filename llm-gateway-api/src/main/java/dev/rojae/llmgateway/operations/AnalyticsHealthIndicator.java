package dev.rojae.llmgateway.operations;

import dev.rojae.llmgateway.analytics.AnalyticsDatabase;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Independent dependency health check; provider readiness stays separate. */
@Component("analytics")
public class AnalyticsHealthIndicator implements ReactiveHealthIndicator {
    private final ObjectProvider<AnalyticsDatabase> database;
    private final ObjectProvider<ReactiveRedisConnectionFactory> redis;
    private final Environment environment;
    private final Scheduler worker = Schedulers.newBoundedElastic(1, 4, "analytics-health");

    public AnalyticsHealthIndicator(ObjectProvider<AnalyticsDatabase> database,
                                    ObjectProvider<ReactiveRedisConnectionFactory> redis,
                                    Environment environment) {
        this.database = database;
        this.redis = redis;
        this.environment = environment;
    }

    @Override public Mono<Health> health() {
        return Mono.defer(() -> {
            var db = database.getIfAvailable();
            if (db == null) return Mono.just(Health.up().build());
            if (!db.isInitialized()) return Mono.just(Health.down().build());
            return Mono.fromCallable(() -> {
                try (var connection = db.open(); var query = connection.createStatement()) {
                    query.setQueryTimeout(2);
                    try (var result = query.executeQuery("SELECT 1")) {
                        if (!result.next()) throw new IllegalStateException("database_unavailable");
                    }
                }
                return true;
            }).subscribeOn(worker).then(checkSessionStore()).thenReturn(Health.up().build());
        }).timeout(Duration.ofSeconds(4)).onErrorReturn(Health.down().build());
    }

    private Mono<Void> checkSessionStore() {
        if (!"enterprise".equals(environment.getProperty("gateway.security.mode", "local"))) {
            return Mono.empty();
        }
        return Mono.defer(() -> {
            var factory = redis.getIfAvailable();
            if (factory == null) return Mono.error(new IllegalStateException("session_store_unavailable"));
            return Mono.usingWhen(Mono.fromSupplier(factory::getReactiveConnection),
                    connection -> connection.ping().flatMap(reply -> "PONG".equals(reply)
                            ? Mono.<Void>empty() : Mono.error(new IllegalStateException("session_store_unavailable"))),
                    connection -> connection.closeLater());
        });
    }

    @PreDestroy public void close() { worker.dispose(); }
}
