package io.artio.api.orders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
}
