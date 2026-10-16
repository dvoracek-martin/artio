import { provideZoneChangeDetection } from "@angular/core";
import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { AdminComponent } from './app/admin.component';
import { authInterceptor } from './app/auth.interceptor';
import { loadErrorInterceptor } from './app/load-error.interceptor';
bootstrapApplication(AdminComponent,{providers:[provideZoneChangeDetection(),provideHttpClient(withXhr(), withInterceptors([authInterceptor, loadErrorInterceptor])),provideRouter([{path:'**',component:AdminComponent}])] }).catch(console.error);
