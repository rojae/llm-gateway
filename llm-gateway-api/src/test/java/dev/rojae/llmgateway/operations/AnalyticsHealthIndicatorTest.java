package dev.rojae.llmgateway.operations;

import dev.rojae.llmgateway.analytics.AnalyticsDatabase;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AnalyticsHealthIndicatorTest {
    @Test void disabledAnalyticsDoesNotDependOnRedisOrDatabase() {
        var database = provider(AnalyticsDatabase.class);
        var redis = provider(ReactiveRedisConnectionFactory.class);
        var health = new AnalyticsHealthIndicator(database, redis, new MockEnvironment());
        try {
            assertThat(health.health().block().getStatus()).isEqualTo(Status.UP);
            verifyNoInteractions(redis);
        } finally { health.close(); }
    }

    @Test void migrationMustFinishBeforeDependenciesSucceed() {
        var database = provider(AnalyticsDatabase.class);
        var db = mock(AnalyticsDatabase.class);
        when(database.getIfAvailable()).thenReturn(db);
        var health = new AnalyticsHealthIndicator(database, provider(ReactiveRedisConnectionFactory.class), new MockEnvironment());
        try {
            assertThat(health.health().block().getStatus()).isEqualTo(Status.DOWN);
            verify(db).isInitialized();
        } finally { health.close(); }
    }

    @Test void databaseFailureIsDownWithoutLeakingConnectionDetails() throws Exception {
        var database = provider(AnalyticsDatabase.class);
        var db = mock(AnalyticsDatabase.class);
        when(database.getIfAvailable()).thenReturn(db);
        when(db.isInitialized()).thenReturn(true);
        when(db.open()).thenThrow(new SQLException("PRIVATE_JDBC_PASSWORD"));
        var health = new AnalyticsHealthIndicator(database, provider(ReactiveRedisConnectionFactory.class), new MockEnvironment());
        try {
            var result = health.health().block();
            assertThat(result.getStatus()).isEqualTo(Status.DOWN);
            assertThat(result.getDetails()).isEmpty();
        } finally { health.close(); }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(Class<T> type) { return mock(ObjectProvider.class); }
}
