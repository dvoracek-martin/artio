package io.artio.api.catalog.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.extensions.CommerceCapabilityInvoker;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

class PluginMediaStorageTests {
    private final CommerceCapabilityInvoker invoker = mock(CommerceCapabilityInvoker.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final org.springframework.jdbc.core.JdbcTemplate jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
    private final PluginMediaStorage storage = new PluginMediaStorage(invoker, jdbc, mapper);
    private final String key = UUID.randomUUID() + ".png";
    private final CommerceCapabilityInvoker.Owner owner = new CommerceCapabilityInvoker.Owner(UUID.randomUUID(), 7L, 2,
            "a".repeat(64), Map.of());

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void storageMetadataStartsEmpty() {
        org.mockito.Mockito.doReturn(java.util.List.of()).when(jdbc).query(any(String.class), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class));
        when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(1);
    }

    @Test
    void storesOnlyAfterEveryPlacementAcknowledgesTheSameChecksum() throws Exception {
        byte[] bytes = "media-bytes".getBytes(StandardCharsets.UTF_8);
        String checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        when(invoker.invoke(eq("storage.object.v1"), eq("put"), eq(Map.of()), eq(null), eq(true), any(), any()))
                .thenReturn(new CommerceCapabilityInvoker.Invocation(mapper.readTree("""
                        {"receipts":[{"sha256":"%s","versionId":"primary-v1"},{"sha256":"%s","versionId":"replica-v1"}],"allReplicasAcknowledged":true,"targetInstanceIds":["%s","%s"]}
                        """.formatted(checksum, checksum, owner.instanceId(), UUID.randomUUID())), owner));

        storage.store(key, bytes);

        verify(invoker).invoke(eq("storage.object.v1"), eq("put"), eq(Map.of()), eq(null), eq(true), any(), any());
    }

    @Test
    void rejectsPartialOrCorruptReplicaWrites() throws Exception {
        byte[] bytes = "media-bytes".getBytes(StandardCharsets.UTF_8);
        when(invoker.invoke(eq("storage.object.v1"), eq("put"), eq(Map.of()), eq(null), eq(true), any(), any()))
                .thenReturn(new CommerceCapabilityInvoker.Invocation(mapper.readTree("""
                        {"receipts":[{"sha256":"bad","versionId":"v1"}],"allReplicasAcknowledged":false,"targetInstanceIds":["%s"]}
                        """.formatted(owner.instanceId())), owner));

        assertThatThrownBy(() -> storage.store(key, bytes)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("replicas");
    }

    @Test
    void splitsLargeObjectsIntoIndividuallyBoundedDurableChunks() throws Exception {
        byte[] bytes = new byte[600 * 1024];
        java.util.Arrays.fill(bytes, (byte) 7);
        String checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        when(invoker.invoke(eq("storage.object.v1"), eq("put"), eq(Map.of()), eq(null), eq(true), any(), any()))
                .thenReturn(new CommerceCapabilityInvoker.Invocation(mapper.readTree("""
                        {"receipts":[{"sha256":"%s","versionId":"primary-v1"}],"allReplicasAcknowledged":true,"targetInstanceIds":["%s"]}
                        """.formatted(checksum, owner.instanceId())), owner));

        storage.store(key, bytes);

        verify(invoker, times(2)).invoke(eq("storage.object.v1"), eq("put"), eq(Map.of()), eq(null), eq(true), any(), any());
    }

    @Test
    void verifiesContentChecksumOnReadAndRequiresTombstoneAcknowledgementsOnDelete() throws Exception {
        byte[] bytes = "media-bytes".getBytes(StandardCharsets.UTF_8);
        String checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("content_checksum"), eq(String.class), eq(key))).thenReturn(checksum);
        when(invoker.invoke(eq("storage.object.v1"), eq("get"), eq(Map.of()), eq(null), eq(true), any(), any()))
                .thenReturn(new CommerceCapabilityInvoker.Invocation(mapper.readTree("""
                        {"chunkBase64":"%s","sha256":"%s","chunkIndex":0,"totalChunks":1}
                        """.formatted(java.util.Base64.getEncoder().encodeToString(bytes), checksum)), owner));
        assertThat(storage.read(key)).isEqualTo(bytes);

        when(invoker.invoke(eq("storage.object.v1"), eq("delete"), eq(Map.of()), eq(null), eq(true), any(), any()))
                .thenReturn(new CommerceCapabilityInvoker.Invocation(mapper.readTree("""
                        {"receipts":[{"deleted":true}],"allReplicasAcknowledged":true}
                        """), owner));
        storage.delete(key);
        verify(invoker).invoke(eq("storage.object.v1"), eq("delete"), eq(Map.of()), eq(null), eq(true), any(), any());
    }

    @Test
    void servesUnbackfilledMediaFromTheConfiguredSourceUntilVerifiedCopyExists() {
        byte[] legacyBytes = "legacy-media".getBytes(StandardCharsets.UTF_8);
        var sourceFactory = mock(MediaStorageMigrationSourceFactory.class);
        var source = mock(MediaStorage.class);
        when(sourceFactory.open()).thenReturn(source);
        when(source.read(key)).thenReturn(legacyBytes);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("content_checksum"), eq(String.class), eq(key)))
                .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
        var withFallback = new PluginMediaStorage(invoker, jdbc, mapper, sourceFactory);

        assertThat(withFallback.read(key)).isEqualTo(legacyBytes);
        verify(source).read(key);
        verify(source, org.mockito.Mockito.never()).delete(key);
    }
}
