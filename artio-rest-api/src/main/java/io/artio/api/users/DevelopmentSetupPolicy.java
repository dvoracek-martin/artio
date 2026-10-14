package io.artio.api.users;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Guard for the development-only setup bypass. */
final class DevelopmentSetupPolicy {
    private DevelopmentSetupPolicy() { }

    static void validate(Environment environment, boolean skipRequested) {
        if (skipRequested && (!environment.acceptsProfiles(Profiles.of("dev"))
                || environment.acceptsProfiles(Profiles.of("production")))) {
            throw new IllegalStateException("ARTIO_SETUP_SKIP is allowed only with the dev profile and is forbidden in production");
        }
    }
}
