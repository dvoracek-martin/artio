import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { backofficeText, currentBackofficeLocale } from './backoffice-locale';
import { BackofficeToastService } from './backoffice-toast.service';

/** Keep failed data reads visible as a transient global toast instead of leaving stale loading UI. */
export const loadErrorInterceptor: HttpInterceptorFn = (request, next) => {
  if (request.method !== 'GET' || !request.url.startsWith('/api/v1/')) return next(request);
  const toast = inject(BackofficeToastService);
  return next(request).pipe(catchError((error: unknown) => {
    if (!(error instanceof HttpErrorResponse) || (error.status !== 401 && error.status !== 403)) {
      toast.showError(backofficeText(currentBackofficeLocale(), 'adminLoadFailure'));
    }
    return throwError(() => error);
  }));
};
