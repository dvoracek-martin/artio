import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { setBackofficeLocale } from './backoffice-locale';
import { ExtensionSecurityComponent } from './extension-security.component';

describe('ExtensionSecurityComponent', () => {
  let fixture: ComponentFixture<ExtensionSecurityComponent>;

  beforeEach(async () => {
    setBackofficeLocale('en');
    await TestBed.configureTestingModule({ imports: [ExtensionSecurityComponent] }).compileComponents();
    fixture = TestBed.createComponent(ExtensionSecurityComponent);
  });

  afterEach(() => fixture.destroy());

  it('shows verified feed freshness, release bans, exact image digests, and runtime containment results read-only', () => {
    fixture.componentInstance.status = {
      sync: { sequence: 42, stale: false, lastSuccessAt: '2026-10-17T10:00:00Z' },
      releases: [{ pluginId: 'shipping.demo', version: '1.2.3', securityStatus: 'BANNED', supportStatus: 'REVOKED', reasonCode: 'SECURITY_REVOKED' }],
      bannedArtifacts: [{ imageDigest: `sha256:${'a'.repeat(64)}`, pluginId: 'shipping.demo', version: '1.2.3', reasonCode: 'SECURITY_REVOKED' }],
      blockedTargets: [{ runtimeIdentity: 'instance:demo:one', pluginId: 'shipping.demo', containmentStatus: 'STOP_FAILED', lastErrorCode: 'ExtensionUnavailableException' }],
    };

    fixture.detectChanges();

    const page = fixture.nativeElement as HTMLElement;
    expect(page.textContent).toContain('Verified feed #42');
    expect(page.textContent).toContain('BANNED · REVOKED');
    expect(page.textContent).toContain(`sha256:${'a'.repeat(64)}`);
    expect(page.textContent).toContain('Stop failed');
    expect(page.textContent).toContain('ExtensionUnavailableException');
    expect(page.querySelectorAll('button')).toHaveLength(0);
    expect(page.querySelectorAll('input,select,textarea')).toHaveLength(0);
  });

  it('makes missing verified feed and empty advisory state explicit', () => {
    fixture.componentInstance.status = { sync: { sequence: 0 }, releases: [], bannedArtifacts: [], blockedTargets: [] };

    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No verified advisory snapshot has been received yet.');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No banned image digests.');
  });
});
