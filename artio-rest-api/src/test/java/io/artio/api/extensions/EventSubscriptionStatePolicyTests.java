package io.artio.api.extensions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventSubscriptionStatePolicyTests {
    @Test
    void aPendingSubscriptionNeedsSuperadminApprovalBeforeActivation() {
        assertThat(EventSubscriptionStatePolicy.rejection("PENDING_APPROVAL", "ACTIVE", false))
                .isEqualTo(EventSubscriptionStatePolicy.Rejection.SUPERADMIN_REQUIRED);
        assertThat(EventSubscriptionStatePolicy.rejection("PENDING_APPROVAL", "ACTIVE", true))
                .isEqualTo(EventSubscriptionStatePolicy.Rejection.NONE);
    }

    @Test
    void pendingSubscriptionMayBeDisabledButCannotSkipApprovalIntoAnotherState() {
        assertThat(EventSubscriptionStatePolicy.rejection("PENDING_APPROVAL", "DISABLED", false))
                .isEqualTo(EventSubscriptionStatePolicy.Rejection.NONE);
        assertThat(EventSubscriptionStatePolicy.rejection("PENDING_APPROVAL", "PAUSED", true))
                .isEqualTo(EventSubscriptionStatePolicy.Rejection.APPROVE_OR_DISABLE_REQUIRED);
    }

    @Test
    void alreadyApprovedSubscriptionsKeepTheirNormalOperatorStateTransitions() {
        assertThat(EventSubscriptionStatePolicy.rejection("ACTIVE", "PAUSED", false))
                .isEqualTo(EventSubscriptionStatePolicy.Rejection.NONE);
        assertThat(EventSubscriptionStatePolicy.rejection("PAUSED", "ACTIVE", false))
                .isEqualTo(EventSubscriptionStatePolicy.Rejection.NONE);
    }

    @Test
    void rejectsUnknownAndMissingStates() {
        assertThat(EventSubscriptionStatePolicy.rejection("ACTIVE", "APPROVED", true))
                .isEqualTo(EventSubscriptionStatePolicy.Rejection.INVALID_STATE);
        assertThat(EventSubscriptionStatePolicy.rejection("PENDING_APPROVAL", null, true))
                .isEqualTo(EventSubscriptionStatePolicy.Rejection.INVALID_STATE);
    }
}
