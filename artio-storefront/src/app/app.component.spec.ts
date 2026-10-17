import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClient } from '@angular/common/http';
import { NEVER, Subject, of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppComponent } from './app.component';

function createComponent(orderStatus: string | undefined, authenticated = true, route = '/checkout?success=true&orderId=order-1', checkoutRetry = false, products: Array<{id:string;name:string;description:string;sku:string;active:boolean}> = [], publicProduct?: {id:string;name:string;description:string;sku:string;active:boolean;seoSlug:string;seoTitle:string;seoDescription:string}, preferredLocale: string|null = null) {
  localStorage.removeItem('artio-token');
  localStorage.setItem('artio-locale', 'en');
  if (authenticated) localStorage.setItem('artio-token', 'test-token');
  history.replaceState({}, '', route);
  const orders = orderStatus ? [{ orderId: 'order-1', status: orderStatus }] : [];
  const http = {
    get: vi.fn((url: string) => of(
      url === '/api/v1/public/config'
        ? { googleOidcEnabled: false, countryCode: 'CH', currencyCode: 'CHF', locale: 'en', availableLanguages: ['en','de'] }
        : url === '/api/v1/users/me/preferences'
          ? { preferredLocale, defaultLocale: 'en', availableLanguages: ['en','de'] }
        : url.startsWith('/api/v1/bundles/localized?') || url === '/api/v1/categories' || url === '/api/v1/brands'
          ? []
        : url.startsWith('/api/v1/public/products/')
          ? publicProduct
        : url === '/api/v1/products'
          ? products
        : url.includes('/localized?locale=')
          ? { name: 'Localized product', description: 'Localized description', seoSlug: 'localized-product', seoTitle: 'SEO product title', seoDescription: 'SEO product description' }
        : url.endsWith('/variants')
          ? url.includes(publicProduct?.id ?? 'missing-product') ? [{ id: 'variant-1', name: 'Small', active: true }] : []
        : url.endsWith('/media')
          ? [{ id: 'media-1', contentUrl: 'https://cdn.example.test/product.jpg', altText: 'Product', position: 0 }]
        : url === '/api/v1/cart'
          ? { items: route === '/checkout' ? [{ itemId: 'cart-item-1', variantId: 'variant-1', quantity: 1, unitPrice: 25.5, currency: 'CHF' }] : [] }
          : url === '/api/v1/cart/orders'
            ? orders
            : []
    )),
    post: vi.fn((url: string, _body?: unknown, _options?: unknown) => url === '/api/v1/payments/methods'
      ? of({ methods: [{ reference: 'card-ref', displayName: 'Card' }, { reference: 'paypal-ref', displayName: 'PayPal' }], partialFailure: false })
      : url.includes('/api/v1/payments/provider/orders/')
        ? of({ status: orderStatus === 'PAID' ? 'captured' : orderStatus === 'CANCELLED' ? 'cancelled' : 'pending' })
      : checkoutRetry && url === '/api/v1/cart/checkout'
        ? of({ orderId: 'retry-order', status: 'PENDING' })
        : checkoutRetry && url.includes('/checkout-session')
          ? NEVER
          : url === '/api/v1/pricing/quote'
            ? of({ unitListPrice: { amount: 25.5, currency: 'CHF' }, total: { amount: 25.5, currency: 'CHF' } })
          : of({}))
  };
  TestBed.configureTestingModule({ imports: [AppComponent], providers: [{ provide: HttpClient, useValue: http }] });
  const fixture = TestBed.createComponent(AppComponent);
  fixture.detectChanges();
  return { fixture, http };
}

