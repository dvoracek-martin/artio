import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AccountSettingsComponent } from './account-settings.component';

describe('AccountSettingsComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AccountSettingsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('prefills the current profile while leaving all password fields blank', () => {
    const previousShowModal = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal');
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true,
      value: vi.fn(function (this: HTMLDialogElement) {
        Object.defineProperty(this, 'open', { configurable: true, value: true });
      })
    });

    try {
      const fixture = TestBed.createComponent(AccountSettingsComponent);
      fixture.componentRef.setInput('open', true);
      fixture.detectChanges();
      TestBed.inject(HttpTestingController).expectOne('/api/v1/users/me').flush({
        firstName: 'Alex', lastName: 'Developer', email: 'alex@example.test', emailVerified: true
      });
      fixture.detectChanges();

      expect(fixture.componentInstance.profileLoaded).toBe(true);
      expect(fixture.componentInstance.profile).toMatchObject({ firstName: 'Alex', lastName: 'Developer', email: 'alex@example.test' });
      expect(fixture.componentInstance.profilePassword).toBe('');
      expect(fixture.componentInstance.passwords).toEqual({ currentPassword: '', newPassword: '', confirmPassword: '' });
      expect(fixture.componentInstance.emailDraft.currentPassword).toBe('');
    } finally {
      if (previousShowModal) Object.defineProperty(HTMLDialogElement.prototype, 'showModal', previousShowModal);
      else delete (HTMLDialogElement.prototype as Partial<HTMLDialogElement>).showModal;
    }
  });
});
