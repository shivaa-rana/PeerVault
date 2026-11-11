package com.coreissuer.common.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;

public final class TransferStateMachine {
    private static final EnumMap<TransferStatus, Set<TransferStatus>> TRANSITIONS =
            new EnumMap<>(TransferStatus.class);

    static {
        TRANSITIONS.put(TransferStatus.INITIATED, EnumSet.of(TransferStatus.PROCESSING, TransferStatus.FAILED));
        TRANSITIONS.put(TransferStatus.PROCESSING, EnumSet.of(TransferStatus.COMPLETED, TransferStatus.FAILED));
        TRANSITIONS.put(TransferStatus.COMPLETED, EnumSet.of(TransferStatus.REVERSED));
        TRANSITIONS.put(TransferStatus.FAILED, EnumSet.noneOf(TransferStatus.class));
        TRANSITIONS.put(TransferStatus.REVERSED, EnumSet.noneOf(TransferStatus.class));
    }

    private TransferStateMachine() {
    }

    public static TransferStatus transition(TransferStatus current, TransferStatus next) {
        if (!TRANSITIONS.getOrDefault(current, EnumSet.noneOf(TransferStatus.class)).contains(next)) {
            throw new IllegalStateException("Invalid transfer state transition from " + current + " to " + next);
        }
        return next;
    }
}
