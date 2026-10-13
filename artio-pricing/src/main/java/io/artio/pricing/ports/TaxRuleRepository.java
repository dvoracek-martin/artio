package io.artio.pricing.ports;
import io.artio.pricing.TaxRule; import io.artio.pricing.TaxRuleId; import java.util.List; import java.util.Optional;
import io.artio.pricing.TaxCategory; import java.util.UUID;
public interface TaxRuleRepository {
    Optional<TaxRule> findById(TaxRuleId id);
    List<TaxRule> findAll();
    void save(TaxRule rule);
    TaxCategory categoryFor(UUID sellableId);
    void assignCategory(UUID sellableId, TaxCategory category);
}
