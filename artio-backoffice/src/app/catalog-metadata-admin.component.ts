
import { HttpClient } from '@angular/common/http';
import { Component, inject, ChangeDetectionStrategy, ElementRef, ViewChild } from '@angular/core';
import { SearchSelectComponent, SelectOption } from './search-select.component';
import { FormsModule } from '@angular/forms';
import { backofficeText, currentBackofficeLocale, BackofficeTextKey } from './backoffice-locale';
import { catalogText, CatalogTextKey } from './catalog-backoffice-locale';

type Category = { id: string; name: string; parentId: string | null; active: boolean };
type Brand = { id: string; name: string; description: string; active: boolean };
type AttributeDefinition = { id: string; code: string; label: string; type: 'TEXT' | 'NUMBER' | 'BOOLEAN'; required: boolean; active: boolean };
type CatalogProduct = { id: string; name: string; sku: string; brandId: string | null; categoryIds: string[]; attributes: Record<string,string> };

@Component({
    selector: 'artio-catalog-metadata-admin',
    imports: [FormsModule, SearchSelectComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `
    <section>
      <div class="panel">
        <div class="panel-heading"><div><p class="eyebrow">{{ct('structure')}}</p><h2>{{ct('categories')}}</h2></div><button class="link-button" (click)="load()">{{ct('refresh')}}</button></div>
        <form class="create-form" (ngSubmit)="createCategory()">
          <label class="sr-only" for="new-category-name">{{ct('categoryName')}}</label>
          <input id="new-category-name" [(ngModel)]="categoryDraft.name" name="categoryName" maxlength="120" [placeholder]="ct('categoryName')" required>
          <artio-search-select [(ngModel)]="categoryDraft.parentId" name="categoryParent" [label]="ct('parentCategory')" [options]="parentOptions" [placeholder]="ct('topLevel')"></artio-search-select>
          <button class="primary">{{ct('addCategory')}}</button>
        </form>
        @for (category of categories; track category) {
          <div class="row"><span class="row-main"><strong>{{category.name}}</strong><small>{{category.parentId?ct('childOf').replace('{name}',categoryName(category.parentId)):ct('topLevel')}} · {{category.active?ct('active'):ct('inactive')}}</small></span><button class="link-button" (click)="editCategory(category)">{{ct('edit')}}</button><button class="link-button" (click)="toggleCategory(category)">{{category.active?ct('deactivate'):ct('activate')}}</button></div>
        }
        @if (!categories.length) {
          <p class="empty">{{ct('noCategories')}}</p>
        }
      </div>

      @if (products.length) {
        <div class="panel">
          <div class="panel-heading"><div><p class="eyebrow">{{ct('classification')}}</p><h2>{{ct('assignData')}}</h2></div></div>
          <div class="setup-field"><span>{{ct('product')}}</span><artio-search-select [(ngModel)]="selectedProductId" name="metadataProduct" (ngModelChange)="selectProduct($event)" [label]="ct('productToClassify')" [options]="productOptions" [placeholder]="ct('chooseProduct')"></artio-search-select></div>
          @if (selectedProduct) {
            <form (ngSubmit)="saveProductMetadata()">
              <div class="setup-field"><span>{{ct('brand')}}</span><artio-search-select [(ngModel)]="productDraft.brandId" name="productBrand" [label]="ct('productBrand')" [options]="brandOptions" [placeholder]="ct('noBrand')"></artio-search-select></div>
              <h3>{{ct('categoriesLabel')}}</h3><artio-search-select [(ngModel)]="productDraft.categoryIds" name="productCategories" [label]="ct('categoriesLabel')" [options]="categoryOptions" [multiple]="true" [placeholder]="ct('chooseCategories')"></artio-search-select>
              <h3>{{ct('attributes')}}</h3>@for (attribute of productAttributeDefinitions; track attribute) {
              <div><label>{{attribute.label}} · {{attribute.code}}<small>{{attribute.required?ct('required'):ct('optional')}}{{attribute.active?'':' · '+ct('inactiveRetained')}}</small>@if (attribute.type==='BOOLEAN') {
              <select [ngModel]="productDraft.attributes[attribute.code]||''" (ngModelChange)="setAttribute(attribute.code,$event)" [name]="'attribute-'+attribute.code" [disabled]="!attribute.active"><option value="">{{ct('unset')}}</option><option value="true">{{ct('yes')}}</option><option value="false">{{ct('no')}}</option></select>
              }@if (attribute.type!=='BOOLEAN') {
              <input [type]="attribute.type==='NUMBER'?'number':'text'" [ngModel]="productDraft.attributes[attribute.code]||''" (ngModelChange)="setAttribute(attribute.code,$event)" [name]="'attribute-'+attribute.code" [required]="attribute.required&&attribute.active" [disabled]="!attribute.active">
            }</label></div>
          }
          <button class="primary">{{ct('saveClassification')}}</button>
        </form>
      }
      @if (selectedProduct) {
        <p class="muted">{{ct('inactiveDefinitionsHelp')}}</p>
      }
    </div>
    }

    <div class="panel">
      <div class="panel-heading"><div><p class="eyebrow">{{ct('manufacturers')}}</p><h2>{{ct('brands')}}</h2></div></div>
      <form class="create-form" (ngSubmit)="createBrand()"><label class="sr-only" for="new-brand-name">{{ct('brandName')}}</label><input id="new-brand-name" [(ngModel)]="brandDraft.name" name="brandName" maxlength="160" [placeholder]="ct('brandName')" required><label class="sr-only" for="new-brand-description">{{ct('description')}}</label><input id="new-brand-description" [(ngModel)]="brandDraft.description" name="brandDescription" maxlength="1000" [placeholder]="ct('description')"><button class="primary">{{ct('addBrand')}}</button></form>
      @for (brand of brands; track brand) {
        <div class="row"><span class="row-main"><strong>{{brand.name}}</strong><small>{{brand.description}} · {{brand.active?ct('active'):ct('inactive')}}</small></span><button class="link-button" (click)="editBrand(brand)">{{ct('edit')}}</button><button class="link-button" (click)="toggleBrand(brand)">{{brand.active?ct('deactivate'):ct('activate')}}</button></div>
      }
      @if (!brands.length) {
        <p class="empty">{{ct('noBrands')}}</p>
      }
    </div>

    <div class="panel">
      <div class="panel-heading"><div><p class="eyebrow">{{ct('productData')}}</p><h2>{{ct('attributeDefinitions')}}</h2></div></div>
      <form class="create-form" (ngSubmit)="createAttribute()"><label class="sr-only" for="new-attribute-code">{{ct('code')}}</label><input id="new-attribute-code" [(ngModel)]="attributeDraft.code" name="attributeCode" pattern="[A-Z][A-Z0-9_]{1,31}" maxlength="32" [placeholder]="ct('code')" required><label class="sr-only" for="new-attribute-label">{{ct('label')}}</label><input id="new-attribute-label" [(ngModel)]="attributeDraft.label" name="attributeLabel" maxlength="120" [placeholder]="ct('label')" required><label class="sr-only" for="new-attribute-type">{{ct('attributes')}}</label><select id="new-attribute-type" [(ngModel)]="attributeDraft.type" name="attributeType"><option value="TEXT">{{ct('text')}}</option><option value="NUMBER">{{ct('number')}}</option><option value="BOOLEAN">{{ct('boolean')}}</option></select><label><input type="checkbox" [(ngModel)]="attributeDraft.required" name="attributeRequired"> {{ct('required')}}</label><button class="primary">{{ct('addAttribute')}}</button></form>
      @for (attribute of attributes; track attribute) {
        <div class="row"><span class="row-main"><strong>{{attribute.label}} · {{attribute.code}}</strong><small>{{attribute.type}} · {{attribute.required?ct('required'):ct('optional')}} · {{attribute.active?ct('active'):ct('inactive')}}</small></span><button class="link-button" (click)="editAttribute(attribute)">{{ct('rename')}}</button><button class="link-button" (click)="toggleAttribute(attribute)">{{attribute.active?ct('deactivate'):ct('activate')}}</button></div>
      }
      @if (!attributes.length) {
        <p class="empty">{{ct('noAttributes')}}</p>
      }
    </div>
    @if (error) {
      <p class="error" role="alert">{{error}}</p>
    }
    @if (catalogEditor) {
      <dialog #catalogEditorDialog class="refund-dialog" aria-labelledby="catalog-editor-title" (cancel)="cancelCatalogEditor($event)" (close)="catalogEditorClosed()">
        <form class="refund-dialog-content" (ngSubmit)="saveCatalogEdit()">
          <p class="eyebrow">{{t('catalogEditEyebrow')}}</p>
          <h2 id="catalog-editor-title">{{catalogEditor.kind==='category'?t('categoryEditTitle'):catalogEditor.kind==='brand'?t('brandEditTitle'):t('attributeEditTitle')}}</h2>
          @if (catalogEditor.kind==='category') {
            <label for="catalog-edit-name">{{t('catalogName')}}</label>
            <input id="catalog-edit-name" name="catalogEditName" [(ngModel)]="catalogEditor.name" maxlength="120" required [disabled]="catalogEditorBusy" autofocus>
            <artio-search-select [(ngModel)]="catalogEditor.parentId" name="catalogEditParent" [label]="t('catalogParent')" [options]="parentOptions" [placeholder]="t('catalogTopLevel')"></artio-search-select>
          } @else if (catalogEditor.kind==='brand') {
            <label for="catalog-edit-name">{{t('catalogName')}}</label>
            <input id="catalog-edit-name" name="catalogEditName" [(ngModel)]="catalogEditor.name" maxlength="160" required [disabled]="catalogEditorBusy" autofocus>
            <label for="catalog-edit-description">{{t('catalogDescription')}}</label>
            <textarea id="catalog-edit-description" name="catalogEditDescription" rows="3" maxlength="1000" [(ngModel)]="catalogEditor.description" [disabled]="catalogEditorBusy"></textarea>
          } @else {
            <label for="catalog-edit-label">{{t('catalogAttributeLabel')}}</label>
            <input id="catalog-edit-label" name="catalogEditLabel" [(ngModel)]="catalogEditor.label" maxlength="120" required [disabled]="catalogEditorBusy" autofocus>
          }
          @if (catalogEditorError) { <p class="error" role="alert">{{catalogEditorError}}</p> }
          @if (catalogEditorBusy) { <p class="muted" role="status">{{t('catalogEditBusy')}}</p> }
          <div class="refund-dialog-actions">
            <button type="button" class="secondary" [disabled]="catalogEditorBusy" (click)="closeCatalogEditor()">{{t('refundCancel')}}</button>
            <button type="submit" class="primary" [disabled]="catalogEditorBusy">{{t('catalogEditSave')}}</button>
          </div>
        </form>
      </dialog>
    }
    </section>
    `
})
export class CatalogMetadataAdminComponent {
  @ViewChild('catalogEditorDialog') private catalogEditorDialogRef?:ElementRef<HTMLDialogElement>;
  t(key:BackofficeTextKey):string{return backofficeText(currentBackofficeLocale(),key)}
  private readonly http = inject(HttpClient);
  categories: Category[] = [];
  brands: Brand[] = [];
  attributes: AttributeDefinition[] = [];
  products: CatalogProduct[] = [];
  selectedProductId = '';
  selectedProduct: CatalogProduct | null = null;
  productDraft = { brandId: '', categoryIds: [] as string[], attributes: {} as Record<string,string> };
  error = '';
  catalogEditor:any=null;
  catalogEditorBusy=false;
  catalogEditorError='';
  categoryDraft = { name: '', parentId: '' };
  brandDraft = { name: '', description: '' };
  attributeDraft = { code: '', label: '', type: 'TEXT' as AttributeDefinition['type'], required: false };

  get parentOptions():SelectOption[]{return [{value:'',label:this.ct('topLevel')},...this.categories.map(x=>({value:x.id,label:x.name}))];}
  get productOptions():SelectOption[]{return this.products.map(x=>({value:x.id,label:x.name,detail:x.sku}));}
  get brandOptions():SelectOption[]{return [{value:'',label:this.ct('noBrand')},...this.brands.map(x=>({value:x.id,label:x.name,disabled:!x.active,detail:x.active?'':this.ct('inactive')}))];}
  get categoryOptions():SelectOption[]{return this.categories.map(x=>({value:x.id,label:x.name,disabled:!x.active,detail:x.parentId?this.ct('childOf').replace('{name}',this.categoryName(x.parentId)):x.active?this.ct('topLevel'):this.ct('inactive')}));}
  constructor() { this.load(); this.loadProducts(); }

  get productAttributeDefinitions(): AttributeDefinition[] {
    const retained=new Set(Object.keys(this.selectedProduct?.attributes??{}));
    return this.attributes.filter(attribute=>attribute.active||retained.has(attribute.code));
  }

  load(): void {
    this.http.get<Category[]>('/api/v1/categories').subscribe({ next: rows => this.categories = rows, error: e => this.fail(e, this.ct('loadCategoriesFailure')) });
    this.http.get<Brand[]>('/api/v1/brands').subscribe({ next: rows => this.brands = rows, error: e => this.fail(e, this.ct('loadBrandsFailure')) });
    this.http.get<AttributeDefinition[]>('/api/v1/attribute-definitions').subscribe({ next: rows => this.attributes = rows, error: e => this.fail(e, this.ct('loadAttributesFailure')) });
  }

  loadProducts(): void { this.http.get<CatalogProduct[]>('/api/v1/products').subscribe({next:rows=>this.products=rows,error:e=>this.fail(e,this.ct('loadProductsFailure'))}); }
  selectProduct(id:string):void{this.selectedProduct=this.products.find(row=>row.id===id)??null;if(!this.selectedProduct)return;this.productDraft={brandId:this.selectedProduct.brandId??'',categoryIds:[...this.selectedProduct.categoryIds],attributes:{...this.selectedProduct.attributes}};}
  toggleProductCategory(id:string,event:Event):void{const checked=(event.target as HTMLInputElement).checked;this.productDraft.categoryIds=checked?[...this.productDraft.categoryIds,id]:this.productDraft.categoryIds.filter(value=>value!==id);}
  setAttribute(code:string,value:string):void{this.productDraft.attributes={...this.productDraft.attributes,[code]:value};}
  saveProductMetadata():void{if(!this.selectedProduct)return;const attributes=Object.fromEntries(Object.entries(this.productDraft.attributes).map(([key,value])=>[key,value===''?null:value]));this.http.put(`/api/v1/products/${this.selectedProduct.id}/catalog-metadata`,{brandId:this.productDraft.brandId||null,categoryIds:this.productDraft.categoryIds,attributes}).subscribe({next:()=>{this.error='';this.loadProducts();this.selectedProduct=null;this.selectedProductId='';},error:e=>this.fail(e,this.ct('saveClassificationFailure'))});}

  categoryName(id: string): string { return this.categories.find(row => row.id === id)?.name ?? this.ct('unknownParent'); }
  createCategory(): void {
    this.http.post<Category>('/api/v1/categories', { name: this.categoryDraft.name.trim(), parentId: this.categoryDraft.parentId || null, active: true }).subscribe({
      next: () => { this.categoryDraft = { name: '', parentId: '' }; this.load(); this.error = ''; }, error: e => this.fail(e, this.ct('createCategoryFailure'))
    });
  }
  editCategory(row: Category): void {
    this.openCatalogEditor('category',{...row,parentId:row.parentId??''});
  }
  toggleCategory(row: Category): void {
    this.http.put(`/api/v1/categories/${row.id}`, { name: row.name, parentId: row.parentId, active: !row.active }).subscribe({ next: () => this.load(), error: e => this.fail(e, this.ct('changeCategoryFailure')) });
  }

  createBrand(): void {
    this.http.post('/api/v1/brands', { ...this.brandDraft, name: this.brandDraft.name.trim(), active: true }).subscribe({ next: () => { this.brandDraft = { name: '', description: '' }; this.load(); this.error = ''; }, error: e => this.fail(e, this.ct('createBrandFailure')) });
  }
  editBrand(row: Brand): void {
    this.openCatalogEditor('brand',{...row});
  }
  toggleBrand(row: Brand): void {
    this.http.put(`/api/v1/brands/${row.id}`, { name: row.name, description: row.description, active: !row.active }).subscribe({ next: () => this.load(), error: e => this.fail(e, this.ct('changeBrandFailure')) });
  }

  createAttribute(): void {
    this.http.post('/api/v1/attribute-definitions', { ...this.attributeDraft, code: this.attributeDraft.code.trim().toUpperCase(), label: this.attributeDraft.label.trim() }).subscribe({
      next: () => { this.attributeDraft = { code: '', label: '', type: 'TEXT', required: false }; this.load(); this.error = ''; }, error: e => this.fail(e, this.ct('createAttributeFailure'))
    });
  }
  editAttribute(row: AttributeDefinition): void {
    this.openCatalogEditor('attribute',{...row});
  }

  private openCatalogEditor(kind:'category'|'brand'|'attribute',row:any):void{this.catalogEditor={kind,...row};this.catalogEditorBusy=false;this.catalogEditorError='';setTimeout(()=>{if(this.catalogEditor&&!this.catalogEditorDialogRef?.nativeElement.open)this.catalogEditorDialogRef?.nativeElement.showModal()},0)}
  cancelCatalogEditor(event:Event):void{if(this.catalogEditorBusy)event.preventDefault()}
  catalogEditorClosed():void{if(this.catalogEditorBusy)return;this.catalogEditor=null;this.catalogEditorError=''}
  closeCatalogEditor():void{if(!this.catalogEditorBusy)this.catalogEditorDialogRef?.nativeElement.close()}
  saveCatalogEdit():void{const edit=this.catalogEditor;if(!edit||this.catalogEditorBusy)return;const label=edit.kind==='attribute'?edit.label.trim():edit.name.trim();if(!label){this.catalogEditorError=this.t('catalogEditRequired');return}let request;if(edit.kind==='category')request=this.http.put(`/api/v1/categories/${edit.id}`,{name:label,parentId:edit.parentId||null,active:edit.active});else if(edit.kind==='brand')request=this.http.put(`/api/v1/brands/${edit.id}`,{name:label,description:edit.description.trim(),active:edit.active});else request=this.http.put(`/api/v1/attribute-definitions/${edit.id}`,{label,active:edit.active});this.catalogEditorBusy=true;this.catalogEditorError='';request.subscribe({next:()=>{this.catalogEditorBusy=false;this.catalogEditorDialogRef?.nativeElement.close();this.load();this.error=''},error:e=>{this.catalogEditorBusy=false;this.catalogEditorError=e.error?.detail??this.t('catalogEditFailure')}})}
  toggleAttribute(row: AttributeDefinition): void {
    this.http.put(`/api/v1/attribute-definitions/${row.id}`, { label: row.label, active: !row.active }).subscribe({ next: () => this.load(), error: e => this.fail(e, this.ct('changeAttributeFailure')) });
  }

  private fail(error: any, fallback: string): void { this.error = error.error?.detail ?? fallback; }
  ct(key: CatalogTextKey): string { return catalogText(currentBackofficeLocale(), key); }
}
