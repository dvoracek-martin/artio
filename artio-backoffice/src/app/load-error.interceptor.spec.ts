import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideHttpClient as provideClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { loadErrorInterceptor } from './load-error.interceptor';
import { BackofficeToastService } from './backoffice-toast.service';

describe('loadErrorInterceptor', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  let toast: BackofficeToastService;

  beforeEach(() => {
    TestBed.configureTestingModule({providers:[provideClient(withInterceptors([loadErrorInterceptor])),provideHttpClientTesting()]});
    http=TestBed.inject(HttpTestingController);
    client=TestBed.inject(HttpClient);
    toast=TestBed.inject(BackofficeToastService);
  });

  afterEach(()=>http.verify());

  it('shows a localized global error toast for failed data loads',()=>{
    client.get('/api/v1/orders').subscribe({error:()=>undefined});
    http.expectOne('/api/v1/orders').flush({}, {status:500,statusText:'Server Error'});
    expect(toast.errorMessage()).toBe('Could not load this information.');
  });

  it('does not turn authentication failures into misleading load errors',()=>{
    client.get('/api/v1/orders').subscribe({error:()=>undefined});
    http.expectOne('/api/v1/orders').flush({}, {status:401,statusText:'Unauthorized'});
    expect(toast.errorMessage()).toBe('');
  });
});
