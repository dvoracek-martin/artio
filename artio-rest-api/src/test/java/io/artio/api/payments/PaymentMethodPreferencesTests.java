package io.artio.api.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.artio.extensions.commerce.PaymentProvider;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PaymentMethodPreferencesTests {
    @Test
    void appliesPerInstanceVisibilityAndOrderWithoutChangingProviderIdentity() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID firstInstance = UUID.randomUUID();
        UUID secondInstance = UUID.randomUUID();
        var disabled = preferenceRow("payments.one", firstInstance, "card", false, 1);
        var enabled = preferenceRow("payments.two", secondInstance, "card", true, 0);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(disabled, 0), mapper.mapRow(enabled, 1));
        });
        var service = new PaymentMethodPreferences(jdbc);
        var first = method("payments.one", firstInstance, "card", "Card One");
        var second = method("payments.two", secondInstance, "card", "Card Two");

        var visible = service.visibleInOrder(List.of(first, second));
        var settings = service.withPreferences(List.of(first, second));

        assertThat(visible).containsExactly(second);
        assertThat(settings).extracting(PaymentMethodPreferences.MethodPreferenceView::displayName).containsExactly("Card Two", "Card One");
        assertThat(settings).extracting(PaymentMethodPreferences.MethodPreferenceView::enabled).containsExactly(true, false);
    }

    @Test
    void leavesUnconfiguredCatalogInProviderOrder() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
        var service = new PaymentMethodPreferences(jdbc);
        var twint = method("payments.one", UUID.randomUUID(), "twint", "TWINT");
        var card = method("payments.two", UUID.randomUUID(), "card", "Card");

        assertThat(service.visibleInOrder(List.of(twint, card))).containsExactly(twint, card);
        assertThat(service.withPreferences(List.of(twint, card))).extracting(PaymentMethodPreferences.MethodPreferenceView::displayOrder)
                .containsExactly(0, 1);
    }

    private static ResultSet preferenceRow(String pluginId, UUID instanceId, String methodId, boolean enabled, int order) throws Exception {
        ResultSet result = mock(ResultSet.class);
        when(result.getString("provider_plugin_id")).thenReturn(pluginId);
        when(result.getObject("provider_instance_id", UUID.class)).thenReturn(instanceId);
        when(result.getString("method_id")).thenReturn(methodId);
        when(result.getBoolean("enabled")).thenReturn(enabled);
        when(result.getInt("display_order")).thenReturn(order);
        return result;
    }

    private static PaymentProvider.AvailablePaymentMethod method(String pluginId, UUID instanceId, String methodId, String name) {
        return new PaymentProvider.AvailablePaymentMethod(instanceId + ":" + methodId, methodId, name, pluginId,
                instanceId, 1L, 0, true);
    }
}
