import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { setBackofficeLocale, type BackofficeLocale } from './backoffice-locale';
import { ExtensionAdminComponent } from './extension-admin.component';
import { extensionAdminText, type ExtensionAdminTextKey } from './extension-admin-locale';

describe('ExtensionAdminComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [ExtensionAdminComponent], providers: [provideHttpClient(), provideHttpClientTesting()] }).compileComponents();
  });
  afterEach(() => localStorage.removeItem('artio-backoffice-language'));

  it.each([
    ['en', 'Release', 'Configuration generation'],
    ['de', 'Version', 'Konfigurationsgeneration'],
    ['it', 'Release', 'Generazione della configurazione'],
    ['fr', 'Version', 'Génération de la configuration'],
  ] as const)('localizes instance release metadata in %s', (locale, release, generation) => {
    expect(extensionAdminText(locale, 'instanceRelease' as ExtensionAdminTextKey)).toBe(release);
    expect(extensionAdminText(locale, 'configurationGeneration' as ExtensionAdminTextKey)).toBe(generation);
  });

  it.each([
    ['en', 'Register trusted extensions', 'Regional catalog', 'No plugins are recommended', 'No extensions are registered.', 'Discover published plugins'],
    ['de', 'Registrieren Sie vertrauenswürdige Erweiterungen', 'Regionale Übersicht', 'Für die Paketdistribution werden keine Plugins empfohlen.', 'Keine Erweiterungen registriert.', 'Veröffentlichte Plugins entdecken'],
    ['it', 'Registra estensioni attendibili', 'Catalogo regionale', 'Nessun plugin è consigliato', 'Nessuna estensione registrata.', 'Scopri i plugin pubblicati'],
    ['fr', 'Enregistrez des extensions fiables', 'Catalogue régional', 'Aucun plugin n’est recommandé', 'Aucune extension enregistrée.', 'Découvrir les plugins publiés']
  ] as const)('renders discovery and empty states in %s', (locale, intro, catalog, noRecommendations, noPlugins, marketplaceTitle) => {
    setBackofficeLocale(locale as BackofficeLocale);
    const fixture = TestBed.createComponent(ExtensionAdminComponent);
    fixture.detectChanges();
    const page = fixture.nativeElement.textContent as string;
    expect(page).toContain(intro);
    expect(page).toContain(catalog);
    expect(page).toContain(noRecommendations);
    expect(page).toContain(noPlugins);
    expect(page).toContain(marketplaceTitle);
  });

  it('emits registration actions to the owner of extension lifecycle behavior', () => {
    const fixture = TestBed.createComponent(ExtensionAdminComponent);
    const actions: string[] = [];
    fixture.componentInstance.action.subscribe(action => actions.push(action.type));
    fixture.detectChanges();
    const button = [...fixture.nativeElement.querySelectorAll('button')]
      .find((candidate: HTMLButtonElement) => candidate.textContent?.includes('Register extension')) as HTMLButtonElement;
    button.click();
    expect(actions).toEqual(['toggleRegistration']);
  });

  it('shows and reorders explicit primary and replica storage bindings', () => {
    setBackofficeLocale('en');
    const fixture = TestBed.createComponent(ExtensionAdminComponent);
    const component = fixture.componentInstance;
    const extension = { manifest: { id: 'storage.example' } };
    component.selectedExtension = extension;
    const primary = { bindingId: 'b-primary', instanceId: 'instance-primary', capabilityId: 'storage.object.v1', operationName: 'put', scope: {}, priority: 10, eligibilityState: 'ACTIVE' };
    const replica = { bindingId: 'b-replica', instanceId: 'instance-replica', capabilityId: 'storage.object.v1', operationName: 'put', scope: {}, priority: 0, eligibilityState: 'ACTIVE' };
    component.storagePlacementBindings = [replica, primary].map(binding => ({ ...binding, pluginId: 'storage.example', instanceKey: binding.instanceId, lifecycleState: 'ENABLED' }));
    expect(component.placementRole(component.storagePlacementBindings[1])).toBe('Primary');
    expect(component.placementRole(component.storagePlacementBindings[0])).toBe('Replica');
    component.moveStorageBinding(replica, -1);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne({ method: 'PUT', url: '/api/v1/admin/extensions/storage-placement/b-replica' }).flush({ bindingId: 'b-replica', priority: 11 });
    expect(component.storagePlacementBindings[0].priority).toBe(11);
  });

  it('shows storage placement status and advances verified migration one bounded batch at a time', async () => {
    setBackofficeLocale('en');
    const fixture = TestBed.createComponent(ExtensionAdminComponent);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/v1/admin/extensions/security/advisories').flush({ releases: [] });
    http.expectOne('/api/v1/admin/catalog/media/storage-status').flush({ uploadAvailable: true, installCategory: 'storage', storageMode: 'plugins', migrationSource: 's3', mediaCount: 3, activePluginObjects: 1, pendingMigration: 2, deletePending: 0, tombstoned: 0, migrationAvailable: true, canManageStorage: true });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Storage placement and migration');
    expect(fixture.nativeElement.textContent).toContain('Waiting to migrate: 2');

    const migrate = [...fixture.nativeElement.querySelectorAll('button')]
      .find((button: HTMLButtonElement) => button.textContent?.includes('Migrate next batch')) as HTMLButtonElement;
    migrate.click();
    http.expectOne('/api/v1/admin/catalog/media/storage-backfill?limit=50').flush({ inspected: 2, migrated: 2, failed: 0, complete: false, nextCursor: 'abc.jpg', failures: [] });
    http.expectOne('/api/v1/admin/catalog/media/storage-status').flush({ uploadAvailable: true, storageMode: 'plugins', migrationSource: 's3', mediaCount: 3, activePluginObjects: 2, pendingMigration: 1, deletePending: 0, tombstoned: 0, migrationAvailable: true, canManageStorage: true });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Inspected 2 · migrated 2 · failed 0');

    [...fixture.nativeElement.querySelectorAll('button')]
      .find((button: HTMLButtonElement) => button.textContent?.includes('Continue migration'))?.click();
    const next = http.expectOne('/api/v1/admin/catalog/media/storage-backfill?limit=50&afterStorageKey=abc.jpg');
    next.flush({ inspected: 1, migrated: 1, failed: 0, complete: true, nextCursor: 'xyz.png', failures: [] });
    http.expectOne('/api/v1/admin/catalog/media/storage-status').flush({ uploadAvailable: true, storageMode: 'plugins', migrationSource: 's3', mediaCount: 3, activePluginObjects: 3, pendingMigration: 0, deletePending: 0, tombstoned: 0, migrationAvailable: true, canManageStorage: true });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('All legacy media has been copied and verified. Source data is retained.');
  });

  it('discovers payment methods and saves the Core-validated visibility and order', () => {
    setBackofficeLocale('en');
    const fixture = TestBed.createComponent(ExtensionAdminComponent);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/v1/admin/extensions/security/advisories').flush({ releases: [] });
    http.expectOne('/api/v1/admin/catalog/media/storage-status').flush({ storageMode: 'local' });
    const discover = [...fixture.nativeElement.querySelectorAll('button')]
      .find((button: HTMLButtonElement) => button.textContent?.includes('Discover methods')) as HTMLButtonElement;
    discover.click();
    http.expectOne('/api/v1/admin/payments/method-settings?country=CH&currency=CHF').flush({ country: 'CH', currency: 'CHF', providerAvailable: true, partialFailure: false, canManage: true, failures: [], methods: [
      { methodId: 'card', displayName: 'Card', providerPluginId: 'payments.cards', providerInstanceId: 'instance-card', enabled: true, displayOrder: 0 },
      { methodId: 'twint', displayName: 'TWINT', providerPluginId: 'payments.twint', providerInstanceId: 'instance-twint', enabled: true, displayOrder: 1 },
    ] });

    fixture.detectChanges();
    [...fixture.nativeElement.querySelectorAll('button')]
      .find((button: HTMLButtonElement) => button.getAttribute('aria-label') === 'Move Card later')?.click();
    fixture.detectChanges();
    const toggles = [...fixture.nativeElement.querySelectorAll('input[type="checkbox"]')] as HTMLInputElement[];
    toggles[1].checked = false;
    toggles[1].dispatchEvent(new Event('change', { bubbles: true }));
    fixture.detectChanges();
    [...fixture.nativeElement.querySelectorAll('button')]
      .find((button: HTMLButtonElement) => button.textContent?.includes('Save payment settings'))?.click();
    const save = http.expectOne('/api/v1/admin/payments/method-settings?country=CH&currency=CHF');
    expect(save.request.method).toBe('PUT');
    expect(save.request.body).toEqual({ methods: [
      { methodId: 'twint', providerPluginId: 'payments.twint', providerInstanceId: 'instance-twint', enabled: true, displayOrder: 0 },
      { methodId: 'card', providerPluginId: 'payments.cards', providerInstanceId: 'instance-card', enabled: false, displayOrder: 1 },
    ] });
    save.flush({ country: 'CH', currency: 'CHF', providerAvailable: true, partialFailure: false, canManage: true, failures: [], methods: [
      { methodId: 'twint', displayName: 'TWINT', providerPluginId: 'payments.twint', providerInstanceId: 'instance-twint', enabled: true, displayOrder: 0 },
      { methodId: 'card', displayName: 'Card', providerPluginId: 'payments.cards', providerInstanceId: 'instance-card', enabled: false, displayOrder: 1 },
    ] });
  });

  it('includes the entered Marketplace query and country in the search action', () => {
    const fixture = TestBed.createComponent(ExtensionAdminComponent);
    const actions: any[] = [];
    fixture.componentInstance.action.subscribe(action => actions.push(action));
    fixture.detectChanges();
    fixture.componentInstance.marketplaceQuery = 'Artio Reference';
    fixture.componentInstance.marketplaceCountry = 'CH';
    fixture.detectChanges();
    const button = [...fixture.nativeElement.querySelectorAll('form button')]
      .find((candidate: HTMLButtonElement) => candidate.textContent?.includes('Search marketplace')) as HTMLButtonElement;
    button.click();
    expect(actions).toEqual([{ type: 'marketplaceSearch', query: 'Artio Reference', country: 'CH' }]);
  });

  it('shows runtime compatibility and persisted composition counts', () => {
    setBackofficeLocale('en');
    const fixture = TestBed.createComponent(ExtensionAdminComponent);
    fixture.componentInstance.extensions = [{
      id: 'payments.example', name: 'Example Payments', version: '1.0.0', lifecycleState: 'ENABLED',
      configuredSecrets: [], compatibilityModes: 'CAPABILITY_V2', instanceCount: 2, bindingCount: 3, subscriptionCount: 1,
      geographicAvailability: { scope: 'GLOBAL', countries: [] }
    }];
    fixture.detectChanges();
    const page = fixture.nativeElement.textContent as string;
    expect(page).toContain('Compatibility: CAPABILITY_V2 · 2 instance(s) · 3 binding(s) · 1 subscription(s)');
  });

  it('previews the Core-selected active-exclusive route and reports an ambiguous route', () => {
    setBackofficeLocale('en');
    const fixture = TestBed.createComponent(ExtensionAdminComponent);
    const component = fixture.componentInstance;
    const http = TestBed.inject(HttpTestingController);
    component.selectedExtension = { compatibilityModes: 'CAPABILITY_V2', manifest: { id: 'payments.example', extensionPoints: [], configuration: {} } };
    component.extensionBindings = [{ bindingId: 'binding-a', instanceId: 'instance-a', capabilityId: 'payment.provider.v1', operationName: 'createCheckout', scope: { countryCode: 'CH' }, priority: 10, eligibilityState: 'ACTIVE' }];
    fixture.detectChanges();
    http.expectOne('/api/v1/admin/extensions/security/advisories').flush({ releases: [] });

    const previewButton = [...fixture.nativeElement.querySelectorAll('button')]
      .find((button: HTMLButtonElement) => button.textContent?.includes('Preview routing')) as HTMLButtonElement;
    expect(previewButton).toBeTruthy();
    previewButton.click();
    const request = http.expectOne('/api/v1/admin/extensions/routing/resolve');
    expect(request.request.body).toEqual({ capabilityId: 'payment.provider.v1', operationName: 'createCheckout', scope: { countryCode: 'CH' }, pinnedInstanceId: null, newCommitment: true });
    request.flush({ capabilityId: 'payment.provider.v1', operationName: 'createCheckout', policy: 'EXCLUSIVE', sideEffecting: true, scope: { countryCode: 'CH' }, instanceIds: ['instance-a'], bindingIds: ['binding-a'] });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Eligible instances: instance-a');
    expect(fixture.nativeElement.textContent).toContain('Selected bindings: binding-a');

    previewButton.click();
    http.expectOne('/api/v1/admin/extensions/routing/resolve').flush({ message: 'Ambiguous exclusive route' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('Ambiguous exclusive route');
  });

  it('selects immutable releases per instance and emits update or rollback actions by semantic version', () => {
    setBackofficeLocale('en');
    const fixture = TestBed.createComponent(ExtensionAdminComponent);
    const component = fixture.componentInstance;
    component.extensionsLoading = false;
    component.selectedExtension = { compatibilityModes: 'CAPABILITY_V2', manifest: { id: 'payments.example', extensionPoints: [], configuration: {} } };
    component.extensionInstances = [{ instanceId: 'instance-a', instanceKey: 'europe', releaseId: 10,
      compatibilityMode: 'CAPABILITY_V2', lifecycleState: 'DISABLED', publicConfiguration: {} }];
    component.extensionReleases = [
      { releaseId: 10, version: '1.9.0' },
      { releaseId: 11, version: '2.0.0' },
      { releaseId: 12, version: '1.8.0' }
    ];
    component.selectedReleaseByInstance['instance-a'] = 11;
    const actions: any[] = [];
    component.action.subscribe(action => actions.push(action));
    fixture.detectChanges();
    const update = [...fixture.nativeElement.querySelectorAll('button')]
      .find((button: HTMLButtonElement) => button.textContent?.includes('Update instance')) as HTMLButtonElement;
    expect(update).toBeTruthy();
    update.click();
    expect(actions[0]).toMatchObject({ type: 'instanceReleaseChange', targetReleaseId: 11, operation: 'update' });

    component.selectedReleaseByInstance['instance-a'] = 12;
    fixture.detectChanges();
    const rollback = [...fixture.nativeElement.querySelectorAll('button')]
      .find((button: HTMLButtonElement) => button.textContent?.includes('Roll back instance')) as HTMLButtonElement;
    expect(rollback).toBeTruthy();
    rollback.click();
    expect(actions[1]).toMatchObject({ type: 'instanceReleaseChange', targetReleaseId: 12, operation: 'rollback' });
  });

  it('renders a compatible marketplace release and emits an installation action', () => {
    setBackofficeLocale('en');
    const fixture = TestBed.createComponent(ExtensionAdminComponent);
    fixture.componentInstance.marketplacePlugins = [{ pluginId: 'shipping.demo', version: '1.2.3', vendor: 'Demo',
      metadata: { title: 'Demo Shipping', tagline: 'Tracked delivery' }, compatibility: { minimumCoreVersion: '1.0.0' } }];
    fixture.componentInstance.marketplaceCountry = 'CH';
    const actions: any[] = [];
    fixture.componentInstance.action.subscribe(action => actions.push(action));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Demo Shipping');
    const button = [...fixture.nativeElement.querySelectorAll('button')]
      .find((candidate: HTMLButtonElement) => candidate.textContent?.includes('Install from Marketplace')) as HTMLButtonElement;
    button.click();
    expect(actions).toEqual([{ type: 'marketplaceInstall', item: fixture.componentInstance.marketplacePlugins[0], country: 'CH' }]);
  });

  it('offers to register a newer published release for an installed v2 plugin', () => {
    setBackofficeLocale('en');
    const fixture = TestBed.createComponent(ExtensionAdminComponent);
    fixture.componentInstance.extensionsLoading = false;
    fixture.componentInstance.extensions = [{ id: 'shipping.demo', name: 'Demo Shipping', version: '1.0.0', lifecycleState: 'INSTALLED', configuredSecrets: [], compatibilityModes: 'CAPABILITY_V2', instanceCount: 1, bindingCount: 0, subscriptionCount: 0 }];
    fixture.componentInstance.marketplacePlugins = [{ pluginId: 'shipping.demo', version: '1.1.0', vendor: 'Demo', metadata: { title: 'Demo Shipping' } }];
    const actions: any[] = [];
    fixture.componentInstance.action.subscribe(action => actions.push(action));
    fixture.detectChanges();
    const button = [...fixture.nativeElement.querySelectorAll('button')]
      .find((candidate: HTMLButtonElement) => candidate.textContent?.includes('Register Marketplace release')) as HTMLButtonElement;
    expect(button).toBeTruthy();
    button.click();
    expect(actions).toEqual([{ type: 'marketplaceRegisterRelease', item: fixture.componentInstance.marketplacePlugins[0] }]);
  });

  it('blocks a revoked Marketplace release while preserving uninstall', () => {
    setBackofficeLocale('en');
    const fixture = TestBed.createComponent(ExtensionAdminComponent);
    fixture.componentInstance.extensionsLoading = false;
    fixture.componentInstance.extensions = [{ id: 'shipping.demo', name: 'Demo Shipping', version: '1.2.3', lifecycleState: 'ENABLED', configuredSecrets: [] }];
    fixture.componentInstance.securityAdvisories = { releases: [{ pluginId: 'shipping.demo', version: '1.2.3', securityStatus: 'BANNED' }] };
    const actions: any[] = [];
    fixture.componentInstance.action.subscribe(action => actions.push(action));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('A signed Marketplace advisory bans this exact image digest.');
    const buttons = [...fixture.nativeElement.querySelectorAll('button')] as HTMLButtonElement[];
    expect(buttons.some(button => button.textContent?.includes('Disable'))).toBe(false);
    buttons.find(button => button.textContent?.includes('Uninstall'))?.click();
    expect(actions.map(action => action.type)).toEqual(['uninstall']);
  });

  it('does not offer Marketplace installation for a banned release', () => {
    setBackofficeLocale('en');
    const fixture = TestBed.createComponent(ExtensionAdminComponent);
    fixture.componentInstance.extensionsLoading = false;
    fixture.componentInstance.marketplacePlugins = [{ pluginId: 'shipping.demo', version: '1.2.3', vendor: 'Demo', metadata: { title: 'Demo Shipping' } }];
    fixture.componentInstance.securityAdvisories = { releases: [{ pluginId: 'shipping.demo', version: '1.2.3', securityStatus: 'BANNED' }] };
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Blocked by security advisory');
    expect([...fixture.nativeElement.querySelectorAll('button')].some((button: HTMLButtonElement) => button.textContent?.includes('Install from Marketplace'))).toBe(false);
  });

  it('renders instance lifecycle, configuration, capability binding and subscription controls for v2 plugins', () => {
    setBackofficeLocale('en');
    const fixture = TestBed.createComponent(ExtensionAdminComponent);
    fixture.componentInstance.selectedExtension = {
      compatibilityModes: 'CAPABILITY_V2', lifecycleState: 'DISABLED', manifest: { id: 'example.storage', name: 'Storage' }
    };
    fixture.componentInstance.extensionInstances = [{ instanceId: 'instance-1', instanceKey: 'primary', releaseId: 4,
      configurationGeneration: 2, lifecycleState: 'DISABLED' }];
    fixture.componentInstance.extensionBindings = [{ bindingId: 'binding-1', instanceId: 'instance-1', capabilityId: 'storage.object.v1',
      operationName: 'put', scope: {}, priority: 10, eligibilityState: 'ACTIVE' }];
    fixture.componentInstance.extensionSubscriptions = [{ subscriptionId: 'subscription-1', instanceId: 'instance-1',
      eventType: 'order.paid', schemaVersion: 1, subscriptionState: 'PAUSED' }];
    fixture.detectChanges();
    const page = fixture.nativeElement.textContent as string;
    expect(page).toContain('INSTANCE COMPOSITION');
    expect(page).toContain('primary');
    expect(page).toContain('Release 4 · Configuration generation 2');
    expect(page).toContain('storage.object.v1');
    expect(page).toContain('order.paid v1');
    expect(page).toContain('Create instance');
    expect(page).toContain('Create binding');
  });

  it('renders manifest-driven storage fields, hides generated credentials and emits grouped rotation', () => {
    setBackofficeLocale('en');
    const fixture = TestBed.createComponent(ExtensionAdminComponent);
    fixture.componentRef.setInput('selectedExtension', {
      id: 'storage.artio-minio', compatibilityModes: 'CAPABILITY_V2', lifecycleState: 'CONFIGURED',
      manifest: { id: 'storage.artio-minio', name: 'MinIO', configuration: {
        mode: { type: 'enum', label: 'Storage mode', required: true, options: ['managed', 'external'] },
        endpoint: { type: 'url', label: 'External endpoint', required: false },
        runtimeAccessKey: { type: 'secret', label: 'Runtime access key', generated: true, generatedRotationGroup: 'runtime' }
      } }
    });
    fixture.componentRef.setInput('extensionInstances', [{ instanceId: 'minio-1', instanceKey: 'primary', releaseId: 4,
      configurationGeneration: 2, lifecycleState: 'DISABLED', configuredSecrets: ['runtimeAccessKey'],
      publicConfiguration: { mode: 'managed' } }]);
    const actions: any[] = [];
    fixture.componentInstance.action.subscribe(action => actions.push(action));
    fixture.detectChanges();

    const page = fixture.nativeElement.textContent as string;
    expect(page).toContain('Storage mode');
    expect(page).toContain('External endpoint');
    expect(fixture.nativeElement.querySelector('input[name="instance-minio-1-runtimeAccessKey"]')).toBeNull();
    const rotation = [...fixture.nativeElement.querySelectorAll('button')]
      .find((button: HTMLButtonElement) => button.textContent?.includes('Rotate generated credentials')) as HTMLButtonElement;
    expect(rotation.textContent).toContain('runtime');
    rotation.click();
    expect(actions).toHaveLength(1);
    expect(actions[0]).toMatchObject({ type: 'rotateInstanceSecrets', group: 'runtime', instance: { instanceId: 'minio-1' } });
  });

  it('exposes per-instance health, drain and outstanding obligation controls', () => {
    const fixture = TestBed.createComponent(ExtensionAdminComponent);
    fixture.componentInstance.selectedExtension = {
      compatibilityModes: 'CAPABILITY_V2', lifecycleState: 'ENABLED', manifest: { id: 'example.storage', name: 'Storage' }
    };
    fixture.componentInstance.extensionInstances = [{ instanceId: 'instance-live', instanceKey: 'primary', releaseId: 4,
      configurationGeneration: 2, lifecycleState: 'ENABLED' }];
    fixture.componentInstance.instanceHealth = { 'instance-live': { healthy: true, status: 'healthy' } };
    fixture.componentInstance.instanceObligations = { 'instance-live': { total: 3, pendingPayments: 1, activeShipments: 1,
      processingRefunds: 0, deliveries: 1, media: 0 } };
    const actions: string[] = [];
    fixture.componentInstance.action.subscribe(action => actions.push(action.type));
    fixture.detectChanges();
    const page = fixture.nativeElement.textContent as string;
    expect(page).toContain('healthy');
    expect(page).toContain('Outstanding');
    expect(page).toContain('payments');
    const buttons = [...fixture.nativeElement.querySelectorAll('button')] as HTMLButtonElement[];
    buttons.find(button => button.textContent?.includes('Drain'))?.click();
    buttons.find(button => button.textContent?.includes('Health'))?.click();
    buttons.find(button => button.textContent?.includes('Obligations'))?.click();
    expect(actions).toEqual(['drainInstance', 'instanceHealth', 'instanceObligations']);
  });

  it('selects a declared extension point for live diagnostics by default', () => {
    const fixture = TestBed.createComponent(ExtensionAdminComponent);
    const invocation = { extensionPoint: '', operation: 'health', payloadJson: '{}' };
    fixture.componentRef.setInput('selectedExtension', {
      lifecycleState: 'ENABLED', manifest: { id: 'marketplace.reference', extensionPoints: ['payment.provider.v1'] }
    });
    fixture.componentRef.setInput('extensionInvocation', invocation);
    fixture.detectChanges();
    const select = fixture.nativeElement.querySelector('select[name="extensionPoint"]') as HTMLSelectElement;
    expect(invocation.extensionPoint).toBe('payment.provider.v1');
    expect(select.value).toBe('payment.provider.v1');
  });
});
