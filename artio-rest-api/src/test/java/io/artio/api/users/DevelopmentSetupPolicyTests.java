package io.artio.api.users;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DevelopmentSetupPolicyTests {
    @Test
    void allowsExplicitSkipOnlyForDevProfile() {
        assertThatCode(() -> DevelopmentSetupPolicy.validate(environment("dev"), true))
                .doesNotThrowAnyException();
        assertThatCode(() -> DevelopmentSetupPolicy.validate(environment("dev"), false))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSkipOutsideDevAndWhenProductionIsActive() {
        assertThatThrownBy(() -> DevelopmentSetupPolicy.validate(environment("test"), true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("only with the dev profile");
        assertThatThrownBy(() -> DevelopmentSetupPolicy.validate(environment("dev", "production"), true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("forbidden in production");
    }

    private static MockEnvironment environment(String... profiles) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }
}
