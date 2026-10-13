package io.artio.bundles;
public record BundleActiveChanged(BundleId bundleId,boolean active) implements BundleEvent {}
