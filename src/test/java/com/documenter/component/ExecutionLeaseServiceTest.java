package com.documenter.component;

import com.documenter.configuration.TaskExecutorProperties;
import com.documenter.mapper.ExecutionLeaseMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionLeaseServiceTest {
    @Test
    void invalidLeaseTimingAndUnboundedBatchAreRejected() {
        var properties = new TaskExecutorProperties();
        properties.getCluster().setEnabled(true);
        properties.getCluster().setHeartbeat(Duration.ofMillis(99));
        assertThrows(IllegalArgumentException.class, () -> service(properties, mock(ExecutionLeaseMapper.class)));
        properties.getCluster().setHeartbeat(Duration.ofSeconds(10));
        properties.getCluster().setLeaseDuration(Duration.ofSeconds(29));
        assertThrows(IllegalArgumentException.class, () -> service(properties, mock(ExecutionLeaseMapper.class)));
        properties.getCluster().setLeaseDuration(Duration.ofSeconds(60));
        properties.getCluster().setBatchSize(1001);
        assertThrows(IllegalArgumentException.class, () -> service(properties, mock(ExecutionLeaseMapper.class)));
    }

    @Test
    void heartbeatRenewsOnlyTrackedWorkAndStopsAfterRegistrationCloses() {
        var properties = new TaskExecutorProperties(); properties.getCluster().setEnabled(true);
        var mapper = mock(ExecutionLeaseMapper.class);
        var service = service(properties, mapper);
        service.heartbeat(); verifyNoInteractions(mapper);
        when(mapper.lockRenewableTasks(List.of(10L), service.ownerId())).thenReturn(List.of(10L));
        when(mapper.lockRenewableReplies(List.of(20L), service.ownerId())).thenReturn(List.of(20L));
        try (var task = service.trackTask(10); var reply = service.trackReply(20)) {
            service.heartbeat();
            verify(mapper).renewTasks(List.of(10L), service.ownerId(), 60_000_000L);
            verify(mapper).renewReplies(List.of(20L), service.ownerId(), 60_000_000L);
        }
        clearInvocations(mapper);
        service.heartbeat(); verifyNoInteractions(mapper);
    }

    @SuppressWarnings("unchecked")
    private ExecutionLeaseService service(TaskExecutorProperties properties, ExecutionLeaseMapper mapper) {
        return new ExecutionLeaseService(mapper, properties, mock(PlatformTransactionManager.class), mock(ObjectProvider.class));
    }
}
