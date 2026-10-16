import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ExtensionLocalArchiveComponent } from './extension-local-archive.component';

describe('ExtensionLocalArchiveComponent', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ExtensionLocalArchiveComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('uploads only to the versioned installation endpoint and reports the durable job', () => {
    const fixture = TestBed.createComponent(ExtensionLocalArchiveComponent);
    const component = fixture.componentInstance;
    component.pluginId = 'customer.example';
    component.file = new File(['oci-layout'], 'bundle.tar', { type: 'application/x-tar' });

    component.upload();

    const request = http.expectOne('/api/v1/admin/extensions/customer.example/installations/archive');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/i);
    expect(request.request.body instanceof FormData).toBe(true);
    request.flush({ jobId: 'job-123', state: 'REQUESTED' }, { status: 202, statusText: 'Accepted' });
    const status = http.expectOne('/api/v1/admin/extensions/installations/job-123');
    expect(status.request.method).toBe('GET');
    status.flush({ jobId: 'job-123', state: 'INSTALLED' });

    expect(component.uploading).toBe(false);
    expect(component.message).toContain('job-123');
    expect(component.message).toContain('INSTALLED');
  });
});
