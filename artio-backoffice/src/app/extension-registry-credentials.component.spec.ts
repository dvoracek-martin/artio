import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ExtensionRegistryCredentialsComponent } from './extension-registry-credentials.component';

describe('ExtensionRegistryCredentialsComponent', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ExtensionRegistryCredentialsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('uses the versioned registry API and never renders a saved password', async () => {
    const fixture = TestBed.createComponent(ExtensionRegistryCredentialsComponent);
    fixture.detectChanges();
    const load = http.expectOne('/api/v1/admin/extensions/registries');
    expect(load.request.method).toBe('GET');
    load.flush([{ registryHost: 'registry.example:5000', username: 'publisher', createdAt: 'now', updatedAt: 'now' }]);
    await fixture.whenStable();
    fixture.detectChanges();

    fixture.componentInstance.registryHost = 'registry.example:5000';
    fixture.componentInstance.username = 'publisher';
    fixture.componentInstance.password = 'secret-registry-password';
    fixture.componentInstance.save();
    const save = http.expectOne('/api/v1/admin/extensions/registries/registry.example%3A5000');
    expect(save.request.method).toBe('PUT');
    expect(save.request.body.password).toBe('secret-registry-password');
    save.flush({ registryHost: 'registry.example:5000', username: 'publisher', createdAt: 'now', updatedAt: 'now' });
    http.expectOne('/api/v1/admin/extensions/registries').flush([
      { registryHost: 'registry.example:5000', username: 'publisher', createdAt: 'now', updatedAt: 'now' },
    ]);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.componentInstance.password).toBe('');
    expect(fixture.nativeElement.textContent).toContain('registry.example:5000');
    expect(fixture.nativeElement.textContent).not.toContain('secret-registry-password');
  });
});
