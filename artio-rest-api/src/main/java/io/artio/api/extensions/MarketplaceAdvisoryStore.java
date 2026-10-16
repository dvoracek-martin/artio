package io.artio.api.extensions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically persists verified feed state; exact digest bans are insert-only and never removed. */
@Service
public class MarketplaceAdvisoryStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public MarketplaceAdvisoryStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Transactional
  public ApplyResult apply(SignedMarketplaceAdvisoryVerifier.VerifiedSnapshot snapshot) {
    FeedState current =
        jdbc.query(
            "SELECT sequence,payload_sha256 FROM extension_marketplace_advisory_state WHERE singleton_id=true FOR UPDATE",
            rs -> rs.next() ? new FeedState(rs.getLong(1), rs.getString(2)) : null);
    if (current == null) throw new IllegalStateException("Core advisory feed state row is missing");
    if (snapshot.sequence() < current.sequence())
      throw new IllegalArgumentException("Marketplace advisory sequence moved backwards");
    if (snapshot.sequence() == current.sequence()) {
      if (!snapshot.payloadSha256().equals(current.payloadSha256()))
        throw new IllegalArgumentException("Marketplace reused an advisory sequence for different content");
      jdbc.update(
          "UPDATE extension_marketplace_advisory_state SET last_attempt_at=now(),last_success_at=now(),last_error_code=NULL,issued_at=?,expires_at=? WHERE singleton_id=true",
          java.sql.Timestamp.from(snapshot.issuedAt()),
          java.sql.Timestamp.from(snapshot.expiresAt()));
      audit("UNCHANGED", snapshot, null);
      return new ApplyResult(false, snapshot.sequence());
    }

    for (SignedMarketplaceAdvisoryVerifier.Advisory advisory : snapshot.advisories()) {
      jdbc.update(
          "INSERT INTO extension_marketplace_release_advisories(plugin_id,version,advisory_id,release_descriptor_digest,image_digests,banned_image_digests,"
              + "support_status,security_status,status,severity,reason_code,explanations,advisory_issued_at,feed_sequence) "
              + "VALUES (?,?,?,? ,?::jsonb,?::jsonb,?,?,?,?,?,?::jsonb,?,?) "
              + "ON CONFLICT(plugin_id,version) DO UPDATE SET advisory_id=EXCLUDED.advisory_id,"
              + "release_descriptor_digest=EXCLUDED.release_descriptor_digest,image_digests=EXCLUDED.image_digests,"
              + "banned_image_digests=EXCLUDED.banned_image_digests,support_status=EXCLUDED.support_status,"
              + "security_status=EXCLUDED.security_status,status=EXCLUDED.status,severity=EXCLUDED.severity,"
              + "reason_code=EXCLUDED.reason_code,explanations=EXCLUDED.explanations,"
              + "advisory_issued_at=EXCLUDED.advisory_issued_at,feed_sequence=EXCLUDED.feed_sequence,updated_at=now()",
          advisory.pluginId(),
          advisory.version(),
          advisory.advisoryId(),
          advisory.releaseDescriptorDigest(),
          json(advisory.imageDigests()),
          json(advisory.bannedImageDigests()),
          advisory.supportStatus(),
          advisory.securityStatus(),
          advisory.status(),
          advisory.severity(),
          advisory.reasonCode(),
          json(advisory.explanations()),
          java.sql.Timestamp.from(advisory.issuedAt()),
          advisory.sequence());
      for (String digest : advisory.bannedImageDigests()) {
        jdbc.update(
            "INSERT INTO extension_marketplace_banned_artifacts(image_digest,first_plugin_id,first_version,release_descriptor_digest,reason_code,feed_sequence) "
                + "VALUES (?,?,?,?,?,?) ON CONFLICT(image_digest) DO NOTHING",
            digest,
            advisory.pluginId(),
            advisory.version(),
            advisory.releaseDescriptorDigest(),
            advisory.reasonCode(),
            advisory.sequence());
      }
    }
    jdbc.update(
        "UPDATE extension_marketplace_advisory_state SET sequence=?,payload_sha256=?,issued_at=?,expires_at=?,last_attempt_at=now(),last_success_at=now(),last_error_code=NULL WHERE singleton_id=true",
        snapshot.sequence(),
        snapshot.payloadSha256(),
        java.sql.Timestamp.from(snapshot.issuedAt()),
        java.sql.Timestamp.from(snapshot.expiresAt()));
    audit("APPLIED", snapshot, null);
    return new ApplyResult(true, snapshot.sequence());
  }

  @Transactional
  public void recordFailure(String outcome, Long sequence, String payloadSha256, String errorCode) {
    if (!List.of("REJECTED", "UNAVAILABLE").contains(outcome))
      throw new IllegalArgumentException("Invalid advisory sync failure outcome");
    jdbc.update(
        "UPDATE extension_marketplace_advisory_state SET last_attempt_at=now(),last_error_code=? WHERE singleton_id=true",
        safeCode(errorCode));
    jdbc.update(
        "INSERT INTO extension_marketplace_advisory_sync_audit(sync_id,outcome,feed_sequence,payload_sha256,error_code) VALUES (?,?,?,?,?)",
        UUID.randomUUID(),
        outcome,
        sequence,
        payloadSha256,
        safeCode(errorCode));
  }

  public SyncStatus status() {
    return jdbc.query(
        "SELECT sequence,payload_sha256,issued_at,expires_at,last_attempt_at,last_success_at,last_error_code "
            + "FROM extension_marketplace_advisory_state WHERE singleton_id=true",
        rs -> {
          if (!rs.next()) throw new IllegalStateException("Core advisory feed state row is missing");
          java.sql.Timestamp expires = rs.getTimestamp("expires_at");
          return new SyncStatus(
              rs.getLong("sequence"),
              rs.getString("payload_sha256"),
              instant(rs.getTimestamp("issued_at")),
              instant(expires),
              instant(rs.getTimestamp("last_attempt_at")),
              instant(rs.getTimestamp("last_success_at")),
              rs.getString("last_error_code"),
              expires == null || expires.toInstant().isBefore(java.time.Instant.now()));
        });
  }

  public List<ReleaseStatus> releaseStatuses() {
    return jdbc.query(
        "SELECT plugin_id,version,release_descriptor_digest,support_status,security_status,status,severity,reason_code,"
            + "image_digests::text,banned_image_digests::text,feed_sequence,updated_at "
            + "FROM extension_marketplace_release_advisories ORDER BY plugin_id,version",
        (rs, row) ->
            new ReleaseStatus(
                rs.getString("plugin_id"),
                rs.getString("version"),
                rs.getString("release_descriptor_digest"),
                rs.getString("support_status"),
                rs.getString("security_status"),
                rs.getString("status"),
                rs.getString("severity"),
                rs.getString("reason_code"),
                readStrings(rs.getString("image_digests")),
                readStrings(rs.getString("banned_image_digests")),
                rs.getLong("feed_sequence"),
                instant(rs.getTimestamp("updated_at"))));
  }

  private void audit(
      String outcome, SignedMarketplaceAdvisoryVerifier.VerifiedSnapshot snapshot, String error) {
    jdbc.update(
        "INSERT INTO extension_marketplace_advisory_sync_audit(sync_id,outcome,feed_sequence,payload_sha256,error_code) VALUES (?,?,?,?,?)",
        UUID.randomUUID(),
        outcome,
        snapshot.sequence(),
        snapshot.payloadSha256(),
        error);
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Verified Marketplace advisory could not be stored", failure);
    }
  }

  private List<String> readStrings(String json) {
    try {
      return mapper.readValue(
          json, mapper.getTypeFactory().constructCollectionType(List.class, String.class));
    } catch (Exception failure) {
      throw new IllegalStateException("Persisted advisory digest list is invalid", failure);
    }
  }

  private static java.time.Instant instant(java.sql.Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static String safeCode(String code) {
    if (code == null) return "UNKNOWN";
    String safe = code.replaceAll("[^A-Za-z0-9_]", "");
    return safe.isBlank() ? "UNKNOWN" : safe.substring(0, Math.min(64, safe.length()));
  }

  public record ApplyResult(boolean changed, long sequence) {}

  public record SyncStatus(
      long sequence,
      String payloadSha256,
      java.time.Instant issuedAt,
      java.time.Instant expiresAt,
      java.time.Instant lastAttemptAt,
      java.time.Instant lastSuccessAt,
      String lastErrorCode,
      boolean stale) {}

  public record ReleaseStatus(
      String pluginId,
      String version,
      String releaseDescriptorDigest,
      String supportStatus,
      String securityStatus,
      String status,
      String severity,
      String reasonCode,
      List<String> imageDigests,
      List<String> bannedImageDigests,
      long feedSequence,
      java.time.Instant updatedAt) {}

  private record FeedState(long sequence, String payloadSha256) {}
}
