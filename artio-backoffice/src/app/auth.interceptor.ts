import { HttpInterceptorFn } from '@angular/common/http';
export const authInterceptor:HttpInterceptorFn=(request,next)=>{const token=localStorage.getItem('artio-admin-token');return next(token?request.clone({setHeaders:{Authorization:`Bearer ${token}`}}):request);};
