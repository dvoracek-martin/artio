package io.artio.bundles;
import io.artio.kernel.Money;
public record BundleQuote(Money componentTotal,Money perBundlePrice,Money totalPrice) {}
