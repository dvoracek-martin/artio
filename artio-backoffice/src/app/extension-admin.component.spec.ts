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
});
