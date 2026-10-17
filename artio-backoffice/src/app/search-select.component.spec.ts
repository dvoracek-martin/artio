import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { loadBackofficeLocale, setBackofficeLocale, type BackofficeLocale } from './backoffice-locale';
import { SearchSelectComponent } from './search-select.component';

describe('SearchSelectComponent', () => {
  beforeEach(async () => TestBed.configureTestingModule({ imports: [SearchSelectComponent] }).compileComponents());
  afterEach(() => localStorage.removeItem('artio-backoffice-language'));

  it.each([
    ['en', 'Store country', 'Search Store country', 'Search store country…', 'Loading options…', 'No matching options.', 'Nothing selected', 'Clear', 'Done · 0 selected', '1 selected'],
    ['de', 'Geschäftsland', 'Geschäftsland suchen', 'geschäftsland durchsuchen…', 'Optionen werden geladen…', 'Keine passenden Optionen gefunden.', 'Nichts ausgewählt', 'Auswahl löschen', 'Fertig · 0 ausgewählt', '1 ausgewählt'],
    ['it', 'Paese del negozio', 'Cerca Paese del negozio', 'Cerca paese del negozio…', 'Caricamento delle opzioni…', 'Nessuna opzione corrispondente.', 'Nessuna selezione', 'Cancella selezione', 'Fatto · 0 selezionati', '1 selezionati'],
    ['fr', 'Pays du magasin', 'Rechercher Pays du magasin', 'Rechercher pays du magasin…', 'Chargement des options…', 'Aucune option correspondante.', 'Aucune sélection', 'Effacer la sélection', 'Terminé · 0 éléments sélectionnés', '1 éléments sélectionnés']
  ] as const)('localizes searchable multi-select states in %s', async (locale, label, searchLabel, placeholder, loading, noResults, noneSelected, clear, done, oneSelected) => {
    await loadBackofficeLocale(locale as BackofficeLocale);
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

  it('keeps keyboard focus inside the searchable list and returns focus to its trigger on Escape', async () => {
    const fixture = TestBed.createComponent(SearchSelectComponent);
    fixture.componentInstance.label = 'Store country';
    fixture.componentInstance.options = [
      { value: 'ch', label: 'Switzerland' },
      { value: 'de-disabled', label: 'Disabled option', disabled: true },
      { value: 'de', label: 'Germany' },
    ];
    fixture.componentInstance.open = true;
    fixture.detectChanges();

    const options = fixture.nativeElement.querySelectorAll('.select-option') as NodeListOf<HTMLButtonElement>;
    options[0].focus();
    options[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    expect(document.activeElement).toBe(options[2]);

    options[2].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true }));
    expect(document.activeElement).toBe(options[0]);

    const search = fixture.nativeElement.querySelector('input[type="search"]') as HTMLInputElement;
    search.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();
    expect(fixture.componentInstance.open).toBe(false);
    expect(document.activeElement).toBe(fixture.nativeElement.querySelector('.search-select-trigger'));
  });
});
