import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClient } from '@angular/common/http';
import { NEVER, of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppComponent } from './app.component';

function createComponent(orderStatus: string | undefined, authenticated = true, route = '/checkout?success=true&orderId=order-1', checkoutRetry = false) {
  localStorage.removeItem('artio-token');
  localStorage.setItem('artio-locale', 'en');
  if (authenticated) localStorage.setItem('artio-token', 'test-token');
  history.replaceState({}, '', route);
  const orders = orderStatus ? [{ orderId: 'order-1', status: orderStatus }] : [];
  const http = {
    get: vi.fn((url: string) => of(
      url === '/api/v1/public/config'
        ? { googleOidcEnabled: false, countryCode: 'CH', currencyCode: 'CHF', locale: 'en' }
        : url === '/api/v1/cart'
          ? { items: [] }
          : url === '/api/v1/cart/orders'
            ? orders
            : []
    )),
    post: vi.fn((url: string, _body?: unknown, _options?: unknown) => url === '/api/v1/payments/methods'
      ? of({ methods: [{ reference: 'card-ref', displayName: 'Card' }, { reference: 'paypal-ref', displayName: 'PayPal' }], partialFailure: false })
      : checkoutRetry && url === '/api/v1/cart/checkout'
        ? of({ orderId: 'retry-order', status: 'PENDING' })
        : checkoutRetry && url.includes('/checkout-session')
          ? NEVER
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

  it('does not trust a success redirect when the customer session is missing', () => {
    const { fixture } = createComponent(undefined, false);

    expect(fixture.componentInstance.paymentReturnConfirmed).toBe(false);
    expect(fixture.nativeElement.textContent).not.toContain('Thank you for your order.');
    expect(fixture.nativeElement.textContent).toContain('We could not check the payment just now. Your order status remains unchanged.');
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
});
