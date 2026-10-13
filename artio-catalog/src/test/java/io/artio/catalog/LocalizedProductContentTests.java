package io.artio.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalizedProductContentTests {
    @Test
    void resolvesRequestedLocaleThenEnglishThenBasePerField() {
        var german = new LocalizedProductContent(new LocaleCode("de"), "Kaffee", null);
        var english = new LocalizedProductContent(new LocaleCode("en"), "Coffee", "Ground coffee");

        var resolved = LocalizedProductContent.resolve("de", Map.of(german.locale(), german, english.locale(), english), "Cafe", "Base description");

        assertThat(resolved.name()).isEqualTo("Kaffee");
        assertThat(resolved.description()).isEqualTo("Ground coffee");
    }

    @Test
    void rejectsUnsupportedLocaleAndInvalidLocalizedValues() {
        assertThatThrownBy(() -> new LocaleCode("es")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalizedProductContent(new LocaleCode("fr"), " ", "Description"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
