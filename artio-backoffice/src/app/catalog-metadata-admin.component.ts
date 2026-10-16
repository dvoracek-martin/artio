
import { HttpClient } from '@angular/common/http';
import { Component, inject, ChangeDetectionStrategy, ElementRef, ViewChild, Input, OnInit } from '@angular/core';
import { SearchSelectComponent, SelectOption } from './search-select.component';
import { FormsModule } from '@angular/forms';
import { backofficeText, currentBackofficeLocale, BackofficeTextKey } from './backoffice-locale';
import { catalogText, CatalogTextKey } from './catalog-backoffice-locale';
import { finalize } from 'rxjs';

type Category = { id: string; name: string; parentId: string | null; active: boolean };
type Brand = { id: string; name: string; description: string; active: boolean };
type AttributeDefinition = { id: string; code: string; label: string; type: 'TEXT' | 'NUMBER' | 'BOOLEAN'; required: boolean; active: boolean };
type CatalogProduct = { id: string; name: string; sku: string; brandId: string | null; categoryIds: string[]; attributes: Record<string,string> };

@Component({
    selector: 'artio-catalog-metadata-admin',
    imports: [FormsModule, SearchSelectComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `
    <section class="catalog-metadata-page" [class.catalog-entity-page]="view!=='catalog'">
      @if (view==='categories') {
      <div class="panel">
        <div class="panel-heading"><div><p class="eyebrow">{{ct('structure')}}</p><h2>{{ct('categories')}}</h2></div><button type="button" class="secondary" (click)="load()" [disabled]="metadataLoading">{{ct('refresh')}}</button></div>
        @if (metadataLoading) { <div class="loading-indicator" role="status"><span>{{t('loadingCatalogData')}}</span></div> }
        <form class="create-form category-create-form" (ngSubmit)="createCategory()">
          <label for="new-category-name">{{ct('categoryName')}}
          <input id="new-category-name" [(ngModel)]="categoryDraft.name" name="categoryName" maxlength="120" [placeholder]="ct('categoryName')" required>
          </label>
          <artio-search-select [(ngModel)]="categoryDraft.parentId" name="categoryParent" [label]="ct('parentCategory')" [options]="parentOptions" [placeholder]="ct('topLevel')"></artio-search-select>
          <button class="primary" [disabled]="metadataLoading || creatingCategory">@if (creatingCategory) {<span class="button-spinner" aria-hidden="true"></span>}{{ct('addCategory')}}</button>
        </form>
        <label class="search catalog-entity-search"><span class="catalog-search-icon" aria-hidden="true"><svg viewBox="0 0 24 24" focusable="false"><circle cx="10.8" cy="10.8" r="6.3"></circle><path d="m16 16 4.2 4.2"></path></svg></span><input [(ngModel)]="entityQuery" [ngModelOptions]="{standalone:true}" [attr.aria-label]="ct('searchCategories')" [placeholder]="ct('searchCategories')"></label>
        <div class="catalog-entity-list">@for (category of filteredCategories; track category.id) {
          <div class="row catalog-entity-row"><span class="row-main"><strong>{{category.name}}</strong><small>{{category.parentId?ct('childOf').replace('{name}',categoryName(category.parentId)):ct('topLevel')}} · {{category.active?ct('active'):ct('inactive')}}</small></span><div class="catalog-entity-actions"><button type="button" class="secondary" (click)="editCategory(category)">{{ct('edit')}}</button><button type="button" class="secondary" (click)="toggleCategory(category)">{{category.active?ct('deactivate'):ct('activate')}}</button></div></div>
        }
        @if (!metadataLoading && !filteredCategories.length) {
          <p class="empty">{{categories.length?ct('noMatchingCategories'):ct('noCategories')}}</p>
        }
        </div>
      </div>

      }

      @if (view==='catalog') {
      @if (productsLoading || !products.length) { <div class="panel"><div class="panel-heading"><div><p class="eyebrow">{{ct('classification')}}</p><h2>{{ct('assignData')}}</h2></div></div>@if(productsLoading){<div class="loading-indicator" role="status" aria-live="polite"><span>{{t('loadingCatalogData')}}</span></div>}@else{<p class="empty" role="status">{{t('dashboardEmpty')}}</p>}</div> }
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

      }

      @if (view==='brands') {
    <div class="panel">
      <div class="panel-heading"><div><p class="eyebrow">{{ct('manufacturers')}}</p><h2>{{ct('brands')}}</h2></div><button type="button" class="secondary" (click)="load()" [disabled]="metadataLoading">{{ct('refresh')}}</button></div>
      <form class="create-form brand-create-form" (ngSubmit)="createBrand()"><label for="new-brand-name">{{ct('brandName')}}<input id="new-brand-name" [(ngModel)]="brandDraft.name" name="brandName" maxlength="160" [placeholder]="ct('brandName')" required></label><label for="new-brand-description">{{ct('description')}}<input id="new-brand-description" [(ngModel)]="brandDraft.description" name="brandDescription" maxlength="1000" [placeholder]="ct('description')"></label><button class="primary" [disabled]="metadataLoading || creatingBrand">@if (creatingBrand) {<span class="button-spinner" aria-hidden="true"></span>}{{ct('addBrand')}}</button></form>
      <label class="search catalog-entity-search"><span class="catalog-search-icon" aria-hidden="true"><svg viewBox="0 0 24 24" focusable="false"><circle cx="10.8" cy="10.8" r="6.3"></circle><path d="m16 16 4.2 4.2"></path></svg></span><input [(ngModel)]="entityQuery" [ngModelOptions]="{standalone:true}" [attr.aria-label]="ct('searchBrands')" [placeholder]="ct('searchBrands')"></label>
      <div class="catalog-entity-list">@for (brand of filteredBrands; track brand.id) {
        <div class="row catalog-entity-row"><span class="row-main"><strong>{{brand.name}}</strong><small>{{brand.description}} · {{brand.active?ct('active'):ct('inactive')}}</small></span><div class="catalog-entity-actions"><button type="button" class="secondary" (click)="editBrand(brand)">{{ct('edit')}}</button><button type="button" class="secondary" (click)="toggleBrand(brand)">{{brand.active?ct('deactivate'):ct('activate')}}</button></div></div>
      }
      @if (!metadataLoading && !filteredBrands.length) {
        <p class="empty">{{brands.length?ct('noMatchingBrands'):ct('noBrands')}}</p>
      }
      </div></div>

      }

      @if (view==='catalog') {
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
      }
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
export class CatalogMetadataAdminComponent implements OnInit {
  @Input() view:'catalog'|'categories'|'brands'='catalog';
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
  metadataLoading = false;
  productsLoading = false;
  creatingCategory = false;
  creatingBrand = false;
  private metadataLoadGeneration = 0;
  catalogEditor:any=null;
  catalogEditorBusy=false;
  catalogEditorError='';
  categoryDraft = { name: '', parentId: '' };
  brandDraft = { name: '', description: '' };
  entityQuery='';
  attributeDraft = { code: '', label: '', type: 'TEXT' as AttributeDefinition['type'], required: false };

  get parentOptions():SelectOption[]{return [{value:'',label:this.ct('topLevel')},...this.categories.map(x=>({value:x.id,label:x.name}))];}
  get productOptions():SelectOption[]{return this.products.map(x=>({value:x.id,label:x.name,detail:x.sku}));}
  get filteredCategories():Category[]{const query=this.entityQuery.trim().toLocaleLowerCase();return this.categories.filter(category=>!query||`${category.name} ${category.parentId?this.categoryName(category.parentId):''}`.toLocaleLowerCase().includes(query));}
  get filteredBrands():Brand[]{const query=this.entityQuery.trim().toLocaleLowerCase();return this.brands.filter(brand=>!query||`${brand.name} ${brand.description}`.toLocaleLowerCase().includes(query));}
  get brandOptions():SelectOption[]{return [{value:'',label:this.ct('noBrand')},...this.brands.map(x=>({value:x.id,label:x.name,disabled:!x.active,detail:x.active?'':this.ct('inactive')}))];}
  get categoryOptions():SelectOption[]{return this.categories.map(x=>({value:x.id,label:x.name,disabled:!x.active,detail:x.parentId?this.ct('childOf').replace('{name}',this.categoryName(x.parentId)):x.active?this.ct('topLevel'):this.ct('inactive')}));}
  ngOnInit():void{this.load();if(this.view==='catalog')this.loadProducts();}

  get productAttributeDefinitions(): AttributeDefinition[] {
    const retained=new Set(Object.keys(this.selectedProduct?.attributes??{}));
    return this.attributes.filter(attribute=>attribute.active||retained.has(attribute.code));
  }

  load(): void {
    const generation = ++this.metadataLoadGeneration;
    let pending = this.view==='catalog'?3:1;
    this.metadataLoading = true;
    const finish = () => { if (generation === this.metadataLoadGeneration && --pending === 0) this.metadataLoading = false; };
    if(this.view!=='brands')this.http.get<Category[]>('/api/v1/categories').pipe(finalize(finish)).subscribe({ next: rows => { if (generation === this.metadataLoadGeneration) this.categories = rows; }, error: e => this.fail(e, this.ct('loadCategoriesFailure')) });
    if(this.view!=='categories')this.http.get<Brand[]>('/api/v1/brands').pipe(finalize(finish)).subscribe({ next: rows => { if (generation === this.metadataLoadGeneration) this.brands = rows; }, error: e => this.fail(e, this.ct('loadBrandsFailure')) });
    if(this.view==='catalog')this.http.get<AttributeDefinition[]>('/api/v1/attribute-definitions').pipe(finalize(finish)).subscribe({ next: rows => { if (generation === this.metadataLoadGeneration) this.attributes = rows; }, error: e => this.fail(e, this.ct('loadAttributesFailure')) });
  }

  loadProducts(): void { this.productsLoading=true;this.http.get<CatalogProduct[]>('/api/v1/products').subscribe({next:rows=>{this.products=rows;this.productsLoading=false},error:e=>{this.productsLoading=false;this.fail(e,this.ct('loadProductsFailure'))}}); }
  selectProduct(id:string):void{this.selectedProduct=this.products.find(row=>row.id===id)??null;if(!this.selectedProduct)return;this.productDraft={brandId:this.selectedProduct.brandId??'',categoryIds:[...this.selectedProduct.categoryIds],attributes:{...this.selectedProduct.attributes}};}
  toggleProductCategory(id:string,event:Event):void{const checked=(event.target as HTMLInputElement).checked;this.productDraft.categoryIds=checked?[...this.productDraft.categoryIds,id]:this.productDraft.categoryIds.filter(value=>value!==id);}
  setAttribute(code:string,value:string):void{this.productDraft.attributes={...this.productDraft.attributes,[code]:value};}
  saveProductMetadata():void{if(!this.selectedProduct)return;const attributes=Object.fromEntries(Object.entries(this.productDraft.attributes).map(([key,value])=>[key,value===''?null:value]));this.http.put(`/api/v1/products/${this.selectedProduct.id}/catalog-metadata`,{brandId:this.productDraft.brandId||null,categoryIds:this.productDraft.categoryIds,attributes}).subscribe({next:()=>{this.error='';this.loadProducts();this.selectedProduct=null;this.selectedProductId='';},error:e=>this.fail(e,this.ct('saveClassificationFailure'))});}

  categoryName(id: string): string { return this.categories.find(row => row.id === id)?.name ?? this.ct('unknownParent'); }
  createCategory(): void {
    if (this.creatingCategory || !this.categoryDraft.name.trim()) return;
    this.creatingCategory = true;
    this.http.post<Category>('/api/v1/categories', { name: this.categoryDraft.name.trim(), parentId: this.categoryDraft.parentId || null, active: true }).subscribe({
      next: () => { this.creatingCategory = false; this.categoryDraft = { name: '', parentId: '' }; this.load(); this.error = ''; }, error: e => { this.creatingCategory = false; this.fail(e, this.ct('createCategoryFailure')); }
    });
  }
  editCategory(row: Category): void {
    this.openCatalogEditor('category',{...row,parentId:row.parentId??''});
  }
  toggleCategory(row: Category): void {
    this.http.put(`/api/v1/categories/${row.id}`, { name: row.name, parentId: row.parentId, active: !row.active }).subscribe({ next: () => this.load(), error: e => this.fail(e, this.ct('changeCategoryFailure')) });
  }

  createBrand(): void {
    if (this.creatingBrand || !this.brandDraft.name.trim()) return;
    this.creatingBrand = true;
    this.http.post('/api/v1/brands', { ...this.brandDraft, name: this.brandDraft.name.trim(), active: true }).subscribe({ next: () => { this.creatingBrand = false; this.brandDraft = { name: '', description: '' }; this.load(); this.error = ''; }, error: e => { this.creatingBrand = false; this.fail(e, this.ct('createBrandFailure')); } });
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
