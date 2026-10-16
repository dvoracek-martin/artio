import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { setBackofficeLocale, type BackofficeLocale } from './backoffice-locale';
import { ExtensionAdminComponent } from './extension-admin.component';

describe('ExtensionAdminComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [ExtensionAdminComponent] }).compileComponents();
  });
  afterEach(() => localStorage.removeItem('artio-backoffice-language'));

  it.each([
    ['en', 'Register trusted extensions', 'Regional catalog', 'No plugins are recommended', 'No extensions are registered.'],
    ['de', 'Registrieren Sie vertrauenswürdige Erweiterungen', 'Regionale Übersicht', 'Für die Paketdistribution werden keine Plugins empfohlen.', 'Keine Erweiterungen registriert.'],
    ['it', 'Registra estensioni attendibili', 'Catalogo regionale', 'Nessun plugin è consigliato', 'Nessuna estensione registrata.'],
    ['fr', 'Enregistrez des extensions fiables', 'Catalogue régional', 'Aucun plugin n’est recommandé', 'Aucune extension enregistrée.']
  ] as const)('renders discovery and empty states in %s', (locale, intro, catalog, noRecommendations, noPlugins) => {
    setBackofficeLocale(locale as BackofficeLocale);
    const fixture = TestBed.createComponent(ExtensionAdminComponent);
    fixture.detectChanges();
    const page = fixture.nativeElement.textContent as string;
    expect(page).toContain(intro);
    expect(page).toContain(catalog);
    expect(page).toContain(noRecommendations);
    expect(page).toContain(noPlugins);
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
    expect(page).toContain('storage.object.v1');
    expect(page).toContain('order.paid v1');
    expect(page).toContain('Create instance');
    expect(page).toContain('Create binding');
  });
});
