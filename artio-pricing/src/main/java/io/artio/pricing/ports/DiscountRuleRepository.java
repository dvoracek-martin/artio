package io.artio.pricing.ports;
import io.artio.pricing.DiscountRule; import io.artio.pricing.DiscountRuleId; import java.util.Collection; import java.util.List; import java.util.Optional; import java.util.UUID;
public interface DiscountRuleRepository {
    Optional<DiscountRule> findById(DiscountRuleId id);
    List<DiscountRule> findAll();
    List<DiscountRule> findAvailableForUse();
    void lockLimitedRules();
    void redeemAppliedRules(UUID orderId, Collection<UUID> ruleIds);
    void save(DiscountRule rule);
}
