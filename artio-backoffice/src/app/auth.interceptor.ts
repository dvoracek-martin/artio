import { HttpInterceptorFn } from '@angular/common/http';
export const authInterceptor:HttpInterceptorFn=(request,next)=>{
  if(request.url.endsWith('/api/v1/setup/status'))return next(request);
  const token=localStorage.getItem('artio-admin-token');
  return next(token?request.clone({setHeaders:{Authorization:`Bearer ${token}`}}):request);
};
