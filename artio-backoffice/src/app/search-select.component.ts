
import { Component, ElementRef, EventEmitter, HostListener, Input, Output, forwardRef, inject, ChangeDetectionStrategy } from '@angular/core';
import { ControlValueAccessor, FormsModule, NG_VALUE_ACCESSOR } from '@angular/forms';
import { backofficeText, currentBackofficeLocale, BackofficeTextKey } from './backoffice-locale';

export type SelectOption = { value: string; label: string; detail?: string; disabled?: boolean };
let nextSelectId = 0;
@Component({
    selector: 'artio-search-select', imports: [FormsModule],
    providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SearchSelectComponent), multi: true }],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `
    <div class="search-select" [class.is-open]="open" [class.is-disabled]="disabled">
      <button type="button" class="search-select-trigger" [id]="id" [disabled]="disabled" [attr.aria-label]="label" [attr.aria-describedby]="id+'-selection'" [attr.aria-expanded]="open" [attr.aria-controls]="id+'-options'" aria-haspopup="listbox" (click)="toggle()" (keydown.arrowdown)="openMenu($event)">
        <span class="select-values">@if (multiple) {
          @for (option of selectedOptions; track optionValue($index, option)) {
            <span class="select-chip">{{option.label}}</span>
            }@if (!values.length) {
            <span class="select-placeholder">{{placeholder}}</span>
          }
        } @else {
          <span [class.select-placeholder]="!values.length">{{selectedLabel}}</span>
          }</span><span aria-hidden="true">⌄</span>
        </button>
        <span class="sr-only" [id]="id+'-selection'">{{selectionSummary}}</span>
        @if (open) {
          <div class="search-select-popover" [class.opens-above]="position.up" [style.max-height.px]="position.height">
            <div class="select-search"><span class="catalog-search-icon" aria-hidden="true"><svg viewBox="0 0 24 24" focusable="false"><circle cx="10.8" cy="10.8" r="6.3"></circle><path d="m16 16 4.2 4.2"></path></svg></span><input type="search" [(ngModel)]="query" (ngModelChange)="search.emit($event)" [ngModelOptions]="{standalone:true}" [attr.aria-label]="text('selectSearch',{label})" [placeholder]="text('selectSearchPlaceholder',{label:label.toLowerCase()})" (keydown.arrowdown)="focusOption($event,0)" (keydown.escape)="close()" autocomplete="off"></div>
            @if (loading) {
              <p class="select-no-results loading-indicator" role="status">{{text('selectLoading')}}</p>
            }
            @if (multiple) {
              <div class="select-selection-bar"><span>{{selectedCount}}</span><button type="button" (click)="clear()" [disabled]="!values.length">{{text('selectClear')}}</button></div>
            }
            <div class="select-options" [style.max-height.px]="position.height-(multiple?130:65)" [id]="id+'-options'" role="listbox" [attr.aria-label]="label" [attr.aria-multiselectable]="multiple">
              @for (option of filtered; track optionValue(index, option); let index = $index) {
                <button type="button" class="select-option" role="option" [disabled]="option.disabled" [attr.aria-selected]="values.includes(option.value)" [class.is-selected]="values.includes(option.value)" (click)="choose(option.value)" (keydown.arrowdown)="focusOption($event,index+1)" (keydown.arrowup)="focusOption($event,index-1)" (keydown.escape)="close()">
                  <span class="select-check" [class.multiple]="multiple" aria-hidden="true">{{values.includes(option.value)?'✓':''}}</span><span><strong>{{option.label}}</strong>@if (option.detail) {
                  <small>{{option.detail}}</small>
                }</span>
              </button>
              }@if (!loading && !filtered.length) {
              <p class="select-no-results">{{text('selectNoResults')}}</p>
            }
          </div>
          @if (multiple) {
            <button type="button" class="select-done" (click)="close()">{{text('selectDone',{count:values.length.toString()})}}</button>
          }
        </div>
      }
    </div>`
})
export class SearchSelectComponent implements ControlValueAccessor {
  @Input() options: SelectOption[] = [];
  @Input() multiple = false;
  @Input() label = 'Options';
  @Input() placeholder = 'Choose an option';
  @Input() loading = false;
  @Output() search = new EventEmitter<string>();
  id = 'artio-select-' + nextSelectId++;
  open = false;
  disabled = false;
  query = '';
  position={height:340,up:false};
  values: string[] = [];
  private host = inject<ElementRef<HTMLElement>>(ElementRef);
  private change: (value: string | string[]) => void = () => {};
  private touched: () => void = () => {};
  get selectedOptions(): SelectOption[] { return this.values.map(value => this.options.find(x => x.value === value) ?? { value, label: value }); }
  optionValue(index:number,option:SelectOption):string{return option.value;}
  get selectedLabel():string{return this.selectedOptions.length ? this.selectedOptions[0].label : this.placeholder;}
  get selectionSummary():string{return this.selectedOptions.length?this.selectedOptions.map(option=>option.label).join(', '):this.text('selectNothingSelected');}
  get selectedCount():string{return this.text('selectSelected',{count:this.values.length.toString()});}
  text(key:BackofficeTextKey,values:Record<string,string>={}):string{return Object.entries(values).reduce<string>((value,[name,replacement])=>value.replaceAll(`{${name}}`,replacement),backofficeText(currentBackofficeLocale(),key));}
  get filtered(): SelectOption[] { const query = this.query.trim().toLocaleLowerCase(); const options=[...this.options,...this.selectedOptions.filter(x=>!this.options.some(option=>option.value===x.value))]; return options.filter(x => (x.label + ' ' + x.value + ' ' + (x.detail ?? '')).toLocaleLowerCase().includes(query)); }
  writeValue(value: string | string[] | null): void { this.values = Array.isArray(value) ? [...value] : value ? [value] : []; }
  registerOnChange(fn: (value: string | string[]) => void): void { this.change = fn; }
  registerOnTouched(fn: () => void): void { this.touched = fn; }
  setDisabledState(value: boolean): void { this.disabled = value; if (value) this.open = false; }
  toggle(): void { this.open ? this.close() : this.openMenu(); }
  openMenu(event?: Event): void { event?.preventDefault(); if (this.disabled) return; const rect=this.host.nativeElement.getBoundingClientRect();const below=window.innerHeight-rect.bottom-12;const above=rect.top-12;const up=below<270&&above>below;const height=Math.max(150,Math.min(this.multiple?370:310,up?above:below));this.position={height,up};this.open = true; this.query = ''; this.search.emit(''); setTimeout(() => this.host.nativeElement.querySelector<HTMLInputElement>('input')?.focus()); }
  close(): void { this.open = false; this.touched(); this.host.nativeElement.querySelector<HTMLButtonElement>('.search-select-trigger')?.focus(); }
  choose(value: string): void {
    if(this.options.find(x=>x.value===value)?.disabled)return;
    this.values = this.multiple ? this.values.includes(value) ? this.values.filter(x => x !== value) : [...this.values, value] : [value];
    this.change(this.multiple ? [...this.values] : value); this.touched();
    if (!this.multiple) this.close();
  }
  clear(): void { this.values = this.multiple?this.values.filter(value=>this.options.find(x=>x.value===value)?.disabled):[]; this.change(this.multiple ? [...this.values] : ''); this.touched(); }
  focusOption(event: Event, index: number): void {
    event.preventDefault();
    const options=this.host.nativeElement.querySelectorAll<HTMLButtonElement>('.select-option');
    const direction=(event as KeyboardEvent).key==='ArrowUp'?-1:1;
    let target=index;
    while(target>=0&&target<options.length&&options[target].disabled)target+=direction;
    if(target<0||!options.length)this.host.nativeElement.querySelector<HTMLInputElement>('input')?.focus();
    else if(target<options.length)options[target].focus();
  }

  @HostListener('document:pointerdown', ['$event']) outside(event: Event): void { if (this.open && !this.host.nativeElement.contains(event.target as Node)) { this.open = false; this.touched(); } }
  @HostListener('window:resize') resize():void{this.open=false;}
  @HostListener('window:scroll') scroll():void{this.open=false;}
  @HostListener('focusout', ['$event']) blur(event: FocusEvent): void { if (!this.host.nativeElement.contains(event.relatedTarget as Node)) { this.open = false; this.touched(); } }
}