describe('Storefront payment return', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
    localStorage.removeItem('artio-token');
    localStorage.removeItem('artio-locale');
    history.replaceState({}, '', '/');
    document.querySelector('link[rel="canonical"]')?.remove();
    document.querySelector('script[data-artio-seo="website"]')?.remove();
    document.querySelector('script[data-artio-seo="products"]')?.remove();
    document.querySelector('meta[name="robots"]')?.remove();
  });

  it('uses a signed-in customer language preference instead of the shop default', () => {
    const { fixture, http } = createComponent(undefined, true, '/shop', false, [], undefined, 'de');
    expect(fixture.componentInstance.locale).toBe('de');
    expect(localStorage.getItem('artio-locale')).toBe('de');
    expect(http.get).toHaveBeenCalledWith('/api/v1/users/me/preferences');
  });

  it('shows confirmation only after Core reports the returned order as paid', () => {
    const { fixture } = createComponent('PAID');

    expect(fixture.componentInstance.paymentReturnConfirmed).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Thank you for your order.');
    expect(fixture.nativeElement.textContent).toContain('Your payment is confirmed.');
  });

  it('keeps an unconfirmed provider return in processing state', () => {
    const { fixture } = createComponent('PENDING');

    expect(fixture.componentInstance.paymentReturnConfirmed).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('Checking your payment');
    expect(fixture.nativeElement.textContent).not.toContain('Thank you for your order.');
    expect(fixture.nativeElement.textContent).toContain('Your payment is still processing. Your order will update when the provider confirms it.');
  });

  it('confirms a PayPal return only after Core accepts the capture result', () => {
    const { fixture, http } = createComponent('PAID', true, '/checkout?success=true&orderId=paypal-order&token=provider-token');

    expect(fixture.componentInstance.paymentReturnConfirmed).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Your payment is confirmed.');
    expect(http.post).toHaveBeenCalledWith('/api/v1/payments/provider/orders/paypal-order/capture?token=provider-token', {});
  });

  it('does not trust a success redirect when the customer session is missing', () => {
    const { fixture } = createComponent(undefined, false);

    expect(fixture.componentInstance.paymentReturnConfirmed).toBe(false);
    expect(fixture.nativeElement.textContent).not.toContain('Thank you for your order.');
    expect(fixture.nativeElement.textContent).toContain('We could not check the payment just now. Your order status remains unchanged.');
  });

  it('marks checkout return pages private and removes transient parameters from canonical URLs', () => {
    const { fixture } = createComponent('PENDING', true, '/checkout?success=true&orderId=order-1&session_id=temporary');

    expect(document.querySelector('meta[name="robots"]')?.getAttribute('content')).toBe('noindex,nofollow');
    expect(document.querySelector<HTMLLinkElement>('link[rel="canonical"]')?.href).toBe(`${location.origin}/checkout`);
    const schema = JSON.parse(document.querySelector('script[data-artio-seo="website"]')?.textContent ?? '{}');
    expect(schema['@type']).toBe('WebSite');
    expect(fixture.nativeElement.textContent).not.toContain('Thank you for your order.');
  });

  it('publishes an indexable canonical and localized document language for the public shop', () => {
    createComponent(undefined, false, '/shop?campaign=summer');

    expect(document.querySelector('meta[name="robots"]')?.getAttribute('content')).toBe('index,follow');
    expect(document.querySelector<HTMLLinkElement>('link[rel="canonical"]')?.href).toBe(`${location.origin}/shop`);
    expect(document.documentElement.lang).toBe('en');
    expect(document.querySelector('meta[property="og:type"]')?.getAttribute('content')).toBe('website');
  });

  it('publishes product structured data with matching standalone detail links on the shop page', () => {
    const product = { id: 'product-1', name: 'Original name', description: 'Original description', sku: 'SKU-1', active: true };
    const { fixture } = createComponent(undefined, false, '/shop', false, [product]);

    const schema = JSON.parse(document.querySelector('script[data-artio-seo="products"]')?.textContent ?? '{}');
    expect(schema['@type']).toBe('ItemList');
    expect(schema.numberOfItems).toBe(1);
    expect(schema.itemListElement[0].item).toMatchObject({
      '@type': 'Product',
      name: 'SEO product title',
      description: 'SEO product description',
      sku: 'SKU-1',
      image: 'https://cdn.example.test/product.jpg',
      url: `${location.origin}/products/localized-product?locale=en`
    });
    expect(schema.itemListElement[0].item['@id']).toBe(`${location.origin}/products/localized-product?locale=en`);
    expect(fixture.nativeElement.querySelector('#product-product-1')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('a[href="/products/localized-product?locale=en"]')).toBeTruthy();
  });

  it('renders a localized product detail page with a selectable variant, Core quote and canonical product schema', () => {
    const product = { id: 'product-1', name: 'Base name', description: 'Base description', sku: 'SKU-1', active: true, seoSlug: 'tasse', seoTitle: 'Keramiktasse', seoDescription: 'Handgefertigte Keramiktasse' };
    const { fixture, http } = createComponent(undefined, false, '/products/tasse?locale=de', false, [], product);

    expect(fixture.nativeElement.textContent).toContain('Keramiktasse');
    expect(fixture.nativeElement.textContent).toContain('Handgefertigte Keramiktasse');
    expect(fixture.nativeElement.querySelector('.variant-select select')?.value).toBe('variant-1');
    expect(fixture.nativeElement.textContent).toContain('25.5 CHF');
    expect(http.get).toHaveBeenCalledWith('/api/v1/public/products/tasse?locale=de');
    expect(http.post).toHaveBeenCalledWith('/api/v1/pricing/quote', { sellableId: 'variant-1', market: 'CH', channel: 'WEB', jurisdiction: 'CH', quantity: 1 });
    expect(document.querySelector('meta[name="robots"]')?.getAttribute('content')).toBe('index,follow');
    expect(document.querySelector('meta[property="og:type"]')?.getAttribute('content')).toBe('product');
    expect(document.querySelector<HTMLLinkElement>('link[rel="canonical"]')?.href).toBe(`${location.origin}/products/tasse?locale=de`);
    const schema = JSON.parse(document.querySelector('script[data-artio-seo="products"]')?.textContent ?? '{}');
    expect(schema).toMatchObject({ '@type': 'Product', name: 'Keramiktasse', sku: 'SKU-1', offers: { '@type': 'Offer', price: 25.5, priceCurrency: 'CHF' } });
  });

  it('gives checkout address and payment controls visible accessible labels', () => {
    const { fixture } = createComponent(undefined, true, '/checkout');
    const labels = Array.from(fixture.nativeElement.querySelectorAll('label')) as HTMLLabelElement[];

    for (const text of ['Recipient name', 'Street and house number', 'Postal code', 'City', 'Billing address (optional)', 'Country', 'Parcel weight (kg)', 'Delivery method', 'Payment method']) {
      const label = labels.find(candidate => candidate.textContent?.trim().startsWith(text));
      expect(label, `expected a visible label for ${text}`).toBeDefined();
      expect(label?.querySelector('input,select')).toBeTruthy();
    }
  });

  it('keeps the selected Core method and checkout idempotency key when the payment session is retried', () => {
    const { fixture, http } = createComponent(undefined, true, '/checkout', true);
    const component = fixture.componentInstance;
    component.paymentMethodId = 'paypal-ref';
    component.shippingMethodId = 'swiss-post-eco';
    component.checkout();
    component.checkout();

    const checkoutCalls = http.post.mock.calls.filter(([url]) => url === '/api/v1/cart/checkout');
    expect(checkoutCalls).toHaveLength(2);
    const firstKey = (checkoutCalls[0][2] as unknown as { headers: { get(name: string): string | null } }).headers.get('Idempotency-Key');
    const secondKey = (checkoutCalls[1][2] as unknown as { headers: { get(name: string): string | null } }).headers.get('Idempotency-Key');
    expect(firstKey).toBeTruthy();
    expect(secondKey).toBe(firstKey);

    const sessionCalls = http.post.mock.calls.filter(([url]) => String(url).includes('/checkout-session'));
    expect(sessionCalls).toHaveLength(2);
    expect(sessionCalls.map(([, body]) => body)).toEqual([{ methodReference: 'paypal-ref' }, { methodReference: 'paypal-ref' }]);
  });

  it('ignores stale Core payment methods after the customer changes market', () => {
    const { fixture, http } = createComponent(undefined, true, '/checkout');
    const stale = new Subject<{ methods: any[]; partialFailure: boolean }>();
    const current = new Subject<{ methods: any[]; partialFailure: boolean }>();
    let request = 0;
    http.post.mockImplementation((url: string) => url === '/api/v1/payments/methods'
      ? (request++ === 0 ? stale : current)
      : of({}));

    const component = fixture.componentInstance;
    component.shippingCountry = 'CH';
    component.loadPaymentMethods();
    component.shippingCountry = 'US';
    component.loadPaymentMethods();

    current.next({ methods: [{ reference: 'us-card', displayName: 'US card' }], partialFailure: false });
    stale.next({ methods: [{ reference: 'ch-card', displayName: 'Swiss card' }], partialFailure: true });

    expect(component.paymentMethods).toEqual([{ reference: 'us-card', displayName: 'US card' }]);
    expect(component.paymentMethodId).toBe('us-card');
    expect(component.paymentMethodsPartialFailure).toBe(false);
    expect(component.paymentMethodsLoading).toBe(false);
  });

  it('ignores stale Core shipping services after the delivery address changes', async () => {
    vi.useFakeTimers();
    try {
      const { fixture, http } = createComponent(undefined, true, '/checkout');
      const stale = new Subject<{ methods: any[]; partialFailure: boolean }>();
      const current = new Subject<{ methods: any[]; partialFailure: boolean }>();
      let request = 0;
      http.get.mockImplementation((url: string) => url.startsWith('/api/v1/cart/shipping-methods?')
        ? (request++ === 0 ? stale : current) as any
        : of([]) as any);

      const component = fixture.componentInstance;
      component.shippingCountry = 'CH';
      component.shippingAddress = 'Old address';
      component.loadShippingMethods();
      await vi.advanceTimersByTimeAsync(250);
      component.shippingCountry = 'US';
      component.shippingAddress = 'Current address';
      component.loadShippingMethods();
      await vi.advanceTimersByTimeAsync(250);

      current.next({ methods: [{ methodId: 'us-ground', name: 'US Ground' }], partialFailure: false });
      stale.next({ methods: [{ methodId: 'ch-post', name: 'Swiss Post' }], partialFailure: true });

      expect(component.shippingMethods).toEqual([{ methodId: 'us-ground', name: 'US Ground' }]);
      expect(component.shippingMethodId).toBe('us-ground');
      expect(component.shippingMethodsPartialFailure).toBe(false);
      expect(component.shippingMethodsLoading).toBe(false);
      const queries = http.get.mock.calls.map(([url]) => String(url)).filter(url => url.startsWith('/api/v1/cart/shipping-methods?'));
      expect(queries.map(query => new URLSearchParams(query.split('?')[1]).get('country'))).toEqual(['CH', 'US']);
    } finally {
      vi.clearAllTimers();
      vi.useRealTimers();
    }
  });
});

describe('Storefront currency formatting', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
    localStorage.removeItem('artio-token');
    localStorage.removeItem('artio-locale');
    history.replaceState({}, '', '/');
    document.querySelector('link[rel="canonical"]')?.remove();
    document.querySelector('script[data-artioSeo="website"]')?.remove();
    document.querySelector('script[data-artioSeo="products"]')?.remove();
  });

  it('formats CHF amounts using each supported storefront locale', () => {
    const { fixture } = createComponent(undefined, false, '/cart');
    const item = { itemId: 'line-1', variantId: 'variant-1', quantity: 1, unitPrice: 12345.5, currency: 'CHF' };
    const expected: Record<string, string> = {
      en: 'CHF\u00a012,345.50',
      de: '12.345,50\u00a0CHF',
      fr: '12\u202f345,50\u00a0CHF',
      it: '12.345,50\u00a0CHF',
    };

    for (const locale of ['en', 'de', 'fr', 'it'] as const) {
      fixture.componentInstance.setPreferredLocale(locale);
      expect(fixture.componentInstance.lineTotal(item)).toBe(expected[locale]);
    }
  });
});
