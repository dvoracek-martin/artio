import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { authInterceptor } from './auth.interceptor';

describe('authInterceptor', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    localStorage.setItem('artio-admin-token', 'stale-browser-token');
  });

  afterEach(() => {
    http.verify();
    localStorage.removeItem('artio-admin-token');
  });

  it('checks first-run status without a stored session token', () => {
    const client = TestBed.inject(HttpClient);
    client.get('/api/v1/setup/status').subscribe();

    const request = http.expectOne('/api/v1/setup/status');
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({ required: true });
  });

  it('continues to send the stored token to authenticated routes', () => {
    const client = TestBed.inject(HttpClient);
    client.get('/api/v1/users/me').subscribe();

    const request = http.expectOne('/api/v1/users/me');
    expect(request.request.headers.get('Authorization')).toBe('Bearer stale-browser-token');
    request.flush({ email: 'owner@example.test' });
  });
});
