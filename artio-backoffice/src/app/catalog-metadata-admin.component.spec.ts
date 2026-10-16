import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
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
    ['en', 'Categories', 'Category name'],
    ['de', 'Kategorien', 'Kategoriename'],
    ['it', 'Categorie', 'Nome categoria'],
    ['fr', 'Catégories', 'Nom de la catégorie']
  ] as const)('renders the standalone category screen in %s', (locale, categories, categoryName) => {
    setBackofficeLocale(locale as BackofficeLocale);
    const fixture = TestBed.createComponent(CatalogMetadataAdminComponent);
    fixture.componentRef.setInput('view','categories');
    fixture.detectChanges();

    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/categories').flush([]);
    fixture.detectChanges();

    const page = fixture.nativeElement.textContent as string;
    expect(page).toContain(categories);
    expect(page).not.toContain('Brands');
    expect(page).not.toContain('Attribute definitions');
    const categoryInput = fixture.nativeElement.querySelector('#new-category-name') as HTMLInputElement;
    expect(categoryInput.labels?.[0]?.textContent?.trim()).toBe(categoryName);
    expect(page).toContain(locale === 'de' ? 'Es sind keine Kategorien konfiguriert.'
      : locale === 'it' ? 'Non è configurata alcuna categoria.'
      : locale === 'fr' ? 'Aucune catégorie n’est configurée.'
      : 'No categories are configured.');
  });

  it.each([
    ['en', 'Brands', 'Brand name'],
    ['de', 'Marken', 'Markenname'],
    ['it', 'Marche', 'Nome marca'],
    ['fr', 'Marques', 'Nom de la marque']
  ] as const)('renders the standalone brand screen in %s', (locale, brands, brandName) => {
    setBackofficeLocale(locale as BackofficeLocale);
    const fixture = TestBed.createComponent(CatalogMetadataAdminComponent);
    fixture.componentRef.setInput('view','brands');
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/brands').flush([]);
    fixture.detectChanges();
    const page = fixture.nativeElement.textContent as string;
    expect(page).toContain(brands);
    expect(fixture.nativeElement.querySelector('#new-brand-name')?.labels?.[0]?.textContent?.trim()).toBe(brandName);
    expect(page).not.toContain('Categories');
    expect(page).not.toContain('Attribute definitions');
  });

  it('loads classification and attribute data only on the catalog data page', () => {
    const fixture = TestBed.createComponent(CatalogMetadataAdminComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/categories').flush([]);
    http.expectOne('/api/v1/brands').flush([]);
    http.expectOne('/api/v1/attribute-definitions').flush([]);
    http.expectOne('/api/v1/products').flush([]);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Attribute definitions');
    expect(fixture.nativeElement.textContent).not.toContain('Add category');
    expect(fixture.nativeElement.textContent).not.toContain('Add brand');
  });

  it.each([
    ['en', 'Could not load categories.'],
    ['de', 'Kategorien konnten nicht geladen werden.'],
    ['it', 'Impossibile caricare le categorie.'],
    ['fr', 'Impossible de charger les catégories.']
  ] as const)('shows the category load fallback in %s', (locale, message) => {
    setBackofficeLocale(locale as BackofficeLocale);
    const fixture = TestBed.createComponent(CatalogMetadataAdminComponent);
    fixture.componentRef.setInput('view','categories');
    fixture.detectChanges();

    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/categories').flush(null, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent?.trim()).toBe(message);
  });

  it('opens the category editor as an accessible modal and keeps provider errors in the form', async () => {
    const previousShowModal = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal');
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true,
      value: vi.fn(function (this: HTMLDialogElement) {
        Object.defineProperty(this, 'open', { configurable: true, value: true });
      })
    });
    try {
      const fixture = TestBed.createComponent(CatalogMetadataAdminComponent);
      fixture.componentRef.setInput('view','categories');
      fixture.detectChanges();
      const http = TestBed.inject(HttpTestingController);
      http.expectOne('/api/v1/categories').flush([{ id: 'cat-1', name: 'Hardware', parentId: null, active: true }]);
      fixture.detectChanges();

      fixture.componentInstance.editCategory({ id: 'cat-1', name: 'Hardware', parentId: null, active: true });
      fixture.detectChanges();
      await new Promise(resolve => setTimeout(resolve, 0));
      fixture.detectChanges();

      const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
      expect(dialog.open).toBe(true);
      expect(dialog.getAttribute('aria-labelledby')).toBe('catalog-editor-title');
      expect(dialog.querySelector('#catalog-editor-title')?.textContent?.trim()).toBe('Edit category');
      expect((dialog.querySelector('#catalog-edit-name') as HTMLInputElement).labels?.[0]?.textContent?.trim()).toBe('Name');

      fixture.componentInstance.saveCatalogEdit();
      fixture.detectChanges();
      expect(dialog.querySelector('[role="status"]')?.textContent?.trim()).toBe('Saving catalog data…');
      const update = http.expectOne('/api/v1/categories/cat-1');
      expect(update.request.body).toEqual({ name: 'Hardware', parentId: null, active: true });
      update.flush({ detail: 'Category update is temporarily unavailable.' }, { status: 503, statusText: 'Unavailable' });
      fixture.detectChanges();
      expect(dialog.querySelector('[role="alert"]')?.textContent?.trim()).toBe('Category update is temporarily unavailable.');
      expect((dialog.querySelector('button[type="submit"]') as HTMLButtonElement).disabled).toBe(false);
    } finally {
      if (previousShowModal) Object.defineProperty(HTMLDialogElement.prototype, 'showModal', previousShowModal);
      else delete (HTMLDialogElement.prototype as Partial<HTMLDialogElement>).showModal;
    }
  });
});
