import { Injectable, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class BackofficeToastService {
  readonly errorMessage = signal('');
  private timeout: ReturnType<typeof setTimeout> | undefined;

  showError(message: string): void {
    this.errorMessage.set(message);
    if (this.timeout) clearTimeout(this.timeout);
    this.timeout = setTimeout(() => this.errorMessage.set(''), 6000);
  }

  dismissError(): void {
    if (this.timeout) clearTimeout(this.timeout);
    this.errorMessage.set('');
  }
}
