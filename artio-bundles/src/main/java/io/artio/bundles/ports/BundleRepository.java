package io.artio.bundles.ports;
import io.artio.bundles.Bundle;import io.artio.bundles.BundleId;import java.util.List;import java.util.Optional;
public interface BundleRepository { Optional<Bundle> findById(BundleId id); List<Bundle> findAll(); void save(Bundle bundle); }
