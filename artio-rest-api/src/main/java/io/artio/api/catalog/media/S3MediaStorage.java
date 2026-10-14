package io.artio.api.catalog.media;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** Private S3-compatible objects, served through the existing validated product media API. */
@Component
@ConditionalOnProperty(name = "artio.catalog.media.storage", havingValue = "s3")
public class S3MediaStorage implements MediaStorage, AutoCloseable {
    private final S3Client client;
    private final String bucket;
    private final String prefix;

    public S3MediaStorage(
            @Value("${artio.catalog.media.s3.bucket}") String bucket,
            @Value("${artio.catalog.media.s3.region:us-east-1}") String region,
            @Value("${artio.catalog.media.s3.endpoint:}") String endpoint,
            @Value("${artio.catalog.media.s3.path-style:true}") boolean pathStyle,
            @Value("${artio.catalog.media.s3.prefix:products}") String prefix) {
        this.bucket = requireBucket(bucket);
        this.prefix = normalizePrefix(prefix);
        var builder = S3Client.builder().region(Region.of(region)).forcePathStyle(pathStyle)
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(30)))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(60)).apiCallAttemptTimeout(Duration.ofSeconds(35)))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        if (!endpoint.isBlank()) {
            URI uri = URI.create(endpoint);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Configure an HTTP(S) S3 endpoint without credentials, query or fragment");
            }
            builder.endpointOverride(uri);
        }
        // Default credential chain supports environment credentials, profiles and workload roles.
        this.client = builder.build();
    }

    S3MediaStorage(S3Client client, String bucket, String prefix) {
        this.client = client;
        this.bucket = requireBucket(bucket);
        this.prefix = normalizePrefix(prefix);
    }

    @Override public void store(String key, byte[] content) {
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(objectKey(key))
                .contentType(key.endsWith(".png") ? "image/png" : "image/jpeg").build(), RequestBody.fromBytes(content));
    }
    @Override public byte[] read(String key) {
        return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(objectKey(key)).build()).asByteArray();
    }
    @Override public void delete(String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey(key)).build());
    }
    @Override public void close() { client.close(); }

    private String objectKey(String key) {
        if (key == null || !key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png)")) {
            throw new IllegalArgumentException("Invalid catalog media key");
        }
        return prefix + key;
    }
    private static String requireBucket(String bucket) {
        if (bucket == null || bucket.isBlank() || bucket.contains("/")) throw new IllegalArgumentException("Configure a catalog media S3 bucket");
        return bucket;
    }
    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) return "";
        String normalized = prefix.replaceAll("^/+|/+$", "");
        if (normalized.contains("..") || !normalized.matches("[A-Za-z0-9/_-]+")) throw new IllegalArgumentException("Invalid catalog media S3 prefix");
        return normalized + "/";
    }
}
