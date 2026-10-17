package io.artio.api.orders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class OrderUpdatePublisherTests {
    @Test
    void orderStateIsReadOnlyAfterTheTransactionCommits() {
        var jdbc = mock(JdbcTemplate.class);
        var messaging = mock(SimpMessagingTemplate.class);
        var beans = new StaticListableBeanFactory();
        beans.addBean("messaging", messaging);
        var publisher = new OrderUpdatePublisher(jdbc, beans.getBeanProvider(SimpMessagingTemplate.class),
                beans.getBeanProvider(RedisOrderUpdateFanout.class));
        UUID orderId = UUID.randomUUID();

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            publisher.publish(orderId);
            verifyNoInteractions(jdbc, messaging);
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations())
                synchronization.afterCommit();
            verify(jdbc).query(anyString(), any(RowMapper.class), eq(orderId));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void doesNotPublishAfterRollbackBecauseNoCommitCallbackRuns() {
        var jdbc = mock(JdbcTemplate.class);
        var messaging = mock(SimpMessagingTemplate.class);
        var beans = new StaticListableBeanFactory();
        beans.addBean("messaging", messaging);
        var publisher = new OrderUpdatePublisher(jdbc, beans.getBeanProvider(SimpMessagingTemplate.class),
                beans.getBeanProvider(RedisOrderUpdateFanout.class));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            publisher.publish(UUID.randomUUID());
            TransactionSynchronizationManager.clearSynchronization();
            verifyNoInteractions(jdbc, messaging);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            if (TransactionSynchronizationManager.isSynchronizationActive())
                TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void fallsBackToLocalWebSocketWhenRedisFanoutIsUnavailable() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var messaging = mock(SimpMessagingTemplate.class);
        var redis = mock(RedisOrderUpdateFanout.class);
        var beans = new StaticListableBeanFactory();
        beans.addBean("messaging", messaging);
        beans.addBean("redis", redis);
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(orderId))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper = invocation.getArgument(1);
            var row = mock(ResultSet.class);
            when(row.getObject(1, UUID.class)).thenReturn(userId);
            when(row.getString(2)).thenReturn("PAID");
            when(row.getBigDecimal(3)).thenReturn(new BigDecimal("12.50"));
            when(row.getString(4)).thenReturn("CHF");
            return java.util.List.of(mapper.mapRow(row, 0));
        });
        doThrow(new IllegalStateException("Redis unavailable")).when(redis).publish(eq(userId), any());
        var publisher = new OrderUpdatePublisher(jdbc, beans.getBeanProvider(SimpMessagingTemplate.class),
                beans.getBeanProvider(RedisOrderUpdateFanout.class));

        publisher.publish(orderId);

        verify(messaging).convertAndSendToUser(eq(userId.toString()), eq("/queue/orders/" + orderId), any());
    }
}
