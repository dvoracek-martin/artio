package io.artio.api.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Rewraps persisted application ciphertext using the currently configured active key. No key material is accepted over HTTP. */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/admin/system/security/encryption")
public class EncryptionKeyRotationController {
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final EncryptionService encryption;

    public EncryptionKeyRotationController(JdbcTemplate jdbc, ObjectMapper mapper, EncryptionService encryption) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.encryption = encryption;
    }

    @PostMapping("/rotate")
    @Transactional
    public RotationResult rotate(JwtAuthenticationToken actor) {
        requireOwner(actor);
        var mfaRows = jdbc.query("SELECT user_id,encrypted_secret FROM mfa_methods ORDER BY user_id FOR UPDATE",
                (rs, row) -> new MfaRow(rs.getObject("user_id", UUID.class), rs.getString("encrypted_secret")));
        int mfaUpdated = 0;
        for (MfaRow row : mfaRows) {
            String rotated = encryption.reencryptWithActiveKey(row.ciphertext());
            if (!rotated.equals(row.ciphertext())) {
                jdbc.update("UPDATE mfa_methods SET encrypted_secret=? WHERE user_id=?", rotated, row.userId());
                mfaUpdated++;
            }
        }

        var extensionRows = jdbc.query("SELECT plugin_id,encrypted_secrets::text FROM extension_plugins WHERE encrypted_secrets<>'{}'::jsonb ORDER BY plugin_id FOR UPDATE",
                (rs, row) -> new ExtensionRow(rs.getString("plugin_id"), rs.getString("encrypted_secrets")));
        int extensionSecretsUpdated = 0;
        for (ExtensionRow row : extensionRows) {
            Map<String, String> secrets = readSecrets(row.ciphertexts());
            var rotated = new java.util.LinkedHashMap<>(secrets);
            boolean changed = false;
            for (var entry : secrets.entrySet()) {
                String ciphertext = encryption.reencryptWithActiveKey(entry.getValue());
                if (!ciphertext.equals(entry.getValue())) {
                    rotated.put(entry.getKey(), ciphertext);
                    extensionSecretsUpdated++;
                    changed = true;
                }
            }
            if (changed) jdbc.update("UPDATE extension_plugins SET encrypted_secrets=?::jsonb,updated_at=now() WHERE plugin_id=?",
                    write(rotated), row.pluginId());
        }

        var deadLetterRows = jdbc.query("""
                SELECT id,encrypted_payload FROM kafka_consumer_dead_letters
                WHERE encrypted_payload IS NOT NULL ORDER BY id FOR UPDATE
                """, (rs, row) -> new DeadLetterRow(rs.getObject("id", UUID.class), rs.getString("encrypted_payload")));
        int deadLetterPayloadsUpdated = 0;
        for (DeadLetterRow row : deadLetterRows) {
            String rotated = encryption.reencryptWithActiveKey(row.ciphertext());
            if (!rotated.equals(row.ciphertext())) {
                jdbc.update("UPDATE kafka_consumer_dead_letters SET encrypted_payload=? WHERE id=?", rotated, row.id());
                deadLetterPayloadsUpdated++;
            }
        }

        UUID actorId = actorId(actor);
        jdbc.update("INSERT INTO audit_logs(id,user_id,action,path,status,occurred_at) VALUES (?,?,?,?,?,now())",
                UUID.randomUUID(), actorId, "UPDATE", "/api/v1/admin/system/security/encryption/rotate", 200);
        return new RotationResult(encryption.activeKeyId(), mfaUpdated, extensionSecretsUpdated, deadLetterPayloadsUpdated);
    }

    private Map<String, String> readSecrets(String json) {
        try { return mapper.readValue(json, STRING_MAP); }
        catch (Exception failure) { throw new IllegalStateException("Stored extension secret envelope is invalid", failure); }
    }

    private String write(Map<String, String> secrets) {
        try { return mapper.writeValueAsString(secrets); }
        catch (Exception failure) { throw new IllegalStateException("Could not serialize rotated extension secrets", failure); }
    }

    private static void requireOwner(JwtAuthenticationToken actor) {
        if (actor == null || !"SUPERADMIN".equals(actor.getToken().getClaimAsString("role"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the system owner can rotate encryption keys");
        }
    }

    private static UUID actorId(JwtAuthenticationToken actor) {
        try { return UUID.fromString(actor.getToken().getSubject()); }
        catch (RuntimeException ignored) { return null; }
    }

    private record MfaRow(UUID userId, String ciphertext) { }
    private record ExtensionRow(String pluginId, String ciphertexts) { }
    private record DeadLetterRow(UUID id, String ciphertext) { }
    public record RotationResult(String activeKeyId, int mfaSecretsRewrapped, int extensionSecretsRewrapped,
            int deadLetterPayloadsRewrapped) { }
}
