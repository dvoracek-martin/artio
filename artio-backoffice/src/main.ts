import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { AdminComponent } from './app/admin.component';
import { authInterceptor } from './app/auth.interceptor';
bootstrapApplication(AdminComponent,{providers:[provideHttpClient(withInterceptors([authInterceptor])),provideRouter([{path:'**',component:AdminComponent}])] }).catch(console.error);
