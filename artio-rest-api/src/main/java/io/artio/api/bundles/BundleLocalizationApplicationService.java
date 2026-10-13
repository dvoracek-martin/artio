package io.artio.api.bundles;

import io.artio.api.catalog.CatalogNotFoundException;
import io.artio.catalog.LocaleCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BundleLocalizationApplicationService {
    private final JdbcTemplate jdbc;
    private final BundleApplicationService bundles;

    public BundleLocalizationApplicationService(JdbcTemplate jdbc, BundleApplicationService bundles) {
        this.jdbc = jdbc;
        this.bundles = bundles;
    }

    @Transactional(readOnly = true)
    public List<LocalizedBundleView> resolveAll(String requestedLocale) {
        LocaleCode locale = new LocaleCode(requestedLocale);
        return bundles.findAll().stream().map(bundle -> resolve(bundle, locale)).toList();
    }

    @Transactional(readOnly = true)
    public LocalizedBundleView resolve(UUID bundleId, String requestedLocale) {
        return resolve(bundles.findById(bundleId), new LocaleCode(requestedLocale));
    }

    @Transactional(readOnly = true)
    public TranslationView findTranslation(UUID bundleId, String requestedLocale) {
        LocaleCode locale = new LocaleCode(requestedLocale);
        BundleApplicationService.BundleView bundle = bundles.findById(bundleId);
        String name = jdbc.query("SELECT name FROM commerce_bundle_translations WHERE bundle_id=? AND locale=?",
                rs -> rs.next() ? rs.getString(1) : null, bundleId, locale.value());
        Map<String, String> groupNames = new LinkedHashMap<>();
        jdbc.query("SELECT group_code,name FROM commerce_bundle_option_group_translations WHERE bundle_id=? AND locale=? ORDER BY group_code",
                (RowCallbackHandler) rs -> groupNames.put(rs.getString(1), rs.getString(2)), bundleId, locale.value());
        return new TranslationView(bundle.id(), locale.value(), name, groupNames);
    }

    public TranslationView upsert(UUID bundleId, String requestedLocale, TranslationRequest request) {
        LocaleCode locale = new LocaleCode(requestedLocale);
        BundleApplicationService.BundleView bundle = bundles.findById(bundleId);
        String name = label(request.name(), 500, "Bundle name");
        jdbc.update("""
                INSERT INTO commerce_bundle_translations(bundle_id,locale,name,updated_at) VALUES (?,?,?,now())
                ON CONFLICT(bundle_id,locale) DO UPDATE SET name=EXCLUDED.name,updated_at=now()
                """, bundleId, locale.value(), name);
        Map<String, String> requested = request.optionGroupNames() == null ? Map.of() : request.optionGroupNames();
        Map<String, String> knownGroups = bundle.optionGroups().stream().collect(java.util.stream.Collectors.toMap(
                BundleApplicationService.OptionGroupView::code, BundleApplicationService.OptionGroupView::name));
        if (!knownGroups.keySet().containsAll(requested.keySet())) {
            throw new IllegalArgumentException("Translation contains an unknown bundle option group");
        }
        requested.forEach((code, translatedName) -> jdbc.update("""
                INSERT INTO commerce_bundle_option_group_translations(bundle_id,group_code,locale,name,updated_at)
                VALUES (?,?,?,?,now())
                ON CONFLICT(bundle_id,group_code,locale) DO UPDATE SET name=EXCLUDED.name,updated_at=now()
                """, bundleId, code, locale.value(), label(translatedName, 200, "Option group name")));
        return findTranslation(bundleId, locale.value());
    }

    private LocalizedBundleView resolve(BundleApplicationService.BundleView bundle, LocaleCode locale) {
        Map<String, String> bundleNames = jdbc.query("SELECT locale,name FROM commerce_bundle_translations WHERE bundle_id=?",
                rs -> { Map<String, String> values = new LinkedHashMap<>(); while (rs.next()) values.put(rs.getString(1), rs.getString(2)); return values; }, bundle.id());
        Map<String, Map<String, String>> groups = new LinkedHashMap<>();
        jdbc.query("SELECT group_code,locale,name FROM commerce_bundle_option_group_translations WHERE bundle_id=?",
                (RowCallbackHandler) rs -> groups.computeIfAbsent(rs.getString(1), ignored -> new LinkedHashMap<>()).put(rs.getString(2), rs.getString(3)), bundle.id());
        List<LocalizedOptionGroup> localizedGroups = bundle.optionGroups().stream().map(group -> new LocalizedOptionGroup(
                group.code(), group.name(), resolveLabel(locale.value(), groups.get(group.code()), group.name()),
                group.minimumSelections(), group.maximumSelections(), group.choices())).toList();
        return new LocalizedBundleView(bundle.id(), bundle.name(), resolveLabel(locale.value(), bundleNames, bundle.name()),
                locale.value(), bundle.sku(), bundle.type(), bundle.active(), bundle.components(), localizedGroups, bundle.pricingRule());
    }

    private static String resolveLabel(String locale, Map<String, String> translations, String base) {
        if (translations == null) return base;
        return translations.getOrDefault(locale, translations.getOrDefault("en", base));
    }

    private static String label(String value, int max, String field) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new IllegalArgumentException(field + " is required and limited to " + max + " characters");
        }
        return value.trim();
    }

    public record TranslationRequest(String name, Map<String, String> optionGroupNames) { }
    public record TranslationView(UUID bundleId, String locale, String name, Map<String, String> optionGroupNames) { }
    public record LocalizedOptionGroup(String code, String name, String localizedName, int minimumSelections,
            int maximumSelections, List<BundleApplicationService.ComponentView> choices) { }
    public record LocalizedBundleView(UUID id, String name, String localizedName, String locale, String sku,
            io.artio.bundles.BundleType type, boolean active, List<BundleApplicationService.ComponentView> components,
            List<LocalizedOptionGroup> optionGroups, io.artio.bundles.BundlePricingRule pricingRule) { }
}
