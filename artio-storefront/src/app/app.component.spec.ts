import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClient } from '@angular/common/http';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppComponent } from './app.component';

function createComponent(orderStatus: string | undefined, authenticated = true): ComponentFixture<AppComponent> {
  localStorage.removeItem('artio-token');
  localStorage.setItem('artio-locale', 'en');
  if (authenticated) localStorage.setItem('artio-token', 'test-token');
  history.replaceState({}, '', '/checkout?success=true&orderId=order-1');
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
    post: vi.fn((url: string) => of(url === '/api/v1/payments/methods' ? { methods: [], partialFailure: false } : {}))
  };
  TestBed.configureTestingModule({ imports: [AppComponent], providers: [{ provide: HttpClient, useValue: http }] });
  const fixture = TestBed.createComponent(AppComponent);
  fixture.detectChanges();
  return fixture;
}

describe('Storefront payment return', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
    localStorage.removeItem('artio-token');
    localStorage.removeItem('artio-locale');
    history.replaceState({}, '', '/');
  });

  it('shows confirmation only after Core reports the returned order as paid', () => {
    const fixture = createComponent('PAID');

    expect(fixture.componentInstance.paymentReturnConfirmed).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Thank you for your order.');
    expect(fixture.nativeElement.textContent).toContain('Your payment is confirmed.');
  });

  it('keeps an unconfirmed provider return in processing state', () => {
    const fixture = createComponent('PENDING');

    expect(fixture.componentInstance.paymentReturnConfirmed).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('Checking your payment');
    expect(fixture.nativeElement.textContent).not.toContain('Thank you for your order.');
    expect(fixture.nativeElement.textContent).toContain('Your payment is still processing. Your order will update when the provider confirms it.');
  });

  it('does not trust a success redirect when the customer session is missing', () => {
    const fixture = createComponent(undefined, false);

    expect(fixture.componentInstance.paymentReturnConfirmed).toBe(false);
    expect(fixture.nativeElement.textContent).not.toContain('Thank you for your order.');
    expect(fixture.nativeElement.textContent).toContain('We could not check the payment just now. Your order status remains unchanged.');
  });
});
