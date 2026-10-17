import { HttpInterceptorFn } from '@angular/common/http';

const publicAuthRoutes = new Set([
  '/api/v1/auth/login',
  '/api/v1/auth/register',
  '/api/v1/auth/password-reset/request',
  '/api/v1/auth/password-reset/complete',
  '/api/v1/auth/customer-password-reset/request',
  '/api/v1/auth/customer-password-reset/complete',
]);

export const authInterceptor:HttpInterceptorFn=(request,next)=>{
  const path=request.url.split('?')[0];
  if(path==='/api/v1/setup/status'||path.startsWith('/api/v1/setup/')||publicAuthRoutes.has(path))return next(request);
  const token=sessionStorage.getItem('artio-admin-token')??localStorage.getItem('artio-admin-token');
  return next(token?request.clone({setHeaders:{Authorization:`Bearer ${token}`}}):request);
};
