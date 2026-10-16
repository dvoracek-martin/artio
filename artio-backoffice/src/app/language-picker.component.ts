import { Component, ElementRef, EventEmitter, HostListener, Input, Output, ViewChild } from '@angular/core';

type LocaleOption = { value: 'en' | 'de' | 'it' | 'fr'; label: string };

@Component({
  selector: 'artio-language-picker',
  standalone: true,
  template: `
    <details #menu class="language-menu">
      <summary [attr.aria-label]="label" [title]="label">
        <span class="language-menu-globe" aria-hidden="true">◎</span>
        <span>{{selectedLabel}}</span>
        <span class="language-menu-chevron" aria-hidden="true">⌄</span>
      </summary>
      <div class="language-menu-options" role="listbox" [attr.aria-label]="label">
        @for (option of options; track option.value) {
          <button type="button" role="option" [attr.aria-selected]="locale===option.value" [class.selected]="locale===option.value" (click)="choose(option.value)">
            <span>{{option.label}}</span>@if(locale===option.value){<span aria-hidden="true">✓</span>}
          </button>
        }
      </div>
    </details>
  `
})
export class LanguagePickerComponent {
  @ViewChild('menu') private menu?: ElementRef<HTMLDetailsElement>;
  @Input() locale: 'en' | 'de' | 'it' | 'fr' = 'en';
  @Input() label = 'Language';
  @Output() localeChange = new EventEmitter<'en' | 'de' | 'it' | 'fr'>();
  readonly options: LocaleOption[] = [
    { value: 'en', label: 'English' }, { value: 'de', label: 'Deutsch' },
    { value: 'it', label: 'Italiano' }, { value: 'fr', label: 'Français' }
  ];
  get selectedLabel(): string { return this.options.find(option => option.value === this.locale)?.label ?? 'English'; }
  choose(locale: LocaleOption['value']): void { this.localeChange.emit(locale); if (this.menu) this.menu.nativeElement.open = false; }
  @HostListener('document:click', ['$event']) closeOutside(event: MouseEvent): void {
    if (this.menu && !this.menu.nativeElement.contains(event.target as Node)) this.menu.nativeElement.open = false;
  }
  @HostListener('document:keydown.escape') closeOnEscape(): void { if (this.menu) this.menu.nativeElement.open = false; }
}
