import { CommonModule } from '@angular/common';
import { Component, ElementRef, EventEmitter, HostListener, Input, OnChanges, Output, SimpleChanges, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { currentBackofficeLocale } from './backoffice-locale';
import { productCatalogText, ProductCatalogTextKey } from './product-catalog-locale';
import { SearchSelectComponent, SelectOption } from './search-select.component';

export type CatalogProduct = {
  id: string;
  name: string;
  description: string;
  sku: string;
  active: boolean;
  archived: boolean;
  seoSlug: string | null;
  seoTitle: string | null;
  seoDescription: string | null;
  scheduledPublishAt: string | null;
  variants?: Array<{ id: string; name: string; sku: string; active: boolean; attributes?: Record<string, string> }>;
};

export type CatalogAction =
  | { type: 'newProduct' | 'rebuildIndex' | 'previousPage' | 'nextPage' | 'saveTranslation' | 'closeTranslation' }
  | { type: 'toggle' | 'archive' | 'restore' | 'createVariant' | 'schedule' | 'cancelSchedule' | 'editSeo' | 'media' | 'duplicate' | 'translations'; product: CatalogProduct }
  | { type: 'editVariant' | 'toggleVariant'; variant: NonNullable<CatalogProduct['variants']>[number] };

@Component({
  selector: 'artio-product-catalog-admin',
  imports: [CommonModule, FormsModule, SearchSelectComponent],
  template: `
    <section class="catalog-page">
      <div class="page-intro"><div><p class="eyebrow">{{t('eyebrow')}}</p><h2>{{t('title')}}</h2><p>{{t('intro')}}</p></div><button type="button" class="primary" (click)="emit({type:'newProduct'})">＋ {{t('newProduct')}}</button></div>
      <div class="toolbar catalog-toolbar">
        <label class="search catalog-search"><span class="catalog-search-icon" aria-hidden="true"><svg viewBox="0 0 24 24" focusable="false"><circle cx="10.8" cy="10.8" r="6.3"></circle><path d="m16 16 4.2 4.2"></path></svg></span><input [attr.aria-label]="t('searchLabel')" [ngModel]="query" (ngModelChange)="filterChange.emit({field:'query',value:$event})" [placeholder]="t('searchLabel')"></label>
        <select [attr.aria-label]="t('status')" [ngModel]="catalogState" (ngModelChange)="filterChange.emit({field:'catalogState',value:$event})"><option value="ALL">{{t('allProducts')}}</option><option value="ACTIVE">{{t('active')}}</option><option value="INACTIVE">{{t('inactive')}}</option><option value="ARCHIVED">{{t('archived')}}</option></select>
        <artio-search-select [ngModel]="catalogBrandId" (ngModelChange)="filterChange.emit({field:'catalogBrandId',value:$event})" [options]="catalogBrandOptions" [label]="t('brandFilter')" [placeholder]="t('allBrands')"></artio-search-select>
        <artio-search-select [ngModel]="catalogCategoryId" (ngModelChange)="filterChange.emit({field:'catalogCategoryId',value:$event})" [options]="catalogCategoryOptions" [label]="t('categoryFilter')" [placeholder]="t('allCategories')"></artio-search-select>
        <select [attr.aria-label]="t('sortBy')" [ngModel]="catalogSort" (ngModelChange)="filterChange.emit({field:'catalogSort',value:$event})"><option value="updatedAt">{{t('updated')}}</option><option value="createdAt">{{t('created')}}</option><option value="name">{{t('sortName')}}</option><option value="sku">{{t('sortSku')}}</option></select>
        <select [attr.aria-label]="t('sortDirection')" [ngModel]="catalogDirection" (ngModelChange)="filterChange.emit({field:'catalogDirection',value:$event})"><option value="desc">{{t('descending')}}</option><option value="asc">{{t('ascending')}}</option></select>
        <button type="button" class="link-button index-rebuild-button" (click)="emit({type:'rebuildIndex'})" [attr.aria-label]="t('rebuildIndex')" [attr.title]="t('rebuildIndex')" [attr.data-tooltip]="t('rebuildIndex')">
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M20 7v5h-5M4 17v-5h5"/><path d="M5.64 9A8 8 0 0 1 19.07 6L20 7M4 17l.93 1A8 8 0 0 0 18.36 15"/></svg>
        </button>
      </div>
      @if (searchIndexStatus) { <p class="muted" role="status">{{searchIndexStatus}}</p> }
      <div class="panel table-panel catalog-products-panel"><div class="product-table-wrap" [class.is-loading]="!!actionPendingId || loadingProducts"><table><thead><tr><th scope="col">{{t('productHeader')}}</th><th scope="col">{{t('skuVariantHeader')}}</th><th scope="col">{{t('statusHeader')}}</th><th scope="col">{{t('actionHeader')}}</th></tr></thead><tbody>@for (product of products; track product.id) {
        <tr><td class="product-cell"><span class="product-monogram" aria-hidden="true">{{product.name.charAt(0).toUpperCase()}}</span><strong>{{product.name}}</strong><small>{{product.description||t('noDescription')}}</small><small>{{t('seo')}}: {{product.seoSlug||t('notSet')}}</small></td>
          <td>{{product.sku}}@for (variant of product.variants; track variant.id) {
            <small>{{variant.name}} · {{variant.sku}} · {{variant.active?t('activeVariant'):t('inactiveVariant')}} · {{variant.attributes|json}}</small>
          }</td>
          <td><span class="pill" [class.inactive]="!product.active">{{product.archived?t('archived'):product.active?t('active'):t('inactive')}}</span>@if (product.scheduledPublishAt) { <small>{{format('publishes',{date:(product.scheduledPublishAt|date:'medium')})}}</small> }</td>
          <td><details class="product-actions"><summary>{{t('manage')}} <span>⌄</span></summary><div>
            @if (!product.archived) { <button type="button" class="link-button" (click)="emit({type:'toggle',product})">{{product.active?t('deactivate'):t('activate')}}</button> }
            @if (!product.archived) { <button type="button" class="link-button" (click)="emit({type:'archive',product})">{{t('archive')}}</button> }
            @if (product.archived) { <button type="button" class="link-button" (click)="emit({type:'restore',product})">{{t('restore')}}</button> }
            @if (!product.archived) { <button type="button" class="link-button" (click)="emit({type:'createVariant',product})">{{t('addVariant')}}</button> }
            @if (!product.active && !product.archived && !product.scheduledPublishAt) { <button type="button" class="link-button" (click)="emit({type:'schedule',product})">{{t('schedule')}}</button> }
            @if (product.scheduledPublishAt) { <button type="button" class="link-button" (click)="emit({type:'cancelSchedule',product})">{{t('cancelSchedule')}}</button> }
            <button type="button" class="link-button" (click)="emit({type:'editSeo',product})">{{t('seo')}}</button><button type="button" class="link-button" (click)="emit({type:'media',product})">{{t('media')}}</button><button type="button" class="link-button" (click)="emit({type:'duplicate',product})">{{t('duplicate')}}</button><button type="button" class="link-button" (click)="emit({type:'translations',product})">{{t('translations')}}</button>
          </div></details></td>
        </tr>
      }</tbody></table>@if(actionPendingId || loadingProducts){<div class="product-action-overlay" role="status" aria-live="polite"><span class="workspace-loading-spinner" aria-hidden="true"></span><strong>{{loadingProducts?t('loadingProducts'):t('updatingProduct')}}</strong></div>}</div>
      @if (!products.length) { <p class="empty" role="status">{{t('empty')}}</p> }
      <div class="row catalog-list-footer"><span class="row-main">{{format('productCount',{count:catalogTotal,page:catalogPage+1,pages:catalogPages})}}</span><div class="catalog-page-controls"><button type="button" class="secondary" [disabled]="catalogPage===0" (click)="emit({type:'previousPage'})">{{t('previous')}}</button><button type="button" class="secondary" [disabled]="catalogPage+1>=catalogPages" (click)="emit({type:'nextPage'})">{{t('next')}}</button></div></div></div>
      @if (translationProduct) { <dialog #translationDialog class="workspace-dialog translation-dialog" aria-labelledby="translation-dialog-title" (close)="emit({type:'closeTranslation'})"><div class="workspace-dialog-panel"><div class="panel-heading"><div><p class="eyebrow">{{t('translationEyebrow')}}</p><h2 id="translation-dialog-title">{{translationProduct.name}}</h2></div><button type="button" class="link-button" (click)="emit({type:'closeTranslation'})">{{t('close')}}</button></div>
        @if(translationLoading){<p class="muted loading-indicator" role="status" aria-live="polite">{{t('loadingTranslation')}}</p>}@if(translationError){<p class="notice error" role="alert">{{translationError}}</p>}
        <form class="translation-form" (ngSubmit)="emit({type:'saveTranslation'})"><label>{{t('language')}}<select [ngModel]="translationLocale" name="translationLocale" (ngModelChange)="translationLocaleChange.emit($event)"><option value="de">{{t('german')}} · de</option><option value="it">{{t('italian')}} · it</option><option value="fr">{{t('french')}} · fr</option><option value="en">{{t('english')}} · en</option></select></label>
          <label>{{t('translatedName')}}<input [(ngModel)]="translationDraft.name" name="localizedName" [placeholder]="t('translatedName')" required [disabled]="translationLoading"></label><label>{{t('translatedDescription')}}<textarea [(ngModel)]="translationDraft.description" name="localizedDescription" rows="4" [disabled]="translationLoading"></textarea></label>
          <label>{{t('canonicalSlug')}}<input [(ngModel)]="translationDraft.seoSlug" name="localizedSeoSlug" [placeholder]="t('slugExample')"></label><label>{{t('seoTitle')}}<input [(ngModel)]="translationDraft.seoTitle" name="localizedSeoTitle" maxlength="180" [placeholder]="t('seoTitle')"></label><label>{{t('seoDescription')}}<textarea [(ngModel)]="translationDraft.seoDescription" name="localizedSeoDescription" maxlength="320" rows="3"></textarea></label><div class="refund-dialog-actions"><button type="submit" class="primary" [disabled]="translationLoading">{{t('saveTranslation')}}</button></div>
        </form></div></dialog> }
    </section>
  `
})
export class ProductCatalogAdminComponent implements OnChanges {
  @HostListener('document:pointerdown',['$event']) closeActionsOnOutsideClick(event:PointerEvent):void{const target=event.target as Node|null;for(const menu of document.querySelectorAll<HTMLDetailsElement>('details.product-actions[open]'))if(target&&!menu.contains(target))menu.open=false;}
  @ViewChild('translationDialog') private translationDialog?:ElementRef<HTMLDialogElement>;
  @Input() products: CatalogProduct[] = [];
  @Input() catalogTotal = 0;
  @Input() catalogPage = 0;
  @Input() catalogPages = 1;
  @Input() catalogState = 'ALL';
  @Input() catalogSort = 'updatedAt';
  @Input() catalogDirection = 'desc';
  @Input() catalogBrandId = '';
  @Input() catalogCategoryId = '';
  @Input() catalogBrandOptions: SelectOption[] = [];
  @Input() catalogCategoryOptions: SelectOption[] = [];
  @Input() searchIndexStatus = '';
  @Input() query = '';
  @Input() translationProduct: CatalogProduct | null = null;
  @Input() translationLocale = 'de';
  @Input() translationDraft: { name: string; description: string; seoSlug: string; seoTitle: string; seoDescription: string } = { name: '', description: '', seoSlug: '', seoTitle: '', seoDescription: '' };
  @Input() translationLoading = false;
  @Input() translationError = '';
  @Input() actionPendingId: string | null = null;
  @Input() loadingProducts = false;

  @Output() command = new EventEmitter<CatalogAction>();
  @Output() filterChange = new EventEmitter<{ field: 'query' | 'catalogState' | 'catalogBrandId' | 'catalogCategoryId' | 'catalogSort' | 'catalogDirection'; value: string }>();
  @Output() translationLocaleChange = new EventEmitter<string>();

  ngOnChanges(changes:SimpleChanges):void{
    if(changes['translationProduct'])setTimeout(()=>{const dialog=this.translationDialog?.nativeElement;if(this.translationProduct&&dialog&&!dialog.open)dialog.showModal();else if(!this.translationProduct&&dialog?.open)dialog.close()},0);
  }
  @HostListener('document:click',['$event']) closeActionsOutside(event:MouseEvent):void{
    const target=event.target as Node;
    document.querySelectorAll<HTMLDetailsElement>('.product-actions[open]').forEach(details=>{if(!details.contains(target))details.open=false});
  }

  t(key: ProductCatalogTextKey): string { return productCatalogText(currentBackofficeLocale(), key); }
  format(key: ProductCatalogTextKey, values: Record<string, string | number | null>): string {
    return Object.entries(values).reduce((result, [name, value]) => result.replaceAll(`{${name}}`, String(value)), this.t(key));
  }
  emit(command: CatalogAction): void { this.command.emit(command); }
}
