package com.coreissuer.api.domain;

import com.coreissuer.common.domain.TransferStateMachine;
import com.coreissuer.common.domain.TransferStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferStateMachineTest {
    @Test
    void permitsOnlyDeclaredTransferTransitions() {
        assertThat(TransferStateMachine.transition(TransferStatus.INITIATED, TransferStatus.PROCESSING))
                .isEqualTo(TransferStatus.PROCESSING);
        assertThat(TransferStateMachine.transition(TransferStatus.PROCESSING, TransferStatus.COMPLETED))
                .isEqualTo(TransferStatus.COMPLETED);
        assertThat(TransferStateMachine.transition(TransferStatus.PROCESSING, TransferStatus.FAILED))
                .isEqualTo(TransferStatus.FAILED);
    }

    @Test
    void rejectsInvalidAndTerminalTransitions() {
        assertThatThrownBy(() -> TransferStateMachine.transition(TransferStatus.INITIATED, TransferStatus.COMPLETED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TransferStateMachine.transition(TransferStatus.FAILED, TransferStatus.PROCESSING))
                .isInstanceOf(IllegalStateException.class);
    }
}
