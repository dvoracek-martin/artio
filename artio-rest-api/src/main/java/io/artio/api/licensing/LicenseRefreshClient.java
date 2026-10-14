package io.artio.api.licensing;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Controlled HTTPS refresh with no redirects, bounded body and a deadline covering body delivery too. */
public final class LicenseRefreshClient {
    private final HttpClient client;
    private final Duration timeout;

    public LicenseRefreshClient(HttpClient client, Duration timeout) {
        if (client.followRedirects() != HttpClient.Redirect.NEVER || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0) throw new IllegalArgumentException("Invalid license refresh client policy");
        this.client = client;
        this.timeout = timeout;
    }

    public byte[] fetch(String endpoint, String credentialFile) {
        final HttpRequest request;
        try {
            URI uri = URI.create(endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null) throw new IllegalArgumentException();
            var builder = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json").GET();
            if (credentialFile != null && !credentialFile.isBlank()) {
                String token;
                try (var input = Files.newInputStream(Path.of(credentialFile))) {
                    byte[] bytes = input.readNBytes(4097);
                    if (bytes.length > 4096) throw new IllegalArgumentException();
                    token = new String(bytes, StandardCharsets.UTF_8).strip();
                }
                if (!token.matches("[A-Za-z0-9._~+/-]+=*")) throw new IllegalArgumentException();
                builder.header("Authorization", "Bearer " + token);
            }
            request = builder.build();
        } catch (Exception invalid) { throw new RefreshFailure("INVALID_CONFIGURATION"); }
        final CompletableFuture<HttpResponse<byte[]>> future;
        try { future = client.sendAsync(request, ignored -> new BoundedBody()); }
        catch (Exception unavailable) { throw new RefreshFailure("UNAVAILABLE"); }
        try {
            HttpResponse<byte[]> response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) throw new RefreshFailure("UNAVAILABLE");
            return response.body();
        } catch (RefreshFailure failure) { throw failure; }
        catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof RefreshFailure bounded) throw bounded;
            throw new RefreshFailure("UNAVAILABLE");
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RefreshFailure("UNAVAILABLE");
        } catch (Exception failure) { throw new RefreshFailure("UNAVAILABLE"); }
        finally { if (!future.isDone()) future.cancel(true); }
    }

    public static final class RefreshFailure extends RuntimeException {
        private final String status;
        RefreshFailure(String status) { super("License refresh failed"); this.status = status; }
        public String status() { return status; }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > SignedEntitlementVerifier.MAX_DOCUMENT_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new RefreshFailure("INVALID_RESPONSE"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(new RefreshFailure("UNAVAILABLE")); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
