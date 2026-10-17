package io.artio.api.payments;

import io.artio.extensions.commerce.PaymentProvider;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/admin/payments/method-settings")
public class PaymentMethodSettingsController {
    private final ObjectProvider<PaymentProvider> providers;
    private final PaymentMethodPreferences preferences;

    public PaymentMethodSettingsController(ObjectProvider<PaymentProvider> providers, PaymentMethodPreferences preferences) {
        this.providers = providers;
        this.preferences = preferences;
    }

    @GetMapping
    public MethodSettingsView read(@RequestParam String country, @RequestParam String currency, JwtAuthenticationToken auth) {
        Market market = market(country, currency);
        PaymentProvider provider = providers.getIfAvailable();
        boolean canManage = isSuperadmin(auth);
        if (provider == null) return new MethodSettingsView(market.country(), market.currency(), false, false, canManage, List.of(), List.of());
        PaymentProvider.PaymentMethodDiscovery discovery = provider.discoverMethods(
                new PaymentProvider.PaymentDiscoveryRequest(market.country(), market.currency(), null));
        return view(market, discovery, canManage);
    }

    @PutMapping
    public MethodSettingsView update(@RequestParam String country, @RequestParam String currency,
            @RequestBody MethodSettingsRequest request, JwtAuthenticationToken auth) {
        if (!isSuperadmin(auth))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a superadmin can change payment-method settings");
        Market market = market(country, currency);
        PaymentProvider provider = providers.getIfAvailable();
        if (provider == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No payment provider is configured");
        PaymentProvider.PaymentMethodDiscovery discovery = provider.discoverMethods(
                new PaymentProvider.PaymentDiscoveryRequest(market.country(), market.currency(), null));
        if (discovery.partialFailure())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Payment methods cannot be saved while provider discovery is incomplete");
        List<PaymentMethodPreferences.MethodPreferenceInput> requested = validate(request, discovery.methods());
        UUID updatedBy;
        try { updatedBy = UUID.fromString(auth.getToken().getSubject()); }
        catch (RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user identity is invalid"); }
        preferences.save(requested, updatedBy);
        return view(market, discovery, true);
    }

    private MethodSettingsView view(Market market, PaymentProvider.PaymentMethodDiscovery discovery, boolean canManage) {
        return new MethodSettingsView(market.country(), market.currency(), true, discovery.partialFailure(), canManage,
                discovery.failures(), preferences.withPreferences(discovery.methods()));
    }

    private static boolean isSuperadmin(JwtAuthenticationToken auth) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_SUPERADMIN".equals(authority.getAuthority()));
    }

    private static List<PaymentMethodPreferences.MethodPreferenceInput> validate(
            MethodSettingsRequest request, List<PaymentProvider.AvailablePaymentMethod> discovered) {
        if (request == null || request.methods() == null || request.methods().size() != discovered.size()
                || request.methods().size() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Submit the complete discovered payment-method list");
        Set<MethodIdentity> expected = discovered.stream().map(PaymentMethodSettingsController::identity)
                .collect(java.util.stream.Collectors.toSet());
        Set<MethodIdentity> received = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (var item : request.methods()) {
            if (item == null || item.methodId() == null || !item.methodId().matches("[a-z][a-z0-9._-]{0,63}")
                    || item.providerPluginId() == null || item.providerPluginId().isBlank() || item.providerPluginId().length() > 128
                    || item.displayOrder() < 0 || item.displayOrder() > 10000)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment-method settings contain invalid values");
            MethodIdentity identity = identity(item);
            if (!expected.contains(identity) || !received.add(identity) || !orders.add(item.displayOrder()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment-method settings must uniquely match the discovered catalog");
        }
        if (!received.equals(expected))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Submit the complete discovered payment-method list");
        return List.copyOf(request.methods());
    }

    private static MethodIdentity identity(PaymentProvider.AvailablePaymentMethod method) {
        return new MethodIdentity(method.providerPluginId(), method.providerInstanceId(), method.methodId());
    }

    private static MethodIdentity identity(PaymentMethodPreferences.MethodPreferenceInput item) {
        return new MethodIdentity(item.providerPluginId(), item.providerInstanceId(), item.methodId());
    }

    private static Market market(String country, String currency) {
        if (country == null || !country.trim().matches("[A-Za-z]{2}") || currency == null || !currency.trim().matches("[A-Za-z]{3}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment-method settings require an ISO country and currency");
        return new Market(country.trim().toUpperCase(java.util.Locale.ROOT), currency.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private record Market(String country, String currency) { }
    private record MethodIdentity(String pluginId, UUID instanceId, String methodId) { }
    public record MethodSettingsRequest(List<PaymentMethodPreferences.MethodPreferenceInput> methods) { }
    public record MethodSettingsView(String country, String currency, boolean providerAvailable, boolean partialFailure, boolean canManage,
            List<PaymentProvider.ProviderFailure> failures, List<PaymentMethodPreferences.MethodPreferenceView> methods) { }
}
