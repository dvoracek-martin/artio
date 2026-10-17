import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { EMPTY, type Observable } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Router, type Event } from '@angular/router';
import { AdminComponent } from './admin.component';

describe('AdminComponent setup detection', () => {
  let http: HttpTestingController;
  let router: {url:string;events:Observable<Event>;navigateByUrl:ReturnType<typeof vi.fn>};

  beforeEach(async () => {
    router={url:'/',events:EMPTY as Observable<Event>,navigateByUrl:vi.fn(()=>Promise.resolve(true))};
    await TestBed.configureTestingModule({
      imports: [AdminComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: Router,
          useValue: router,
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

  it('redirects the legacy camel-case user URL to its kebab-case route',()=>{
    router.url='/userAdmin';
    const fixture=TestBed.createComponent(AdminComponent);
    fixture.detectChanges();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/user-admin',{replaceUrl:true});
    http.expectOne('/api/v1/setup/status').flush({required:true,developerMode:false});
  });

  it('loads managed users on the user-admin route after session setup',()=>{
    const fixture=TestBed.createComponent(AdminComponent);
    fixture.detectChanges();
    http.expectOne('/api/v1/setup/status').flush({required:false,developerMode:false});
    const component=fixture.componentInstance;
    component.token=`header.${btoa(JSON.stringify({role:'SUPERADMIN',sub:'00000000-0000-0000-0000-000000000001'}))}.signature`;
    router.url='/user-admin';
    (component as any).syncRoute();
    http.expectOne('/api/v1/superadmin/users?limit=100&offset=0').flush([{id:'self',email:'admin@example.test',firstName:'Alex',lastName:'Admin',role:'SUPERADMIN',permissions:[],emailVerified:true,active:true}]);
    fixture.detectChanges();
    expect(component.users).toHaveLength(1);
    expect(fixture.nativeElement.textContent).toContain('admin@example.test');
  });

  it('asks Core to verify and queue a Marketplace v1 release without relaying its manifest', () => {
    const fixture = TestBed.createComponent(AdminComponent);
    fixture.detectChanges();
    http.expectOne('/api/v1/setup/status').flush({ required: false, developerMode: false });
    fixture.detectChanges();

    fixture.componentInstance.installMarketplaceRelease({ pluginId: 'legacy.plugin', version: '1.0.0' });
    const installation = http.expectOne('/api/v1/admin/extensions/marketplace/plugins/legacy.plugin/releases/1.0.0/installations');
    expect(installation.request.body.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    installation.flush({ job: { jobId: 'job-123', state: 'REQUESTED' } });
    http.expectOne('/api/v1/admin/extensions').flush([]);
    http.expectOne('/api/v1/admin/extensions/catalog').flush([]);
    http.expectOne('/api/v1/admin/extensions/installations/job-123').flush({ jobId: 'job-123', state: 'INSTALLED' });
    http.expectOne('/api/v1/admin/extensions').flush([]);
    http.expectOne('/api/v1/admin/extensions/catalog').flush([]);
    expect(fixture.componentInstance.extensionHealth).toContain('Plugin installed');
  });

  it('queues a Marketplace capability release through the same server-verified v1 API route', () => {
    const fixture = TestBed.createComponent(AdminComponent);
    fixture.detectChanges();
    http.expectOne('/api/v1/setup/status').flush({ required: false, developerMode: false });
    fixture.detectChanges();

    fixture.componentInstance.installMarketplaceRelease({ pluginId: 'capability.plugin', version: '1.0.0' });
    const installation = http.expectOne('/api/v1/admin/extensions/marketplace/plugins/capability.plugin/releases/1.0.0/installations');
    expect(installation.request.body.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    installation.flush({ job: { jobId: 'job-456', state: 'REQUESTED' } });
    http.expectOne('/api/v1/admin/extensions').flush([]);
    http.expectOne('/api/v1/admin/extensions/catalog').flush([]);
    http.expectOne('/api/v1/admin/extensions/installations/job-456').flush({ jobId: 'job-456', state: 'FAILED', errorCode: 'IMAGE_PULL_DENIED' });
    http.expectOne('/api/v1/admin/extensions').flush([]);
    http.expectOne('/api/v1/admin/extensions/catalog').flush([]);
    expect(fixture.componentInstance.extensionHealth).toContain('Installation failed · IMAGE_PULL_DENIED');
  });

  it('registers a Marketplace v2 release through Core before installing it from the official source', () => {
    const fixture = TestBed.createComponent(AdminComponent);
    fixture.detectChanges();
    http.expectOne('/api/v1/setup/status').flush({ required: false, developerMode: false });
    fixture.detectChanges();

    const release = { pluginId: 'capability.plugin', version: '2.1.0' };
    fixture.componentInstance.registerMarketplaceRelease(release);
    const registration = http.expectOne('/api/v1/admin/extensions/marketplace/plugins/capability.plugin/releases/2.1.0/register');
    expect(registration.request.method).toBe('POST');
    expect(registration.request.body).toEqual({});
    registration.flush({ alreadyRegistered: false });
    expect(fixture.componentInstance.extensionHealth).toContain('Registered Marketplace release capability.plugin v2.1.0');

    fixture.componentInstance.installMarketplaceRelease(release);
    const installation = http.expectOne('/api/v1/admin/extensions/marketplace/plugins/capability.plugin/releases/2.1.0/installations');
    expect(installation.request.method).toBe('POST');
    expect(installation.request.body.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    installation.flush({ job: { jobId: 'job-v2', state: 'REQUESTED' } });
    http.expectOne('/api/v1/admin/extensions').flush([]);
    http.expectOne('/api/v1/admin/extensions/catalog').flush([]);
    http.expectOne('/api/v1/admin/extensions/installations/job-v2').flush({ jobId: 'job-v2', state: 'INSTALLED' });
    http.expectOne('/api/v1/admin/extensions').flush([]);
    http.expectOne('/api/v1/admin/extensions/catalog').flush([]);
    expect(fixture.componentInstance.extensionHealth).toContain('capability.plugin@2.1.0 · Plugin installed · job-v2');
  });
});
