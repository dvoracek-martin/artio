import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { EMPTY, type Observable } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { Router, type Event } from '@angular/router';
import { AdminComponent } from './admin.component';

describe('AdminComponent setup detection', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: Router,
          useValue: {
            url: '/',
            events: EMPTY as Observable<Event>,
            navigateByUrl: () => Promise.resolve(true),
          },
        },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.removeItem('artio-admin-token');
    localStorage.removeItem('artio-admin-email');
  });

  it('shows setup when a clean installation is reported without a saved session', () => {
    localStorage.removeItem('artio-admin-token');
    const fixture = TestBed.createComponent(AdminComponent);
    fixture.detectChanges();

    http.expectOne('/api/v1/setup/status').flush({ required: true, developerMode: false });
    fixture.detectChanges();

    expect(fixture.componentInstance.setupChecked).toBe(true);
    expect(fixture.componentInstance.setupRequired).toBe(true);
    expect(fixture.componentInstance.token).toBeNull();
    http.expectNone('/api/v1/admin/catalog/products?page=0&size=5&state=ALL&sort=updatedAt&direction=desc');
    http.expectNone('/api/v1/admin/orders?page=0&size=1&sort=createdAt&direction=desc');
  });

  it('clears a stale saved session and still shows setup when the installation is clean', () => {
    localStorage.setItem('artio-admin-token', 'stale-session');
    localStorage.setItem('artio-admin-email', 'old@example.test');
    const fixture = TestBed.createComponent(AdminComponent);
    fixture.detectChanges();

    http.expectOne('/api/v1/setup/status').flush({ required: true, developerMode: false });
    fixture.detectChanges();

    expect(fixture.componentInstance.setupChecked).toBe(true);
    expect(fixture.componentInstance.setupRequired).toBe(true);
    expect(fixture.componentInstance.token).toBeNull();
    expect(localStorage.getItem('artio-admin-token')).toBeNull();
    expect(localStorage.getItem('artio-admin-email')).toBeNull();
    http.expectNone('/api/v1/users/me');
    http.expectNone('/api/v1/admin/catalog/products?page=0&size=5&state=ALL&sort=updatedAt&direction=desc');
    http.expectNone('/api/v1/admin/orders?page=0&size=1&sort=createdAt&direction=desc');
  });
});
