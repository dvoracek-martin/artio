import { CommonModule } from '@angular/common';
import { Component, ElementRef, EventEmitter, HostListener, Input, Output, forwardRef, inject } from '@angular/core';
import { ControlValueAccessor, FormsModule, NG_VALUE_ACCESSOR } from '@angular/forms';

export type SelectOption = { value: string; label: string; detail?: string; disabled?: boolean };
let nextSelectId = 0;
@Component({
  selector: 'artio-search-select', standalone: true, imports: [CommonModule, FormsModule],
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SearchSelectComponent), multi: true }],
  template: `
    <div class="search-select" [class.is-open]="open" [class.is-disabled]="disabled">
      <button type="button" class="search-select-trigger" [id]="id" [disabled]="disabled" [attr.aria-label]="label" [attr.aria-describedby]="id+'-selection'" [attr.aria-expanded]="open" [attr.aria-controls]="id+'-options'" aria-haspopup="listbox" (click)="toggle()" (keydown.arrowdown)="openMenu($event)">
        <span class="select-values"><ng-container *ngIf="multiple; else single"><span class="select-chip" *ngFor="let option of selectedOptions; trackBy: optionValue">{{option.label}}</span><span *ngIf="!values.length" class="select-placeholder">{{placeholder}}</span></ng-container><ng-template #single><span [class.select-placeholder]="!values.length">{{selectedLabel}}</span></ng-template></span><span aria-hidden="true">⌄</span>
      </button>
      <span class="sr-only" [id]="id+'-selection'">{{selectionSummary}}</span>
      <div *ngIf="open" class="search-select-popover" [style.left.px]="position.left" [style.top.px]="position.top" [style.width.px]="position.width" [style.max-height.px]="position.height">
        <div class="select-search"><span aria-hidden="true">⌕</span><input type="search" [(ngModel)]="query" (ngModelChange)="search.emit($event)" [ngModelOptions]="{standalone:true}" [attr.aria-label]="'Search '+label" [placeholder]="'Search '+label.toLowerCase()+'…'" (keydown.arrowdown)="focusOption($event,0)" (keydown.escape)="close()" autocomplete="off"></div>
        <p *ngIf="loading" class="select-no-results" role="status">Loading options…</p>
        <div class="select-selection-bar" *ngIf="multiple"><span>{{values.length}} selected</span><button type="button" (click)="clear()" [disabled]="!values.length">Clear</button></div>
        <div class="select-options" [style.max-height.px]="position.height-(multiple?130:65)" [id]="id+'-options'" role="listbox" [attr.aria-label]="label" [attr.aria-multiselectable]="multiple">
          <button type="button" class="select-option" *ngFor="let option of filtered; let index=index; trackBy: optionValue" role="option" [disabled]="option.disabled" [attr.aria-selected]="values.includes(option.value)" [class.is-selected]="values.includes(option.value)" (click)="choose(option.value)" (keydown.arrowdown)="focusOption($event,index+1)" (keydown.arrowup)="focusOption($event,index-1)" (keydown.escape)="close()">
            <span class="select-check" [class.multiple]="multiple" aria-hidden="true">{{values.includes(option.value)?'✓':''}}</span><span><strong>{{option.label}}</strong><small *ngIf="option.detail">{{option.detail}}</small></span>
          </button><p *ngIf="!filtered.length" class="select-no-results">No matching options.</p>
        </div>
        <button *ngIf="multiple" type="button" class="select-done" (click)="close()">Done · {{values.length}} selected</button>
      </div>
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
  position={left:0,top:0,width:260,height:340};
  values: string[] = [];
  private host = inject<ElementRef<HTMLElement>>(ElementRef);
  private change: (value: string | string[]) => void = () => {};
  private touched: () => void = () => {};
  get selectedOptions(): SelectOption[] { return this.values.map(value => this.options.find(x => x.value === value) ?? { value, label: value }); }
  optionValue(index:number,option:SelectOption):string{return option.value;}
  get selectedLabel():string{return this.selectedOptions.length ? this.selectedOptions[0].label : this.placeholder;}
  get selectionSummary():string{return this.selectedOptions.length?this.selectedOptions.map(option=>option.label).join(', '):'Nothing selected';}
  get filtered(): SelectOption[] { const query = this.query.trim().toLocaleLowerCase(); const options=[...this.options,...this.selectedOptions.filter(x=>!this.options.some(option=>option.value===x.value))]; return options.filter(x => (x.label + ' ' + x.value + ' ' + (x.detail ?? '')).toLocaleLowerCase().includes(query)); }
  writeValue(value: string | string[] | null): void { this.values = Array.isArray(value) ? [...value] : value ? [value] : []; }
  registerOnChange(fn: (value: string | string[]) => void): void { this.change = fn; }
  registerOnTouched(fn: () => void): void { this.touched = fn; }
  setDisabledState(value: boolean): void { this.disabled = value; if (value) this.open = false; }
  toggle(): void { this.open ? this.close() : this.openMenu(); }
  openMenu(event?: Event): void { event?.preventDefault(); if (this.disabled) return; const rect=this.host.nativeElement.getBoundingClientRect();const width=Math.min(Math.max(rect.width,260),window.innerWidth-24);const below=window.innerHeight-rect.bottom-12;const above=rect.top-12;const up=below<270&&above>below;const height=Math.min(this.multiple?370:310,up?above:below);this.position={left:Math.max(12,Math.min(rect.left,window.innerWidth-width-12)),top:up?rect.top-height-6:rect.bottom+6,width,height:Math.max(150,height)};this.open = true; this.query = ''; this.search.emit(''); setTimeout(() => this.host.nativeElement.querySelector<HTMLInputElement>('input')?.focus()); }
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
