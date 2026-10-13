package io.artio.pricing.ports;
import io.artio.pricing.DiscountRule; import io.artio.pricing.DiscountRuleId; import java.util.List; import java.util.Optional;
public interface DiscountRuleRepository { Optional<DiscountRule> findById(DiscountRuleId id); List<DiscountRule> findAll(); void save(DiscountRule rule); }
