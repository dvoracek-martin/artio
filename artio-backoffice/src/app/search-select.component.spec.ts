import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { setBackofficeLocale, type BackofficeLocale } from './backoffice-locale';
import { SearchSelectComponent } from './search-select.component';

describe('SearchSelectComponent', () => {
  beforeEach(async () => TestBed.configureTestingModule({ imports: [SearchSelectComponent] }).compileComponents());
  afterEach(() => localStorage.removeItem('artio-backoffice-language'));

  it.each([
    ['en', 'Store country', 'Search Store country', 'Search store country…', 'Loading options…', 'No matching options.', 'Nothing selected', 'Clear', 'Done · 0 selected', '1 selected'],
    ['de', 'Geschäftsland', 'Geschäftsland suchen', 'geschäftsland durchsuchen…', 'Optionen werden geladen…', 'Keine passenden Optionen gefunden.', 'Nichts ausgewählt', 'Auswahl löschen', 'Fertig · 0 ausgewählt', '1 ausgewählt'],
    ['it', 'Paese del negozio', 'Cerca Paese del negozio', 'Cerca paese del negozio…', 'Caricamento delle opzioni…', 'Nessuna opzione corrispondente.', 'Nessuna selezione', 'Cancella selezione', 'Fatto · 0 selezionati', '1 selezionati'],
    ['fr', 'Pays du magasin', 'Rechercher Pays du magasin', 'Rechercher pays du magasin…', 'Chargement des options…', 'Aucune option correspondante.', 'Aucune sélection', 'Effacer la sélection', 'Terminé · 0 éléments sélectionnés', '1 éléments sélectionnés']
  ] as const)('localizes searchable multi-select states in %s', (locale, label, searchLabel, placeholder, loading, noResults, noneSelected, clear, done, oneSelected) => {
    setBackofficeLocale(locale as BackofficeLocale);
    const fixture = TestBed.createComponent(SearchSelectComponent);
    fixture.componentInstance.label = label;
    fixture.componentInstance.multiple = true;
    fixture.componentInstance.loading = true;
    fixture.componentInstance.open = true;
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('input[type="search"]') as HTMLInputElement;
    expect(input.getAttribute('aria-label')).toBe(searchLabel);
    expect(input.placeholder).toBe(placeholder);
    expect(fixture.nativeElement.querySelector('[role="status"]')?.textContent).toContain(loading);
    expect(fixture.nativeElement.querySelector('.select-options')?.textContent).not.toContain(noResults);
    expect(fixture.nativeElement.querySelector('.sr-only')?.textContent).toContain(noneSelected);
    expect(fixture.nativeElement.querySelector('.select-selection-bar')?.textContent).toContain(clear);
    expect(fixture.nativeElement.querySelector('.select-done')?.textContent).toContain(done);

    fixture.componentInstance.loading = false;
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.select-options')?.textContent).toContain(noResults);

    fixture.componentInstance.values = ['country-1'];
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.select-selection-bar')?.textContent).toContain(oneSelected);
  });
});
