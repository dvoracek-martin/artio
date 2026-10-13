package io.artio.pricing.ports;
import io.artio.pricing.TaxRule; import io.artio.pricing.TaxRuleId; import java.util.List; import java.util.Optional;
public interface TaxRuleRepository { Optional<TaxRule> findById(TaxRuleId id); List<TaxRule> findAll(); void save(TaxRule rule); }
