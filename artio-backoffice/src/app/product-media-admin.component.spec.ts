import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setBackofficeLocale, type BackofficeLocale } from './backoffice-locale';
import { ProductMediaAdminComponent } from './product-media-admin.component';

describe('ProductMediaAdminComponent', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [ProductMediaAdminComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.removeItem('artio-backoffice-language');
    TestBed.resetTestingModule();
  });

  it.each([
    ['en', 'CATALOG MEDIA', 'Coffee images', 'Image file', 'Alternative text', 'Loading product images…', 'No product images yet.', 'Move image 1 earlier', 'Delete image 1'],
    ['de', 'KATALOGMEDIEN', 'Coffee Bilder', 'Bilddatei', 'Alternativtext', 'Produktbilder werden geladen…', 'Noch keine Produktbilder vorhanden.', 'Bild 1 nach oben verschieben', 'Bild 1 löschen'],
    ['it', 'MEDIA DEL CATALOGO', 'Coffee immagini', 'File immagine', 'Testo alternativo', 'Caricamento delle immagini del prodotto…', 'Non ci sono ancora immagini del prodotto.', 'Anticipa immagine 1', 'Elimina immagine 1'],
    ['fr', 'MÉDIAS DU CATALOGUE', 'Coffee images', 'Fichier image', 'Texte alternatif', 'Chargement des images produit…', 'Aucune image produit pour le moment.', 'Monter l’image 1', 'Supprimer l’image 1']
  ] as const)('localizes media controls and load states in %s', (locale, eyebrow, title, fileLabel, altLabel, loading, empty, moveAria, deleteAria) => {
    setBackofficeLocale(locale as BackofficeLocale);
    const fixture = TestBed.createComponent(ProductMediaAdminComponent);
    fixture.componentInstance.productName = 'Coffee';
    fixture.componentRef.setInput('productId', 'product-1');
    fixture.detectChanges();
    http.expectOne('/api/v1/admin/catalog/media/storage-status').flush({ uploadAvailable: true });
    fixture.detectChanges();

    let page = fixture.nativeElement.textContent as string;
    expect(page).toContain(eyebrow);
    expect(page).toContain(title);
    expect(page).toContain(fileLabel);
    expect(page).toContain(altLabel);
    expect(page).toContain(loading);
    expect(page).not.toContain(empty);

    http.expectOne('/api/v1/products/product-1/media').flush([]);
    fixture.detectChanges();
    page = fixture.nativeElement.textContent as string;
    expect(page).toContain(empty);
    expect(fixture.nativeElement.querySelector('[role="status"]')).toBeNull();

    fixture.componentInstance.items = [{ id: 'media-1', altText: '', position: 0, contentUrl: '/media/1', width: 400, height: 300 }];
    fixture.detectChanges();
    const labels = Array.from(fixture.nativeElement.querySelectorAll('button[aria-label]'), (button: Element) => button.getAttribute('aria-label'));
    expect(labels).toContain(moveAria);
    expect(labels).toContain(deleteAria);
  });

  it.each([
    ['en', 'Could not load product images.'],
    ['de', 'Produktbilder konnten nicht geladen werden.'],
    ['it', 'Impossibile caricare le immagini del prodotto.'],
    ['fr', 'Impossible de charger les images produit.']
  ] as const)('shows a localized load failure in %s without a false empty state', (locale, message) => {
    setBackofficeLocale(locale as BackofficeLocale);
    const fixture = TestBed.createComponent(ProductMediaAdminComponent);
    fixture.componentRef.setInput('productId', 'product-1');
    fixture.detectChanges();
    http.expectOne('/api/v1/admin/catalog/media/storage-status').flush({ uploadAvailable: true });
    fixture.detectChanges();
    http.expectOne('/api/v1/products/product-1/media').flush({}, { status: 503, statusText: 'Service unavailable' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain(message);
    expect(fixture.nativeElement.textContent).not.toContain(locale === 'en' ? 'No product images yet.' : locale === 'de' ? 'Noch keine Produktbilder vorhanden.' : locale === 'it' ? 'Non ci sono ancora immagini del prodotto.' : 'Aucune image produit pour le moment.');
  });

  it.each([
    ['en', 'Choose a JPEG or PNG image.'],
    ['de', 'Wählen Sie ein JPEG- oder PNG-Bild aus.'],
    ['it', 'Scegli un’immagine JPEG o PNG.'],
    ['fr', 'Choisissez une image JPEG ou PNG.']
  ] as const)('localizes unsupported-file validation in %s', (locale, message) => {
    setBackofficeLocale(locale as BackofficeLocale);
    const fixture = TestBed.createComponent(ProductMediaAdminComponent);
    fixture.componentInstance.productId = 'product-1';
    fixture.componentInstance.uploadAvailable = true;
    fixture.componentInstance.file = new File(['invalid image'], 'notes.txt', { type: 'text/plain' });
    fixture.componentInstance.upload({ value: '' } as HTMLInputElement);
    expect(fixture.componentInstance.error).toBe(message);
  });

  it('confirms product-image removal in a labelled modal and preserves retry after API failure', async () => {
    const previousShowModal = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal');
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true,
      value: vi.fn(function (this: HTMLDialogElement) {
        Object.defineProperty(this, 'open', { configurable: true, value: true });
      })
    });
    try {
      const fixture = TestBed.createComponent(ProductMediaAdminComponent);
      fixture.componentRef.setInput('productId', 'product-1');
      fixture.detectChanges();
      http.expectOne('/api/v1/admin/catalog/media/storage-status').flush({ uploadAvailable: true });
      fixture.detectChanges();
      http.expectOne('/api/v1/products/product-1/media').flush([]);
      fixture.componentInstance.items = [{ id: 'media-1', altText: 'Front view', position: 0, contentUrl: '/media/1', width: 400, height: 300 }];
      fixture.detectChanges();
      fixture.componentInstance.remove(fixture.componentInstance.items[0]);
      fixture.detectChanges();
      await new Promise(resolve => setTimeout(resolve, 0));
      fixture.detectChanges();

      const dialog = fixture.nativeElement.querySelector('dialog[aria-labelledby="delete-media-title"]') as HTMLDialogElement;
      expect(dialog.open).toBe(true);
      expect(dialog.getAttribute('aria-labelledby')).toBe('delete-media-title');
      expect(dialog.querySelector('#delete-media-title')?.textContent?.trim()).toBe('Delete product image');
      expect(dialog.querySelector('.refund-payment-summary')?.textContent?.trim()).toBe('Front view');
      expect(dialog.textContent).toContain('This image will be removed from the product and cannot be restored.');

      fixture.componentInstance.confirmDelete();
      fixture.detectChanges();
      expect(dialog.querySelector('[role="status"]')?.textContent?.trim()).toBe('Deleting image…');
      const deletion = http.expectOne('/api/v1/products/product-1/media/media-1');
      deletion.flush({ detail: 'Image is still referenced by an active product.' }, { status: 409, statusText: 'Conflict' });
      fixture.detectChanges();
      expect(dialog.querySelector('[role="alert"]')?.textContent?.trim()).toBe('Image is still referenced by an active product.');
      expect((dialog.querySelector('button[type="submit"]') as HTMLButtonElement).disabled).toBe(false);
    } finally {
      if (previousShowModal) Object.defineProperty(HTMLDialogElement.prototype, 'showModal', previousShowModal);
      else delete (HTMLDialogElement.prototype as Partial<HTMLDialogElement>).showModal;
    }
  });
});
