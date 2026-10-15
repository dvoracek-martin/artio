import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { CatalogMetadataAdminComponent } from './catalog-metadata-admin.component';
import { setBackofficeLocale, type BackofficeLocale } from './backoffice-locale';

describe('CatalogMetadataAdminComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CatalogMetadataAdminComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
  });

  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    localStorage.removeItem('artio-backoffice-language');
  });

  it.each([
    ['en', 'Categories', 'Brands', 'Attribute definitions', 'Category name'],
    ['de', 'Kategorien', 'Marken', 'Attributdefinitionen', 'Kategoriename'],
    ['it', 'Categorie', 'Marche', 'Definizioni degli attributi', 'Nome categoria'],
    ['fr', 'Catégories', 'Marques', 'Définitions des attributs', 'Nom de la catégorie']
  ] as const)('renders catalog labels and empty states in %s', (locale, categories, brands, attributes, categoryName) => {
    setBackofficeLocale(locale as BackofficeLocale);
    const fixture = TestBed.createComponent(CatalogMetadataAdminComponent);
    fixture.detectChanges();

    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/categories').flush([]);
    http.expectOne('/api/v1/brands').flush([]);
    http.expectOne('/api/v1/attribute-definitions').flush([]);
    http.expectOne('/api/v1/products').flush([]);
    fixture.detectChanges();

    const page = fixture.nativeElement.textContent as string;
    expect(page).toContain(categories);
    expect(page).toContain(brands);
    expect(page).toContain(attributes);
    const categoryInput = fixture.nativeElement.querySelector('#new-category-name') as HTMLInputElement;
    expect(categoryInput.labels?.[0]?.textContent?.trim()).toBe(categoryName);
    expect(page).toContain(locale === 'de' ? 'Es sind keine Kategorien konfiguriert.'
      : locale === 'it' ? 'Non è configurata alcuna categoria.'
      : locale === 'fr' ? 'Aucune catégorie n’est configurée.'
      : 'No categories are configured.');
  });

  it.each([
    ['en', 'Could not load categories.'],
    ['de', 'Kategorien konnten nicht geladen werden.'],
    ['it', 'Impossibile caricare le categorie.'],
    ['fr', 'Impossible de charger les catégories.']
  ] as const)('shows the category load fallback in %s', (locale, message) => {
    setBackofficeLocale(locale as BackofficeLocale);
    const fixture = TestBed.createComponent(CatalogMetadataAdminComponent);
    fixture.detectChanges();

    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/categories').flush(null, { status: 503, statusText: 'Unavailable' });
    http.expectOne('/api/v1/brands').flush([]);
    http.expectOne('/api/v1/attribute-definitions').flush([]);
    http.expectOne('/api/v1/products').flush([]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent?.trim()).toBe(message);
  });
});
