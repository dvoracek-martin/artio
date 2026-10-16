import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AccountComponent } from './account.component';

describe('AccountComponent preferred language',()=>{
  let http:HttpTestingController;
  beforeEach(async()=>{
    localStorage.setItem('artio-token','customer-token');
    await TestBed.configureTestingModule({imports:[AccountComponent],providers:[provideHttpClient(),provideHttpClientTesting()]}).compileComponents();
    http=TestBed.inject(HttpTestingController);
  });
  afterEach(()=>{http.verify();localStorage.removeItem('artio-token');localStorage.removeItem('artio-locale');});

  it('loads the saved preference and persists a new store-supported language',()=>{
    const fixture=TestBed.createComponent(AccountComponent);
    fixture.componentRef.setInput('page','account');
    fixture.componentRef.setInput('availableLanguages',['en','fr']);
    fixture.detectChanges();
    http.expectOne('/api/v1/users/me').flush({email:'customer@example.test',firstName:'Casey',lastName:'Customer'});
    http.expectOne('/api/v1/users/me/preferences').flush({preferredLocale:'fr',defaultLocale:'en',availableLanguages:['en','fr']});
    fixture.detectChanges();
    expect(fixture.componentInstance.loading).toBe(false);
    expect(fixture.componentInstance.account.email).toBe('customer@example.test');
    expect(fixture.componentInstance.preferredLocale).toBe('fr');
    fixture.componentInstance.preferredLocale='en';
    fixture.componentInstance.savePreferredLanguage();
    http.expectOne({url:'/api/v1/users/me/preferences',method:'PUT'}).flush({preferredLocale:'en',defaultLocale:'en',availableLanguages:['en','fr']});
    fixture.detectChanges();
    expect(localStorage.getItem('artio-locale')).toBe('en');
    expect(fixture.componentInstance.message).toBe('Your preferred language has been saved.');
  });
});
