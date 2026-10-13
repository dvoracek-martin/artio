package io.artio.bundles;
public record BundleCreated(BundleId bundleId,BundleType type,BundleSku sku) implements BundleEvent {}
