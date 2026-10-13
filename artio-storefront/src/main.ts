import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { AppComponent } from './app/app.component';
import { authInterceptor } from './app/auth.interceptor';

bootstrapApplication(AppComponent, { providers: [provideHttpClient(withInterceptors([authInterceptor])), provideRouter([{ path: '**', component: AppComponent }])] }).catch(console.error);
