import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { setBackofficeLocale, type BackofficeLocale } from './backoffice-locale';
import { ProductCatalogAdminComponent } from './product-catalog-admin.component';

describe('ProductCatalogAdminComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [ProductCatalogAdminComponent] }).compileComponents();
  });

  afterEach(() => localStorage.removeItem('artio-backoffice-language'));

  it.each([
    ['en', 'YOUR CATALOG', 'Made to stand out.', 'New product', 'Search products', 'No products found.'],
    ['de', 'IHR KATALOG', 'Produkte, die auffallen.', 'Neues Produkt', 'Produkte suchen', 'Keine Produkte gefunden.'],
    ['it', 'IL TUO CATALOGO', 'Prodotti che si distinguono.', 'Nuovo prodotto', 'Cerca prodotti', 'Nessun prodotto trovato.'],
    ['fr', 'VOTRE CATALOGUE', 'Des produits qui se démarquent.', 'Nouveau produit', 'Rechercher des produits', 'Aucun produit trouvé.']
  ] as const)('renders catalog labels in %s', (locale, eyebrow, title, newProduct, searchLabel, emptyState) => {
    setBackofficeLocale(locale as BackofficeLocale);
    const fixture = TestBed.createComponent(ProductCatalogAdminComponent);
    fixture.detectChanges();

    const page = fixture.nativeElement.textContent as string;
    expect(page).toContain(eyebrow);
    expect(page).toContain(title);
    expect(page).toContain(newProduct);
    expect(page).toContain(emptyState);
    expect(fixture.nativeElement.querySelector('input[aria-label]')?.getAttribute('aria-label')).toBe(searchLabel);
  });

  it('emits product creation instead of owning parent navigation', () => {
    const fixture = TestBed.createComponent(ProductCatalogAdminComponent);
    const commands: string[] = [];
    fixture.componentInstance.command.subscribe(action => commands.push(action.type));
    fixture.detectChanges();

    const button = [...fixture.nativeElement.querySelectorAll('button')]
      .find((candidate: HTMLButtonElement) => candidate.textContent?.includes('New product')) as HTMLButtonElement;
    button.click();

    expect(commands).toEqual(['newProduct']);
  });

  it('keeps search inline and removes CSV actions from the product page', () => {
    const fixture = TestBed.createComponent(ProductCatalogAdminComponent);
    fixture.detectChanges();

    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('.catalog-search-icon svg')).not.toBeNull();
    expect([...root.querySelectorAll('button')].some(button => button.textContent?.trim() === 'Search')).toBe(false);
    expect(root.querySelector('.catalog-data-actions')).toBeNull();
    expect([...root.querySelectorAll('button')].some(button => /import csv|export csv/i.test(button.textContent ?? ''))).toBe(false);
  });
});
