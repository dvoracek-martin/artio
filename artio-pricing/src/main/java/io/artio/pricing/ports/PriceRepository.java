package io.artio.pricing.ports;
import io.artio.pricing.Price; import io.artio.pricing.PriceId; import java.time.Instant; import java.util.List; import java.util.Optional; import java.util.UUID;
public interface PriceRepository { Optional<Price> findById(PriceId id); List<Price> findAll(); List<Price> findApplicable(UUID sellableId, String market, String channel, Instant at); void save(Price price); }
