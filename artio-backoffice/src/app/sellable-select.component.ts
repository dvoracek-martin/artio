import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, DestroyRef, Input, forwardRef, inject } from '@angular/core';
import { ControlValueAccessor, FormsModule, NG_VALUE_ACCESSOR } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, catchError, debounceTime, distinctUntilChanged, forkJoin, map, of, startWith, switchMap, tap } from 'rxjs';
import { SearchSelectComponent, SelectOption } from './search-select.component';

type Variant = { id: string; productId: string; name: string; sku: string; active: boolean };
@Component({
  selector: 'artio-sellable-select', standalone: true, imports: [CommonModule, FormsModule, SearchSelectComponent],
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SellableSelectComponent), multi: true }],
  template: `<artio-search-select [ngModel]="value" (ngModelChange)="select($event)" [ngModelOptions]="{standalone:true}" [label]="label" [options]="options" [multiple]="multiple" [disabled]="disabled" [loading]="loading" placeholder="Search products or SKUs" (search)="query.next($event)"></artio-search-select><small class="picker-help" *ngIf="!error">Search by product name or product SKU to select a variant.</small><small class="picker-error" *ngIf="error" role="alert">{{error}} <button type="button" class="link-button" (click)="reload()">Retry</button></small>`
})
export class SellableSelectComponent implements ControlValueAccessor {
  @Input() label = 'Product variant';
  @Input() multiple = false;
  private http = inject(HttpClient);
  private destroyRef = inject(DestroyRef);
  query = new Subject<string>();
  loading = false;
  disabled = false;
  error = '';
  value: string | string[] = '';
  private found: SelectOption[] = [];
  private retained = new Map<string, SelectOption>();
  private change: (value: string | string[]) => void = () => {};
  private touch: () => void = () => {};
  get options(): SelectOption[] { return [...new Map([...this.retained.values(), ...this.found].map(option => [option.value,option])).values()]; }
  constructor() {
    this.query.pipe(startWith(''),debounceTime(200),tap(()=>{this.loading=true;this.error='';}),switchMap(q=>this.http.get<{items:{id:string;name:string;sku:string}[]}>('/api/v1/admin/catalog/products',{params:{q,page:0,size:10,state:'ALL',sort:'name',direction:'asc'}}).pipe(
      switchMap(page=>page.items.length?forkJoin(page.items.map(product=>this.http.get<Variant[]>(`/api/v1/products/${product.id}/variants`).pipe(map(variants=>variants.filter(x=>x.active).map(variant=>({value:variant.id,label:product.name+' · '+variant.name,detail:product.sku+' / '+variant.sku})))))):of([])),
      map(groups=>groups.flat()),catchError(()=>{this.error='Could not load product variants.';return of([]);})
    )),takeUntilDestroyed(this.destroyRef)).subscribe(options=>{this.found=options;this.loading=false;});
  }
  reload():void{this.query.next(' ');}
  writeValue(value:string|string[]|null):void {
    this.value=value??(this.multiple?[]:'');
    for(const id of Array.isArray(this.value)?this.value:this.value?[this.value]:[]) if(!this.retained.has(id)) this.http.get<Variant>(`/api/v1/variants/${id}`).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({next:variant=>this.retained.set(id,{value:id,label:variant.name,detail:variant.sku}),error:()=>undefined});
  }
  registerOnChange(fn:(value:string|string[])=>void):void{this.change=fn;}
  registerOnTouched(fn:()=>void):void{this.touch=fn;}
  setDisabledState(disabled:boolean):void{this.disabled=disabled;}
  select(value:string|string[]):void{for(const option of this.found)if((Array.isArray(value)?value:[value]).includes(option.value))this.retained.set(option.value,option);this.value=value;this.change(value);this.touch();}
}
