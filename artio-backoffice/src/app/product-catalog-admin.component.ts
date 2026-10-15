import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
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
  | { type: 'newProduct' | 'rebuildIndex' | 'export' | 'search' | 'previousPage' | 'nextPage' | 'saveTranslation' | 'closeTranslation' | 'dismissImport' }
  | { type: 'import'; event: Event }
  | { type: 'toggle' | 'archive' | 'restore' | 'createVariant' | 'schedule' | 'cancelSchedule' | 'editSeo' | 'media' | 'duplicate' | 'translations'; product: CatalogProduct }
  | { type: 'editVariant' | 'toggleVariant'; variant: NonNullable<CatalogProduct['variants']>[number] };

@Component({
  selector: 'artio-product-catalog-admin',
  imports: [CommonModule, FormsModule, SearchSelectComponent],
  template: `
    <section class="catalog-page">
      <div class="page-intro"><div><p class="eyebrow">{{t('eyebrow')}}</p><h2>{{t('title')}}</h2><p>{{t('intro')}}</p></div><button type="button" class="primary" (click)="emit({type:'newProduct'})">＋ {{t('newProduct')}}</button></div>
      <div class="toolbar catalog-toolbar">
        <input #productCsvFile type="file" accept=".csv,text/csv" hidden (change)="emit({type:'import',event:$event})">
        <button type="button" class="link-button" (click)="productCsvFile.click()">{{t('importCsv')}}</button>
        <button type="button" class="link-button" (click)="emit({type:'export'})">{{t('exportCsv')}}</button>
        <label class="search">⌕ <input [attr.aria-label]="t('searchLabel')" [ngModel]="query" (ngModelChange)="filterChange.emit({field:'query',value:$event})" (keyup.enter)="emit({type:'search'})" [placeholder]="t('searchLabel')"></label>
        <button type="button" class="link-button" (click)="emit({type:'search'})">{{t('search')}}</button>
        <select [attr.aria-label]="t('status')" [ngModel]="catalogState" (ngModelChange)="filterChange.emit({field:'catalogState',value:$event})"><option value="ALL">{{t('allProducts')}}</option><option value="ACTIVE">{{t('active')}}</option><option value="INACTIVE">{{t('inactive')}}</option><option value="ARCHIVED">{{t('archived')}}</option></select>
        <artio-search-select [ngModel]="catalogBrandId" (ngModelChange)="filterChange.emit({field:'catalogBrandId',value:$event})" [options]="catalogBrandOptions" [label]="t('brandFilter')" [placeholder]="t('allBrands')"></artio-search-select>
        <artio-search-select [ngModel]="catalogCategoryId" (ngModelChange)="filterChange.emit({field:'catalogCategoryId',value:$event})" [options]="catalogCategoryOptions" [label]="t('categoryFilter')" [placeholder]="t('allCategories')"></artio-search-select>
        <select [attr.aria-label]="t('sortBy')" [ngModel]="catalogSort" (ngModelChange)="filterChange.emit({field:'catalogSort',value:$event})"><option value="updatedAt">{{t('updated')}}</option><option value="createdAt">{{t('created')}}</option><option value="name">{{t('sortName')}}</option><option value="sku">{{t('sortSku')}}</option></select>
        <select [attr.aria-label]="t('sortDirection')" [ngModel]="catalogDirection" (ngModelChange)="filterChange.emit({field:'catalogDirection',value:$event})"><option value="desc">{{t('descending')}}</option><option value="asc">{{t('ascending')}}</option></select>
        <button type="button" class="link-button" (click)="emit({type:'rebuildIndex'})">{{t('rebuildIndex')}}</button>
      </div>
      @if (searchIndexStatus) { <p class="muted" role="status">{{searchIndexStatus}}</p> }
      @if (productImportReport) {
        <div class="panel"><div class="panel-heading"><div><p class="eyebrow">{{t('importReport')}}</p><h2>{{format('importSummary',{created:productImportReport.created,updated:productImportReport.updated,failed:productImportReport.failed})}}</h2></div><button type="button" class="link-button" (click)="emit({type:'dismissImport'})">{{t('dismiss')}}</button></div>
          <p class="muted">{{format('rowsProcessed',{rows:productImportReport.rows})}}</p>@for (item of productImportReport.errors; track item) {
            <div class="row"><span class="row-main"><strong>{{format('row',{row:item.row})}} · {{item.sku||t('noSku')}}</strong><small>{{item.reason}}</small></span></div>
          }
        </div>
      }
      <div class="panel table-panel"><table><thead><tr><th scope="col">{{t('productHeader')}}</th><th scope="col">{{t('skuVariantHeader')}}</th><th scope="col">{{t('statusHeader')}}</th><th scope="col">{{t('actionHeader')}}</th></tr></thead><tbody>@for (product of products; track product.id) {
        <tr><td class="product-cell"><span class="product-monogram" aria-hidden="true">{{product.name.charAt(0).toUpperCase()}}</span><strong>{{product.name}}</strong><small>{{product.description||t('noDescription')}}</small><small>{{t('seo')}}: {{product.seoSlug||t('notSet')}}</small></td>
          <td>{{product.sku}}@for (variant of product.variants; track variant.id) {
            <small>{{variant.name}} · {{variant.sku}} · {{variant.active?t('activeVariant'):t('inactiveVariant')}} · {{variant.attributes|json}} @if (!product.archived) {
              <button type="button" class="link-button" (click)="emit({type:'editVariant',variant})">{{t('editOptions')}}</button>
            }@if (!product.archived) {
              <button type="button" class="link-button" (click)="emit({type:'toggleVariant',variant})">{{variant.active?t('deactivate'):t('activate')}}</button>
            }</small>
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
      }</tbody></table>
      @if (!products.length) { <p class="empty" role="status">{{t('empty')}}</p> }
      <div class="row"><span class="row-main">{{format('productCount',{count:catalogTotal,page:catalogPage+1,pages:catalogPages})}}</span><button type="button" class="link-button" [disabled]="catalogPage===0" (click)="emit({type:'previousPage'})">{{t('previous')}}</button><button type="button" class="link-button" [disabled]="catalogPage+1>=catalogPages" (click)="emit({type:'nextPage'})">{{t('next')}}</button></div></div>
      @if (translationProduct) {
        <div class="panel"><div class="panel-heading"><div><p class="eyebrow">{{t('translationEyebrow')}}</p><h2>{{translationProduct.name}}</h2></div><button type="button" class="link-button" (click)="emit({type:'closeTranslation'})">{{t('close')}}</button></div>
          <form class="create-form" (ngSubmit)="emit({type:'saveTranslation'})"><select [ngModel]="translationLocale" name="translationLocale" (ngModelChange)="translationLocaleChange.emit($event)"><option value="de">{{t('german')}} · de</option><option value="it">{{t('italian')}} · it</option><option value="fr">{{t('french')}} · fr</option><option value="en">{{t('english')}} · en</option></select>
            <input [(ngModel)]="translationDraft.name" name="localizedName" [placeholder]="t('translatedName')" required><textarea [(ngModel)]="translationDraft.description" name="localizedDescription" [placeholder]="t('translatedDescription')" rows="3"></textarea>
            <label>{{t('canonicalSlug')}}<input [(ngModel)]="translationDraft.seoSlug" name="localizedSeoSlug" [placeholder]="t('slugExample')"></label><input [(ngModel)]="translationDraft.seoTitle" name="localizedSeoTitle" maxlength="180" [placeholder]="t('seoTitle')"><textarea [(ngModel)]="translationDraft.seoDescription" name="localizedSeoDescription" maxlength="320" [placeholder]="t('seoDescription')" rows="2"></textarea><button class="primary">{{t('saveTranslation')}}</button>
          </form>
        </div>
      }
    </section>
  `
})
export class ProductCatalogAdminComponent {
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
  @Input() productImportReport: any = null;
  @Input() query = '';
  @Input() translationProduct: CatalogProduct | null = null;
  @Input() translationLocale = 'de';
  @Input() translationDraft: { name: string; description: string; seoSlug: string; seoTitle: string; seoDescription: string } = { name: '', description: '', seoSlug: '', seoTitle: '', seoDescription: '' };

  @Output() command = new EventEmitter<CatalogAction>();
  @Output() filterChange = new EventEmitter<{ field: 'query' | 'catalogState' | 'catalogBrandId' | 'catalogCategoryId' | 'catalogSort' | 'catalogDirection'; value: string }>();
  @Output() translationLocaleChange = new EventEmitter<string>();

  t(key: ProductCatalogTextKey): string { return productCatalogText(currentBackofficeLocale(), key); }
  format(key: ProductCatalogTextKey, values: Record<string, string | number | null>): string {
    return Object.entries(values).reduce((result, [name, value]) => result.replaceAll(`{${name}}`, String(value)), this.t(key));
  }
  emit(command: CatalogAction): void { this.command.emit(command); }
}
