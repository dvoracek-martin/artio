import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ProductWizardComponent } from './product-wizard.component';

describe('ProductWizardComponent', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProductWizardComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function loadOptions(fixture: ComponentFixture<ProductWizardComponent>) {
    fixture.detectChanges();
    http.expectOne('/api/v1/categories').flush([
      { id: 'category-1', name: 'Lighting', parentId: null, active: true }
    ]);
    http.expectOne('/api/v1/brands').flush([
      { id: 'brand-1', name: 'Artio', active: true }
    ]);
    http.expectOne('/api/v1/attribute-definitions').flush([
      { code: 'material', label: 'Material', type: 'TEXT', required: true, active: true }
    ]);
    fixture.detectChanges();
  }

  it('replaces the loading state with catalog-backed product fields', () => {
    const fixture = TestBed.createComponent(ProductWizardComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Loading your catalog…');
    http.expectOne('/api/v1/categories').flush([
      { id: 'category-1', name: 'Lighting', parentId: null, active: true }
    ]);
    http.expectOne('/api/v1/brands').flush([
      { id: 'brand-1', name: 'Artio', active: true }
    ]);
    http.expectOne('/api/v1/attribute-definitions').flush([
      { code: 'material', label: 'Material', type: 'TEXT', required: true, active: true }
    ]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).not.toContain('Loading your catalog…');
    expect(fixture.nativeElement.textContent).toContain('The essentials');
    expect(fixture.componentInstance.categories).toEqual([
      { id: 'category-1', name: 'Lighting', parentId: null, active: true }
    ]);
    expect(fixture.componentInstance.brands).toEqual([
      { id: 'brand-1', name: 'Artio', active: true }
    ]);
    expect(fixture.componentInstance.attributes).toEqual([
      { code: 'material', label: 'Material', type: 'TEXT', required: true, active: true }
    ]);
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
  });

  it('shows a recoverable error when an option request fails', () => {
    const fixture = TestBed.createComponent(ProductWizardComponent);
    fixture.detectChanges();

    http.expectOne('/api/v1/categories').flush([], { status: 200, statusText: 'OK' });
    http.expectOne('/api/v1/brands').flush([], { status: 200, statusText: 'OK' });
    http.expectOne('/api/v1/attribute-definitions').flush(
      { message: 'Catalog options are unavailable.' },
      { status: 503, statusText: 'Service Unavailable' }
    );
    fixture.detectChanges();

    expect(fixture.componentInstance.optionsLoading).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('Catalog options are unavailable.');
    expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Reload catalog options');
  });

  it('creates a draft with the selected category and normalized fields', async () => {
    const fixture = TestBed.createComponent(ProductWizardComponent);
    loadOptions(fixture);
    const component = fixture.componentInstance;
    component.draft = {
      name: '  Studio lamp  ',
      sku: 'STUDIO-LAMP-001',
      description: '  A warm reading light.  ',
      variantName: '  Brass  ',
      variantSku: 'STUDIO-LAMP-001-BRASS',
      brandId: 'brand-1',
      categoryIds: ['category-1'],
      attributes: { material: '  brass  ', unused: '   ' }
    };

    const saved = component.save();
    const request = http.expectOne('/api/v1/products/drafts');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      name: 'Studio lamp',
      sku: 'STUDIO-LAMP-001',
      description: 'A warm reading light.',
      variantName: 'Brass',
      variantSku: 'STUDIO-LAMP-001-BRASS',
      brandId: 'brand-1',
      categoryIds: ['category-1'],
      attributes: { material: 'brass' }
    });
    request.flush({ id: 'product-1' });
    await saved;

    expect(component.productId).toBe('product-1');
    expect(component.busy).toBe(false);
  });
});
