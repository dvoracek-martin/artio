package io.artio.bundles;
public record BundleRenamed(BundleId bundleId,BundleName oldName,BundleName newName) implements BundleEvent {}
