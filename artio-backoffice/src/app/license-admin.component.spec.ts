import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { LicenseAdminComponent } from './license-admin.component';

describe('LicenseAdminComponent', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LicenseAdminComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.removeItem('artio-backoffice-language');
  });

  it('renders community status and installs a selected signed entitlement through the versioned API', () => {
    const fixture = TestBed.createComponent(LicenseAdminComponent);
    fixture.detectChanges();
    const status = http.expectOne('/api/v1/admin/system/license');
    status.flush({
      installationId: 'install-1', edition: 'COMMUNITY', state: 'ACTIVE', features: [], expiresAt: null, graceUntil: null,
      verificationStatus: 'LOCAL', refreshConfigured: false, refreshStatus: 'NOT_CONFIGURED',
      lastRefreshAttemptAt: null, lastRefreshSuccessAt: null, refreshInProgress: false,
    });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Community');
    expect(fixture.nativeElement.textContent).toContain('No active Enterprise entitlement');
    const fileInput = fixture.nativeElement.querySelector('input[type="file"]') as HTMLInputElement;
    const signedDocument = '{"edition":"ENTERPRISE","signature":"signed-value"}';
    fixture.componentInstance.document = signedDocument;
    fixture.componentInstance.install(fileInput);
    fixture.detectChanges();

    const install = http.expectOne('/api/v1/admin/system/license');
    expect(install.request.method).toBe('POST');
    expect(install.request.headers.get('Content-Type')).toBe('application/json');
    expect(install.request.body).toBe(signedDocument);
    expect(fixture.nativeElement.querySelector('button.primary')?.textContent).toContain('Verifying');
    expect((fixture.nativeElement.querySelector('button.primary') as HTMLButtonElement).disabled).toBe(true);
    install.flush({ ...fixture.componentInstance.status, edition: 'ENTERPRISE', features: ['LOYALTY'] });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Loyalty');
    expect(fileInput.value).toBe('');
  });

  it('surfaces unavailable status and rejects an oversized entitlement before upload', () => {
    const fixture = TestBed.createComponent(LicenseAdminComponent);
    fixture.detectChanges();
    http.expectOne('/api/v1/admin/system/license').flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('Could not read license status');

    const input = fixture.nativeElement.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, 'files', { configurable: true, value: [new File(['x'.repeat(65537)], 'oversized.json', { type: 'application/json' })] });
    fixture.componentInstance.readFile({ target: input } as unknown as Event);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('exceeds 64 KiB');
    expect(fixture.componentInstance.document).toBe('');
  });
});
