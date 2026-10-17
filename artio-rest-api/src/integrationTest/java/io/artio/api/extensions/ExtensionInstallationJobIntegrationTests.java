package io.artio.api.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.extensions.ExtensionRuntimePort;
import io.artio.extensions.PluginManifest;
import io.artio.api.security.EncryptionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL acceptance for idempotent, durable installation job creation and operator controls. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = io.artio.api.ArtioRestApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ExtensionInstallationJobIntegrationTests {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired ExtensionInstallationJobOperations jobs;
    @Autowired EncryptionService encryption;
    @Autowired PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("artio.security.jwt-secret", () -> "integration-test-jwt-secret-value-32-bytes-minimum");
        registry.add("artio.security.encryption-key", () -> "integration-test-encryption-key-value-32-bytes-minimum");
        registry.add("artio.setup.token", () -> "integration-test-setup-token-value-32-bytes-minimum");
        registry.add("artio.workers.enabled", () -> "false");
        registry.add("artio.events.kafka.consumer-enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @BeforeEach
    void clearFixtures() {
        jdbc.update("DELETE FROM extension_installation_jobs");
        jdbc.update("DELETE FROM extension_operation_attempts WHERE plugin_id LIKE 'installation.%'");
        jdbc.update("DELETE FROM extension_plugin_instances WHERE plugin_id LIKE 'installation.%'");
        jdbc.update("DELETE FROM extension_registry_credentials");
    }

    @Test
    void persistsSnapshotAndReturnsSameJobForAnIdempotentRequest() throws Exception {
        String pluginId = "installation.acceptance";
        PluginManifest manifest = manifest(pluginId);
        jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state) VALUES (?,?::jsonb,?,'CONFIGURED')",
                pluginId, mapper.writeValueAsString(manifest), manifest.image());
        UUID actorId = UUID.randomUUID();

        var first = jobs.request(pluginId, null, "legacy:" + pluginId, 0, manifest, actorId, "acceptance-key-0001");
        var repeated = jobs.request(pluginId, null, "legacy:" + pluginId, 0, manifest, actorId, "acceptance-key-0001");

        assertThat(first.jobId()).isEqualTo(repeated.jobId());
        assertThat(first.state()).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForObject("SELECT manifest_snapshot->>'id' FROM extension_installation_jobs WHERE job_id=?", String.class,
                first.jobId())).isEqualTo(pluginId);
        assertThat(jdbc.queryForObject("SELECT permission_snapshot::text FROM extension_installation_jobs WHERE job_id=?", String.class,
                first.jobId())).contains("catalog.read");
        assertThat(jobs.list(pluginId)).extracting(ExtensionInstallationJobOperations.JobView::jobId).containsExactly(first.jobId());
        assertThatThrownBy(() -> jobs.request(pluginId, null, "legacy:" + pluginId, 0, manifest, actorId, "different-key-0001"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> jobs.request("another.plugin", null, "legacy:another.plugin", 0,
                manifest("another.plugin"), actorId, "acceptance-key-0001"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void bindsMarketplaceJobsToTheVerifiedImmutableReleaseDescriptor() throws Exception {
        String pluginId = "marketplace.installation";
        PluginManifest manifest = manifest(pluginId);
        jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state) VALUES (?,?::jsonb,?,'CONFIGURED')",
                pluginId, mapper.writeValueAsString(manifest), manifest.image());
        jdbc.update("INSERT INTO extension_plugin_releases(plugin_id,version,image_reference,manifest) VALUES (?,?,?,?::jsonb)",
                pluginId, manifest.version(), manifest.image(), mapper.writeValueAsString(manifest));
        Long releaseId = jdbc.queryForObject("SELECT release_id FROM extension_plugin_releases WHERE plugin_id=? AND version=?",
                Long.class, pluginId, manifest.version());
        String descriptorDigest = "a".repeat(64);
        jdbc.update("INSERT INTO extension_plugin_release_provenance(release_id,provenance,descriptor_digest,descriptor_snapshot) "
                        + "VALUES (?,'OFFICIAL_MARKETPLACE',?,?::jsonb)", releaseId, descriptorDigest, "{}");
        UUID actorId = UUID.randomUUID();

        var job = jobs.requestMarketplace(pluginId, null, "legacy:" + pluginId, releaseId, manifest,
                actorId, "marketplace-job-0001", descriptorDigest);

        assertThat(job.sourceKind()).isEqualTo("MARKETPLACE");
        assertThat(job.releaseId()).isEqualTo(releaseId);
        assertThat(job.releaseDigest()).isEqualTo(descriptorDigest);
        assertThatThrownBy(() -> jobs.requestMarketplace(pluginId, null, "legacy:" + pluginId, releaseId,
                manifest, actorId, "marketplace-job-0001", "b".repeat(64)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void registersOnlyTheVerifiedImmutableV2MarketplaceReleaseAndIsIdempotent() throws Exception {
        String pluginId = "installation.marketplace-register-v2";
        PluginManifest installed = releaseManifest(pluginId, "1.0.0", "a");
        jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state) VALUES (?,?::jsonb,?,'INSTALLED')",
                pluginId, mapper.writeValueAsString(installed), installed.image());
        jdbc.update("INSERT INTO extension_plugin_releases(plugin_id,version,image_reference,manifest) VALUES (?,?,?,?::jsonb)",
                pluginId, installed.version(), installed.image(), mapper.writeValueAsString(installed));
        PluginManifest published = new PluginManifest("2", pluginId, "Marketplace Release Plugin", "Artio", "2.0.0",
                "Verified v2 Marketplace release", "0.1.0", "9.0.0",
                "ghcr.io/artio/plugin@sha256:" + "b".repeat(64), List.of(), List.of(), Map.of(), List.of(), "/health", "/diagnostics");
        var publishedV2 = new io.artio.extensions.PluginManifestV2(published, List.of(), List.of());
        var descriptor = mapper.createObjectNode().put("pluginId", pluginId).put("version", published.version());
        descriptor.set("manifest", mapper.valueToTree(publishedV2));
        String digest = "c".repeat(64);
        MarketplaceCatalogClient marketplace = org.mockito.Mockito.mock(MarketplaceCatalogClient.class);
        org.mockito.Mockito.when(marketplace.verifiedReleaseDescriptor(pluginId, published.version()))
                .thenReturn(new MarketplaceCatalogClient.VerifiedReleaseDescriptor(descriptor, digest));
        UUID actorId = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,role,created_at) VALUES (?,?,?,'Test','Admin','SUPERADMIN',now())",
                actorId, "marketplace-register@example.test", "not-used");
        var registration = new OfficialMarketplaceReleaseRegistration(marketplace,
                new io.artio.extensions.PluginManifestValidator(java.util.Set.of("ghcr.io")),
                jdbc, mapper, transactionManager, jobs, "0.1.0");

        var first = registration.registerRelease(pluginId, published.version(), actorId);
        var repeated = registration.registerRelease(pluginId, published.version(), actorId);

        assertThat(first.alreadyRegistered()).isFalse();
        assertThat(repeated.alreadyRegistered()).isTrue();
        assertThat(repeated.releaseId()).isEqualTo(first.releaseId());
        assertThat(jdbc.queryForObject("SELECT manifest->'packageManifest'->>'version' FROM extension_plugin_v2_releases WHERE release_id=?",
                String.class, first.releaseId())).isEqualTo("2.0.0");
        assertThat(jdbc.queryForObject("SELECT descriptor_digest FROM extension_plugin_release_provenance WHERE release_id=?",
                String.class, first.releaseId())).isEqualTo(digest);
    }

    @Test
    void allowsOnlySafeCancellationAndExplicitRetryOfFailedJobs() throws Exception {
        String pluginId = "installation.controls";
        PluginManifest manifest = manifest(pluginId);
        jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state) VALUES (?,?::jsonb,?,'CONFIGURED')",
                pluginId, mapper.writeValueAsString(manifest), manifest.image());
        UUID actorId = UUID.randomUUID();
        var cancelled = jobs.request(pluginId, null, "legacy:" + pluginId, 0, manifest, actorId, "cancel-key-000001");
        assertThat(jobs.cancel(cancelled.jobId()).state()).isEqualTo("CANCELLED");

        var failed = jobs.request(pluginId, null, "legacy:" + pluginId, 0, manifest, actorId, "retry-key-000001");
        jdbc.update("UPDATE extension_installation_jobs SET state='FAILED',error_code='DockerUnavailable',completed_at=now() WHERE job_id=?", failed.jobId());
        var retried = jobs.retry(failed.jobId());
        assertThat(retried.state()).isEqualTo("REQUESTED");
        assertThat(retried.attemptCount()).isZero();
        assertThat(jobs.get(failed.jobId()).errorCode()).isNull();
        jdbc.update("UPDATE extension_installation_jobs SET state='FAILED',completed_at=now() WHERE job_id=?", failed.jobId());
        jobs.request(pluginId, null, "legacy:" + pluginId, 0, manifest, UUID.randomUUID(), "other-key-000001");
        assertThatThrownBy(() -> jobs.retry(failed.jobId()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void workerInstallsFromTheFrozenSnapshotAndMarksTheJobComplete() throws Exception {
        String pluginId = "installation.worker";
        PluginManifest manifest = manifest(pluginId);
        jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state) VALUES (?,?::jsonb,?,'CONFIGURED')",
                pluginId, mapper.writeValueAsString(manifest), manifest.image());
        var job = jobs.request(pluginId, null, "legacy:" + pluginId, 0, manifest, UUID.randomUUID(), "worker-key-000001");
        var runtime = org.mockito.Mockito.mock(io.artio.extensions.ExtensionRuntimePort.class);
        var runtimeBeans = new StaticListableBeanFactory(Map.of(ExtensionRuntimePort.class.getName(), runtime));
        var registryCredentials = org.mockito.Mockito.mock(ExtensionRegistryCredentialService.class);
        org.mockito.Mockito.when(registryCredentials.credentialsFor(manifest)).thenReturn(Map.of());
        var worker = new ExtensionInstallationJobWorker(jdbc, mapper,
                runtimeBeans.getBeanProvider(ExtensionRuntimePort.class), registryCredentials,
                new OciArchiveInspector(OciArchiveInspector.Limits.developmentDefaults()),
                new OciArchiveQuarantineStore(System.getProperty("java.io.tmpdir"), 2L << 30),
                new DataSourceTransactionManager(jdbc.getDataSource()), 1_800_000, 300_000);

        try {
            worker.runPending();

            assertThat(jobs.get(job.jobId()).state()).isEqualTo("INSTALLED");
            assertThat(jobs.get(job.jobId()).attemptCount()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT lifecycle_state FROM extension_plugins WHERE plugin_id=?", String.class, pluginId))
                    .isEqualTo("INSTALLED");
            org.mockito.Mockito.verify(runtime).install(manifest, Map.of());
        } finally {
            worker.stopLeaseRenewals();
        }
    }

    @Test
    void workerAppliesAQueuedV2ReleaseChangeToOnlyTheSelectedInstance() throws Exception {
        String pluginId = "installation.release-change";
        V2ReleaseFixture fixture = registerV2Plugin(pluginId, "DRAINING");
        UUID actorId = UUID.randomUUID();
        String secretCiphertext = encryption.encrypt("target-release-secret");
        Map<String, Object> targetPublicConfiguration = Map.of("endpoint", "https://v2.example.test");
        Map<String, String> targetSecrets = Map.of("apiKey", secretCiphertext);
        var job = jobs.requestReleaseChange(pluginId, fixture.instanceId(), fixture.runtimeIdentity(),
                fixture.previousReleaseId(), fixture.targetReleaseId(), fixture.previous(), fixture.target(),
                "DRAINING", targetPublicConfiguration, targetSecrets, 1,
                "UPDATE", actorId, "release-update-0001", "REGISTERED", null);
        var runtime = org.mockito.Mockito.mock(ExtensionRuntimePort.class);
        var runtimeBeans = new StaticListableBeanFactory(Map.of(ExtensionRuntimePort.class.getName(), runtime));
        var credentials = org.mockito.Mockito.mock(ExtensionRegistryCredentialService.class);
        org.mockito.Mockito.when(credentials.credentialsFor(fixture.target())).thenReturn(Map.of());
        var worker = new ExtensionInstallationJobWorker(jdbc, mapper,
                runtimeBeans.getBeanProvider(ExtensionRuntimePort.class), credentials, encryption,
                new OciArchiveInspector(OciArchiveInspector.Limits.developmentDefaults()),
                new OciArchiveQuarantineStore(System.getProperty("java.io.tmpdir"), 2L << 30),
                new DataSourceTransactionManager(jdbc.getDataSource()), 1_800_000, 300_000);
        try {
            worker.runPending();

            assertThat(jobs.get(job.jobId()).state()).isEqualTo("INSTALLED");
            assertThat(jobs.get(job.jobId()).operationKind()).isEqualTo("UPDATE");
            assertThat(jobs.get(job.jobId()).previousReleaseId()).isEqualTo(fixture.previousReleaseId());
            assertThat(jdbc.queryForObject("SELECT release_id FROM extension_plugin_instances WHERE instance_id=?",
                    Long.class, fixture.instanceId())).isEqualTo(fixture.targetReleaseId());
            assertThat(jdbc.queryForObject("SELECT lifecycle_state FROM extension_plugin_instances WHERE instance_id=?",
                    String.class, fixture.instanceId())).isEqualTo("DRAINING");
            assertThat(jdbc.queryForObject("SELECT encrypted_secrets::text FROM extension_plugin_instances WHERE instance_id=?",
                    String.class, fixture.instanceId())).contains(secretCiphertext).doesNotContain("target-release-secret");
            org.mockito.Mockito.verify(runtime, org.mockito.Mockito.times(2)).disableInstance(fixture.runtimeIdentity(), pluginId);
            org.mockito.Mockito.verify(runtime).updateInstance(fixture.runtimeIdentity(), fixture.previous(), fixture.target(), Map.of());
            int credentialGeneration = jdbc.queryForObject("SELECT credential_generation FROM extension_plugins WHERE plugin_id=?",
                    Integer.class, pluginId);
            org.mockito.Mockito.verify(runtime).enableInstance(fixture.runtimeIdentity(), fixture.target(), credentialGeneration,
                    Map.of("endpoint", "https://v2.example.test", "apiKey", "target-release-secret"));
        } finally {
            worker.stopLeaseRenewals();
        }
    }

    @Test
    void waitsForPinnedOperationsBeforeReplacingTheDrainingInstanceRelease() throws Exception {
        String pluginId = "installation.release-drain";
        V2ReleaseFixture fixture = registerV2Plugin(pluginId, "DRAINING");
        UUID operationId = UUID.randomUUID();
        jdbc.update("INSERT INTO extension_operation_attempts(attempt_id,instance_id,plugin_id,release_id,configuration_generation,"
                        + "capability_id,operation_name,scope_snapshot,permissions_snapshot,idempotency_key,request_fingerprint,request_snapshot,"
                        + "correlation_id,deadline_at,plan_snapshot,attempt_state) VALUES (?,?,?,?,0,'payment.provider.v1','authorize',"
                        + "'{}'::jsonb,'[]'::jsonb,? ,?, '{}', ?,now()+interval '1 hour','{}'::jsonb,'PENDING')",
                operationId, fixture.instanceId(), pluginId, fixture.previousReleaseId(),
                "drain-operation-0001", "a".repeat(64), UUID.randomUUID());
        var job = jobs.requestReleaseChange(pluginId, fixture.instanceId(), fixture.runtimeIdentity(),
                fixture.previousReleaseId(), fixture.targetReleaseId(), fixture.previous(), fixture.target(),
                "DRAINING", Map.of(), Map.of(), 1, "UPDATE", UUID.randomUUID(), "release-drain-0001", "REGISTERED", null);
        var runtime = org.mockito.Mockito.mock(ExtensionRuntimePort.class);
        var runtimeBeans = new StaticListableBeanFactory(Map.of(ExtensionRuntimePort.class.getName(), runtime));
        var credentials = org.mockito.Mockito.mock(ExtensionRegistryCredentialService.class);
        org.mockito.Mockito.when(credentials.credentialsFor(fixture.target())).thenReturn(Map.of());
        var worker = new ExtensionInstallationJobWorker(jdbc, mapper,
                runtimeBeans.getBeanProvider(ExtensionRuntimePort.class), credentials, encryption,
                new OciArchiveInspector(OciArchiveInspector.Limits.developmentDefaults()),
                new OciArchiveQuarantineStore(System.getProperty("java.io.tmpdir"), 2L << 30),
                new DataSourceTransactionManager(jdbc.getDataSource()), 1_800_000, 300_000);
        try {
            worker.runPending();

            assertThat(jobs.get(job.jobId()).state()).isEqualTo("WAITING_FOR_OBLIGATIONS");
            assertThat(jobs.get(job.jobId()).attemptCount()).isEqualTo(1);
            org.mockito.Mockito.verifyNoInteractions(runtime);
            jdbc.update("UPDATE extension_operation_attempts SET attempt_state='SUCCEEDED' WHERE attempt_id=?", operationId);

            worker.runPending();

            assertThat(jobs.get(job.jobId()).state()).isEqualTo("INSTALLED");
            assertThat(jobs.get(job.jobId()).attemptCount()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT release_id FROM extension_plugin_instances WHERE instance_id=?",
                    Long.class, fixture.instanceId())).isEqualTo(fixture.targetReleaseId());
            org.mockito.Mockito.verify(runtime).updateInstance(fixture.runtimeIdentity(), fixture.previous(), fixture.target(), Map.of());
        } finally {
            worker.stopLeaseRenewals();
        }
    }

    @Test
    void reclaimsAnExpiredLeaseAfterThePreviousWorkerStops() throws Exception {
        String pluginId = "installation.recovery";
        PluginManifest manifest = manifest(pluginId);
        jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state) VALUES (?,?::jsonb,?,'CONFIGURED')",
                pluginId, mapper.writeValueAsString(manifest), manifest.image());
        var job = jobs.request(pluginId, null, "legacy:" + pluginId, 0, manifest, UUID.randomUUID(), "recovery-key-0001");
        jdbc.update("UPDATE extension_installation_jobs SET state='DOWNLOADING_OR_IMPORTING',attempt_count=1,"
                        + "lease_token=?,lease_until=now()-interval '1 second' WHERE job_id=?",
                UUID.randomUUID(), job.jobId());
        var runtime = org.mockito.Mockito.mock(ExtensionRuntimePort.class);
        var runtimeBeans = new StaticListableBeanFactory(Map.of(ExtensionRuntimePort.class.getName(), runtime));
        var registryCredentials = org.mockito.Mockito.mock(ExtensionRegistryCredentialService.class);
        org.mockito.Mockito.when(registryCredentials.credentialsFor(manifest)).thenReturn(Map.of());
        var worker = new ExtensionInstallationJobWorker(jdbc, mapper,
                runtimeBeans.getBeanProvider(ExtensionRuntimePort.class), registryCredentials,
                new OciArchiveInspector(OciArchiveInspector.Limits.developmentDefaults()),
                new OciArchiveQuarantineStore(System.getProperty("java.io.tmpdir"), 2L << 30),
                new DataSourceTransactionManager(jdbc.getDataSource()), 1_800_000, 300_000);
        try {
            worker.runPending();

            assertThat(jobs.get(job.jobId()).state()).isEqualTo("INSTALLED");
            assertThat(jobs.get(job.jobId()).attemptCount()).isEqualTo(2);
            org.mockito.Mockito.verify(runtime).install(manifest, Map.of());
        } finally {
            worker.stopLeaseRenewals();
        }
    }

    @Test
    void expiredLeaseCannotFinalizePluginLifecycleState() throws Exception {
        String pluginId = "installation.expired-finalize";
        PluginManifest manifest = manifest(pluginId);
        String manifestJson = mapper.writeValueAsString(manifest);
        jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state) VALUES (?,?::jsonb,?,'CONFIGURED')",
                pluginId, manifestJson, manifest.image());
        var job = jobs.request(pluginId, null, "legacy:" + pluginId, 0, manifest, UUID.randomUUID(), "expired-finalize-0001");
        UUID staleToken = UUID.randomUUID();
        jdbc.update("UPDATE extension_installation_jobs SET state='VERIFYING_ARTIFACTS',lease_token=?,lease_until=now()-interval '1 second' "
                        + "WHERE job_id=?", staleToken, job.jobId());
        var runtime = org.mockito.Mockito.mock(ExtensionRuntimePort.class);
        var runtimeBeans = new StaticListableBeanFactory(Map.of(ExtensionRuntimePort.class.getName(), runtime));
        var worker = new ExtensionInstallationJobWorker(jdbc, mapper,
                runtimeBeans.getBeanProvider(ExtensionRuntimePort.class),
                org.mockito.Mockito.mock(ExtensionRegistryCredentialService.class),
                new OciArchiveInspector(OciArchiveInspector.Limits.developmentDefaults()),
                new OciArchiveQuarantineStore(System.getProperty("java.io.tmpdir"), 2L << 30),
                new DataSourceTransactionManager(jdbc.getDataSource()), 1_800_000, 300_000);
        try {
            var staleJob = new ExtensionInstallationJobWorker.InstallJob(job.jobId(), pluginId, null,
                    "legacy:" + pluginId, manifestJson,
                    jdbc.queryForObject("SELECT permission_snapshot::text FROM extension_installation_jobs WHERE job_id=?",
                            String.class, job.jobId()),
                    jdbc.queryForObject("SELECT image_snapshot::text FROM extension_installation_jobs WHERE job_id=?",
                            String.class, job.jobId()), staleToken, "REGISTERED", "INSTALL", null,
                    null, null, null, null, null, null);

            assertThat(worker.finalizeInstallation(staleJob)).isFalse();
            assertThat(jobs.get(job.jobId()).state()).isEqualTo("VERIFYING_ARTIFACTS");
            assertThat(jdbc.queryForObject("SELECT lifecycle_state FROM extension_plugins WHERE plugin_id=?", String.class, pluginId))
                    .isEqualTo("CONFIGURED");
        } finally {
            worker.stopLeaseRenewals();
        }
    }

    @Test
    void renewsLeaseDuringControllerWorkAndPreventsASecondWorkerFromReclaimingTheJob() throws Exception {
        String pluginId = "installation.lease-renewal";
        String queuedPluginId = "installation.lease-queued";
        PluginManifest manifest = manifest(pluginId);
        PluginManifest queuedManifest = manifest(queuedPluginId);
        jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state) VALUES (?,?::jsonb,?,'CONFIGURED')",
                pluginId, mapper.writeValueAsString(manifest), manifest.image());
        jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state) VALUES (?,?::jsonb,?,'CONFIGURED')",
                queuedPluginId, mapper.writeValueAsString(queuedManifest), queuedManifest.image());
        var job = jobs.request(pluginId, null, "legacy:" + pluginId, 0, manifest, UUID.randomUUID(), "lease-key-000001");
        var queuedJob = jobs.request(queuedPluginId, null, "legacy:" + queuedPluginId, 0, queuedManifest,
                UUID.randomUUID(), "lease-key-000002");
        var runtime = org.mockito.Mockito.mock(ExtensionRuntimePort.class);
        var runtimeBeans = new StaticListableBeanFactory(Map.of(ExtensionRuntimePort.class.getName(), runtime));
        var registryCredentials = org.mockito.Mockito.mock(ExtensionRegistryCredentialService.class);
        org.mockito.Mockito.when(registryCredentials.credentialsFor(org.mockito.ArgumentMatchers.any(PluginManifest.class))).thenReturn(Map.of());
        var worker = new ExtensionInstallationJobWorker(jdbc, mapper,
                runtimeBeans.getBeanProvider(ExtensionRuntimePort.class), registryCredentials,
                new OciArchiveInspector(OciArchiveInspector.Limits.developmentDefaults()),
                new OciArchiveQuarantineStore(System.getProperty("java.io.tmpdir"), 2L << 30),
                new DataSourceTransactionManager(jdbc.getDataSource()), 1_500, 250);
        var installStarted = new CountDownLatch(1);
        var finishInstall = new CountDownLatch(1);
        var installCalls = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (installCalls.incrementAndGet() == 1) {
                installStarted.countDown();
                assertThat(finishInstall.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return null;
        }).when(runtime).install(org.mockito.ArgumentMatchers.any(PluginManifest.class),
                org.mockito.ArgumentMatchers.anyMap());
        Thread workerThread = new Thread(worker::runPending, "installation-lease-test-worker");
        try {
            workerThread.start();
            assertThat(installStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(2_200);

            var leaseUntil = jdbc.queryForObject("SELECT lease_until FROM extension_installation_jobs WHERE job_id=?",
                    java.sql.Timestamp.class, job.jobId()).toInstant();
            assertThat(leaseUntil).isAfter(java.time.Instant.now().plusMillis(500));
            assertThat(jobs.get(job.jobId()).state()).isEqualTo("DOWNLOADING_OR_IMPORTING");
            var queuedLeaseUntil = jdbc.queryForObject("SELECT lease_until FROM extension_installation_jobs WHERE job_id=?",
                    java.sql.Timestamp.class, queuedJob.jobId()).toInstant();
            assertThat(queuedLeaseUntil).isAfter(java.time.Instant.now().plusMillis(500));

            worker.runPending();
            org.mockito.Mockito.verify(runtime, org.mockito.Mockito.times(1)).install(
                    org.mockito.ArgumentMatchers.any(PluginManifest.class), org.mockito.ArgumentMatchers.anyMap());

            finishInstall.countDown();
            workerThread.join(5_000);
            assertThat(workerThread.isAlive()).isFalse();
            assertThat(jobs.get(job.jobId()).state()).isEqualTo("INSTALLED");
            assertThat(jobs.get(job.jobId()).attemptCount()).isEqualTo(1);
            assertThat(jobs.get(queuedJob.jobId()).state()).isEqualTo("INSTALLED");
            assertThat(jobs.get(queuedJob.jobId()).attemptCount()).isEqualTo(1);
            org.mockito.Mockito.verify(runtime, org.mockito.Mockito.times(2)).install(
                    org.mockito.ArgumentMatchers.any(PluginManifest.class), org.mockito.ArgumentMatchers.anyMap());
        } finally {
            finishInstall.countDown();
            workerThread.join(5_000);
            worker.stopLeaseRenewals();
        }
    }

    @Test
    void storesTrustedRegistryCredentialsEncryptedAndReturnsThemOnlyToTheInstallWorker() throws Exception {
        UUID actorId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,role,created_at) VALUES (?,?,?,'Test','Admin','SUPERADMIN',now()) "
                        + "ON CONFLICT(id) DO NOTHING",
                actorId, "registry-admin@example.test", "not-used");
        var service = new ExtensionRegistryCredentialService(jdbc, encryption,
                new io.artio.extensions.PluginManifestValidator(java.util.Set.of("registry.example:5000")));
        var saved = service.put("REGISTRY.EXAMPLE:5000", "publisher", "test-registry-password", actorId);
        String storedCiphertext = jdbc.queryForObject(
                "SELECT encrypted_password FROM extension_registry_credentials WHERE registry_host=?", String.class,
                "registry.example:5000");
        PluginManifest privateManifest = new PluginManifest("1", "private.plugin", "Private Plugin", "Customer", "1.0.0",
                "Private registry fixture", "0.1.0", "9.0.0",
                "registry.example:5000/team/plugin@sha256:" + "a".repeat(64), List.of(), List.of(), Map.of(),
                List.of(), "/health", "/diagnostics");

        assertThat(saved.registryHost()).isEqualTo("registry.example:5000");
        assertThat(storedCiphertext).doesNotContain("test-registry-password");
        assertThat(service.list()).singleElement().satisfies(summary ->
                assertThat(summary.username()).isEqualTo("publisher"));
        assertThat(service.credentialsFor(privateManifest))
                .containsEntry("registry.example:5000",
                        new ExtensionRuntimePort.RegistryAuth("publisher", "test-registry-password"));
        assertThatThrownBy(() -> service.put("untrusted.example", "publisher", "another-password", actorId))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM extension_audit_events WHERE plugin_id='custom.registry' AND actor_user_id=?", Integer.class, actorId))
                .isEqualTo(1);
    }

    private static PluginManifest manifest(String id) {
        return new PluginManifest("2", id, "Acceptance Plugin", "Artio", "1.0.0", "Test manifest",
                "0.1.0", "9.0.0", "example.invalid/plugin@sha256:" + "a".repeat(64), List.of(),
                List.of("catalog.read"), Map.of(), List.of("payment.provider.v1"), "/health", "/diagnostics");
    }

    private V2ReleaseFixture registerV2Plugin(String pluginId, String lifecycleState) throws Exception {
        PluginManifest previous = releaseManifest(pluginId, "1.0.0", "a");
        PluginManifest target = releaseManifest(pluginId, "2.0.0", "b");
        jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state) VALUES (?,?::jsonb,?,'INSTALLED')",
                pluginId, mapper.writeValueAsString(previous), previous.image());
        jdbc.update("INSERT INTO extension_plugin_releases(plugin_id,version,image_reference,manifest) VALUES (?,?,?,?::jsonb)",
                pluginId, previous.version(), previous.image(), mapper.writeValueAsString(previous));
        jdbc.update("INSERT INTO extension_plugin_releases(plugin_id,version,image_reference,manifest) VALUES (?,?,?,?::jsonb)",
                pluginId, target.version(), target.image(), mapper.writeValueAsString(target));
        Long previousReleaseId = jdbc.queryForObject("SELECT release_id FROM extension_plugin_releases WHERE plugin_id=? AND version=?",
                Long.class, pluginId, previous.version());
        Long targetReleaseId = jdbc.queryForObject("SELECT release_id FROM extension_plugin_releases WHERE plugin_id=? AND version=?",
                Long.class, pluginId, target.version());
        var previousV2 = new io.artio.extensions.PluginManifestV2(previous, List.of(), List.of());
        var targetV2 = new io.artio.extensions.PluginManifestV2(target, List.of(), List.of());
        jdbc.update("INSERT INTO extension_plugin_v2_releases(release_id,plugin_id,manifest) VALUES (?,?,?::jsonb)",
                previousReleaseId, pluginId, mapper.writeValueAsString(previousV2));
        jdbc.update("INSERT INTO extension_plugin_v2_releases(release_id,plugin_id,manifest) VALUES (?,?,?::jsonb)",
                targetReleaseId, pluginId, mapper.writeValueAsString(targetV2));
        UUID instanceId = UUID.randomUUID();
        String runtimeIdentity = "instance-" + instanceId.toString().replace("-", "");
        jdbc.update("INSERT INTO extension_plugin_instances(instance_id,plugin_id,instance_key,release_id,compatibility_mode,"
                        + "lifecycle_state,runtime_identity) VALUES (?,?, 'default',?,'CAPABILITY_V2',?,?)",
                instanceId, pluginId, previousReleaseId, lifecycleState, runtimeIdentity);
        return new V2ReleaseFixture(instanceId, runtimeIdentity, previousReleaseId, targetReleaseId, previous, target);
    }

    private static PluginManifest releaseManifest(String id, String version, String digest) {
        Map<String, PluginManifest.ConfigField> configuration = Map.of(
                "endpoint", new PluginManifest.ConfigField("url", false, false, "Endpoint", null, null, List.of(), null, null, null, null),
                "apiKey", new PluginManifest.ConfigField("secret", false, true, "API key", null, null, List.of(), null, null, null, null));
        return new PluginManifest("1", id, "Release Acceptance Plugin", "Artio", version, "Release change test",
                "0.1.0", "9.0.0", "example.invalid/plugin@sha256:" + digest.repeat(64), List.of(),
                List.of("catalog.read"), configuration, List.of("payment.provider.v1"), "/health", "/diagnostics");
    }

    private record V2ReleaseFixture(UUID instanceId, String runtimeIdentity, long previousReleaseId,
            long targetReleaseId, PluginManifest previous, PluginManifest target) { }
}
