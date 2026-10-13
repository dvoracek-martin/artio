package io.artio.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserTests {
    @Test void normalizesEmailAndRegistersCustomerWithSafeDefaults() {
        var user=User.registerCustomer(new UserId(UUID.randomUUID()),new Email("Shopper@example.com"),"bcrypt-hash","Ada","Lovelace");
        assertThat(user.email().value()).isEqualTo("shopper@example.com");
        assertThat(user.role()).isEqualTo(UserRole.CUSTOMER);
        assertThat(user.active()).isTrue();
        assertThat(user.emailVerified()).isFalse();
        assertThat(user.getDomainEvents()).hasSize(1).first().isInstanceOf(UserRegistered.class);
    }
    @Test void rejectsInvalidEmailAndEmptyName() {
        assertThatThrownBy(()->new Email("not-an-email")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->User.registerCustomer(new UserId(UUID.randomUUID()),new Email("ada@example.com"),"hash"," ","Lovelace")).isInstanceOf(IllegalArgumentException.class);
    }
}
