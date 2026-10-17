package io.artio.api.extensions;

import java.util.Set;

/** Core authorization boundary for pending event subscriptions. */
final class EventSubscriptionStatePolicy {
    private static final Set<String> MANAGEABLE_STATES = Set.of("ACTIVE", "PAUSED", "DRAINING", "DISABLED");

    private EventSubscriptionStatePolicy() { }

    static Rejection rejection(String currentState, String requestedState, boolean superadmin) {
        if (requestedState == null || !MANAGEABLE_STATES.contains(requestedState)) return Rejection.INVALID_STATE;
        if (!"PENDING_APPROVAL".equals(currentState)) return Rejection.NONE;
        if ("ACTIVE".equals(requestedState)) return superadmin ? Rejection.NONE : Rejection.SUPERADMIN_REQUIRED;
        return "DISABLED".equals(requestedState) ? Rejection.NONE : Rejection.APPROVE_OR_DISABLE_REQUIRED;
    }

    enum Rejection { NONE, INVALID_STATE, SUPERADMIN_REQUIRED, APPROVE_OR_DISABLE_REQUIRED }
}
