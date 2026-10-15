import { bootstrapApplication } from '@angular/platform-browser';
import { isDevMode, provideZoneChangeDetection } from '@angular/core';
import { provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { AppComponent } from './app/app.component';
import { authInterceptor } from './app/auth.interceptor';

bootstrapApplication(AppComponent, { providers: [provideZoneChangeDetection(),provideHttpClient(withXhr(), withInterceptors([authInterceptor])), provideRouter([{ path: '**', component: AppComponent }])] }).catch(console.error);
if ('serviceWorker' in navigator && !isDevMode()) navigator.serviceWorker.register('/sw.js').catch(() => undefined);
