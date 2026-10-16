package io.artio.api.catalog.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Opens the explicitly configured pre-plugin media store for verified migration and compatibility reads. */
@Component
@ConditionalOnProperty(name = "artio.catalog.media.storage", havingValue = "plugins")
public class MediaStorageMigrationSourceFactory {
    private final String sourceKind;
    private final String localSourcePath;
    private final String s3Bucket;
    private final String s3Region;
    private final String s3Endpoint;
    private final boolean s3PathStyle;
    private final String s3Prefix;

    public MediaStorageMigrationSourceFactory(
            @Value("${artio.catalog.media.migration.source:local}") String sourceKind,
            @Value("${artio.catalog.media.storage-path:./var/artio/media}") String localSourcePath,
            @Value("${artio.catalog.media.s3.bucket:}") String s3Bucket,
            @Value("${artio.catalog.media.s3.region:us-east-1}") String s3Region,
            @Value("${artio.catalog.media.s3.endpoint:}") String s3Endpoint,
            @Value("${artio.catalog.media.s3.path-style:true}") boolean s3PathStyle,
            @Value("${artio.catalog.media.s3.prefix:products}") String s3Prefix) {
        this.sourceKind = sourceKind;
        this.localSourcePath = localSourcePath;
        this.s3Bucket = s3Bucket;
        this.s3Region = s3Region;
        this.s3Endpoint = s3Endpoint;
        this.s3PathStyle = s3PathStyle;
        this.s3Prefix = s3Prefix;
    }

    public MediaStorage open() {
        return switch (sourceKind) {
            case "local" -> new LocalMediaStorage(localSourcePath);
            case "s3" -> new S3MediaStorage(s3Bucket, s3Region, s3Endpoint, s3PathStyle, s3Prefix);
            default -> throw new IllegalStateException("Configure media migration source as local or s3");
        };
    }
}
