package io.artio.api.payments;

import io.artio.extensions.commerce.PaymentProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies merchant visibility and display ordering to live, Core-discovered payment methods. */
@Service
public class PaymentMethodPreferences {
    private final JdbcTemplate jdbc;

    public PaymentMethodPreferences(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PaymentProvider.AvailablePaymentMethod> visibleInOrder(
            List<PaymentProvider.AvailablePaymentMethod> discovered) {
        Map<MethodKey, Preference> preferences = allPreferences();
        List<OrderedMethod> ordered = new ArrayList<>();
        for (int index = 0; index < discovered.size(); index++) {
            var method = discovered.get(index);
            Preference preference = preferences.get(key(method));
            if (preference != null && !preference.enabled()) continue;
            ordered.add(new OrderedMethod(method, preference == null ? index : preference.displayOrder(), index));
        }
        return ordered.stream().sorted(java.util.Comparator.comparingInt(OrderedMethod::order)
                        .thenComparingInt(OrderedMethod::discoveryOrder)
                        .thenComparing(item -> item.method().providerPluginId())
                        .thenComparing(item -> item.method().methodId()))
                .map(OrderedMethod::method).toList();
    }

    public List<MethodPreferenceView> withPreferences(List<PaymentProvider.AvailablePaymentMethod> discovered) {
        Map<MethodKey, Preference> preferences = allPreferences();
        List<MethodPreferenceView> result = new ArrayList<>();
        for (int index = 0; index < discovered.size(); index++) {
            var method = discovered.get(index);
            Preference preference = preferences.get(key(method));
            result.add(new MethodPreferenceView(method.methodId(), method.displayName(), method.providerPluginId(),
                    method.providerInstanceId(), preference == null || preference.enabled(),
                    preference == null ? index : preference.displayOrder()));
        }
        return result.stream().sorted(java.util.Comparator.comparingInt(MethodPreferenceView::displayOrder)
                .thenComparing(MethodPreferenceView::providerPluginId)
                .thenComparing(view -> view.providerInstanceId() == null ? "" : view.providerInstanceId().toString())
                .thenComparing(MethodPreferenceView::methodId)).toList();
    }

    @Transactional
    public void save(List<MethodPreferenceInput> methods, UUID updatedBy) {
        for (MethodPreferenceInput method : methods) {
            jdbc.update("INSERT INTO payment_method_preferences(provider_plugin_id,provider_instance_id,method_id,enabled,display_order,updated_by) "
                            + "VALUES (?,?,?,?,?,?) ON CONFLICT (provider_plugin_id, "
                            + "(COALESCE(provider_instance_id, '00000000-0000-0000-0000-000000000000'::uuid)), method_id) "
                            + "DO UPDATE SET enabled=EXCLUDED.enabled,display_order=EXCLUDED.display_order,updated_by=EXCLUDED.updated_by,updated_at=now()",
                    method.providerPluginId(), method.providerInstanceId(), method.methodId(), method.enabled(),
                    method.displayOrder(), updatedBy);
        }
    }

    private Map<MethodKey, Preference> allPreferences() {
        return jdbc.query("SELECT provider_plugin_id,provider_instance_id,method_id,enabled,display_order FROM payment_method_preferences",
                (rs, row) -> Map.entry(new MethodKey(rs.getString("provider_plugin_id"),
                                rs.getObject("provider_instance_id", UUID.class), rs.getString("method_id")),
                        new Preference(rs.getBoolean("enabled"), rs.getInt("display_order"))))
                .stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static MethodKey key(PaymentProvider.AvailablePaymentMethod method) {
        return new MethodKey(method.providerPluginId(), method.providerInstanceId(), method.methodId());
    }

    private record MethodKey(String pluginId, UUID instanceId, String methodId) { }
    private record Preference(boolean enabled, int displayOrder) { }
    private record OrderedMethod(PaymentProvider.AvailablePaymentMethod method, int order, int discoveryOrder) { }
    public record MethodPreferenceView(String methodId, String displayName, String providerPluginId,
            UUID providerInstanceId, boolean enabled, int displayOrder) { }
    public record MethodPreferenceInput(String methodId, String providerPluginId, UUID providerInstanceId,
            boolean enabled, int displayOrder) { }
}
