import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { setBackofficeLocale, type BackofficeLocale } from './backoffice-locale';
import { BundleAdminComponent } from './bundle-admin.component';

describe('BundleAdminComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [BundleAdminComponent] }).compileComponents();
  });

  afterEach(() => localStorage.removeItem('artio-backoffice-language'));

  it.each([
    ['en', 'Build fixed sets or configurable bundles', 'New bundle', 'No bundles have been configured yet.'],
    ['de', 'Erstellen Sie feste Sets oder konfigurierbare Bundles', 'Neues Bundle', 'Es wurden noch keine Bundles eingerichtet.'],
    ['it', 'Crea set fissi o bundle configurabili', 'Nuovo bundle', 'Non è ancora stato configurato alcun bundle.'],
    ['fr', 'Créez des ensembles fixes ou configurables', 'Nouveau bundle', 'Aucun bundle n’a encore été configuré.']
  ] as const)('renders bundle management states in %s', (locale, intro, newBundle, emptyState) => {
    setBackofficeLocale(locale as BackofficeLocale);
    const fixture = TestBed.createComponent(BundleAdminComponent);
    fixture.detectChanges();

    const page = fixture.nativeElement.textContent as string;
    expect(page).toContain(intro);
    expect(page).toContain(newBundle);
    expect(page).toContain(emptyState);
  });

  it('emits editor and bundle actions to the owning page', () => {
    const fixture = TestBed.createComponent(BundleAdminComponent);
    const actions: string[] = [];
    fixture.componentInstance.action.subscribe(action => actions.push(action.type));
    fixture.detectChanges();

    const button = [...fixture.nativeElement.querySelectorAll('button')]
      .find((candidate: HTMLButtonElement) => candidate.textContent?.includes('New bundle')) as HTMLButtonElement;
    button.click();

    expect(actions).toEqual(['toggleEditor']);
  });
});
