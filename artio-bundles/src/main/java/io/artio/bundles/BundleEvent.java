package io.artio.bundles;
import io.artio.kernel.AggregateEvent;
public sealed interface BundleEvent extends AggregateEvent permits BundleCreated,BundleRenamed,BundleActiveChanged { BundleId bundleId(); }
