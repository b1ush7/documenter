package com.documenter.component;

import com.documenter.configuration.TaskExecutorProperties;
import com.documenter.mapper.AiMessageMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class AiReplyRecoveryRunnerTest {
    @Test
    void startupClosesInterruptedReplies() {
        var messages = mock(AiMessageMapper.class);
        new AiReplyRecoveryRunner(messages, new TaskExecutorProperties()).run(null);
        verify(messages).failInterruptedReplies();
    }

    @Test
    void disabledRecoveryDoesNotTouchAnotherInstancesReplies() {
        var messages = mock(AiMessageMapper.class);
        var properties = new TaskExecutorProperties();
        properties.setRecoverOnStartup(false);
        new AiReplyRecoveryRunner(messages, properties).run(null);
        verifyNoInteractions(messages);
    }
}
