
import { HttpClient } from '@angular/common/http';
import { Component, inject, ChangeDetectionStrategy } from '@angular/core';
import { SearchSelectComponent, SelectOption } from './search-select.component';
import { FormsModule } from '@angular/forms';

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
        <div class="panel-heading"><div><p class="eyebrow">CATALOG STRUCTURE</p><h2>Categories</h2></div><button class="link-button" (click)="load()">Refresh</button></div>
        <form class="create-form" (ngSubmit)="createCategory()">
          <input [(ngModel)]="categoryDraft.name" name="categoryName" maxlength="120" placeholder="Category name" required>
          <artio-search-select [(ngModel)]="categoryDraft.parentId" name="categoryParent" label="Parent category" [options]="parentOptions" placeholder="Top-level category"></artio-search-select>
          <button class="primary">Add category</button>
        </form>
        @for (category of categories; track category) {
          <div class="row"><span class="row-main"><strong>{{category.name}}</strong><small>{{category.parentId?'Child of '+categoryName(category.parentId):'Top level'}} · {{category.active?'Active':'Inactive'}}</small></span><button class="link-button" (click)="editCategory(category)">Edit</button><button class="link-button" (click)="toggleCategory(category)">{{category.active?'Deactivate':'Activate'}}</button></div>
        }
        @if (!categories.length) {
          <p class="empty">No categories are configured.</p>
        }
      </div>

      @if (products.length) {
        <div class="panel">
          <div class="panel-heading"><div><p class="eyebrow">PRODUCT CLASSIFICATION</p><h2>Assign catalog data</h2></div></div>
          <div class="setup-field"><span>Product</span><artio-search-select [(ngModel)]="selectedProductId" name="metadataProduct" (ngModelChange)="selectProduct($event)" label="Product to classify" [options]="productOptions" placeholder="Choose a product"></artio-search-select></div>
          @if (selectedProduct) {
            <form (ngSubmit)="saveProductMetadata()">
              <div class="setup-field"><span>Brand</span><artio-search-select [(ngModel)]="productDraft.brandId" name="productBrand" label="Product brand" [options]="brandOptions" placeholder="No brand"></artio-search-select></div>
              <h3>Categories</h3><artio-search-select [(ngModel)]="productDraft.categoryIds" name="productCategories" label="Product categories" [options]="categoryOptions" [multiple]="true" placeholder="Choose categories"></artio-search-select>
              <h3>Attributes</h3>@for (attribute of productAttributeDefinitions; track attribute) {
              <div><label>{{attribute.label}} · {{attribute.code}}<small>{{attribute.required?'Required':'Optional'}}{{attribute.active?'':' · inactive, retained read-only'}}</small>@if (attribute.type==='BOOLEAN') {
              <select [ngModel]="productDraft.attributes[attribute.code]||''" (ngModelChange)="setAttribute(attribute.code,$event)" [name]="'attribute-'+attribute.code" [disabled]="!attribute.active"><option value="">Unset</option><option value="true">Yes</option><option value="false">No</option></select>
              }@if (attribute.type!=='BOOLEAN') {
              <input [type]="attribute.type==='NUMBER'?'number':'text'" [ngModel]="productDraft.attributes[attribute.code]||''" (ngModelChange)="setAttribute(attribute.code,$event)" [name]="'attribute-'+attribute.code" [required]="attribute.required&&attribute.active" [disabled]="!attribute.active">
            }</label></div>
          }
          <button class="primary">Save product classification</button>
        </form>
      }
      @if (selectedProduct) {
        <p class="muted">Inactive categories and definitions cannot be newly assigned. Existing inactive attribute values are preserved until the definition is reactivated.</p>
      }
    </div>
    }

    <div class="panel">
      <div class="panel-heading"><div><p class="eyebrow">MANUFACTURERS</p><h2>Brands</h2></div></div>
      <form class="create-form" (ngSubmit)="createBrand()"><input [(ngModel)]="brandDraft.name" name="brandName" maxlength="160" placeholder="Brand name" required><input [(ngModel)]="brandDraft.description" name="brandDescription" maxlength="1000" placeholder="Description"><button class="primary">Add brand</button></form>
      @for (brand of brands; track brand) {
        <div class="row"><span class="row-main"><strong>{{brand.name}}</strong><small>{{brand.description}} · {{brand.active?'Active':'Inactive'}}</small></span><button class="link-button" (click)="editBrand(brand)">Edit</button><button class="link-button" (click)="toggleBrand(brand)">{{brand.active?'Deactivate':'Activate'}}</button></div>
      }
      @if (!brands.length) {
        <p class="empty">No brands are configured.</p>
      }
    </div>

    <div class="panel">
      <div class="panel-heading"><div><p class="eyebrow">PRODUCT DATA</p><h2>Attribute definitions</h2></div></div>
      <form class="create-form" (ngSubmit)="createAttribute()"><input [(ngModel)]="attributeDraft.code" name="attributeCode" pattern="[A-Z][A-Z0-9_]{1,31}" maxlength="32" placeholder="CODE" required><input [(ngModel)]="attributeDraft.label" name="attributeLabel" maxlength="120" placeholder="Label" required><select [(ngModel)]="attributeDraft.type" name="attributeType"><option value="TEXT">Text</option><option value="NUMBER">Number</option><option value="BOOLEAN">Boolean</option></select><label><input type="checkbox" [(ngModel)]="attributeDraft.required" name="attributeRequired"> Required</label><button class="primary">Add attribute</button></form>
      @for (attribute of attributes; track attribute) {
        <div class="row"><span class="row-main"><strong>{{attribute.label}} · {{attribute.code}}</strong><small>{{attribute.type}} · {{attribute.required?'Required':'Optional'}} · {{attribute.active?'Active':'Inactive'}}</small></span><button class="link-button" (click)="editAttribute(attribute)">Rename</button><button class="link-button" (click)="toggleAttribute(attribute)">{{attribute.active?'Deactivate':'Activate'}}</button></div>
      }
      @if (!attributes.length) {
        <p class="empty">No attribute definitions are configured.</p>
      }
    </div>
    @if (error) {
      <p class="error" role="alert">{{error}}</p>
    }
    </section>
    `
})
export class CatalogMetadataAdminComponent {
  private readonly http = inject(HttpClient);
  categories: Category[] = [];
  brands: Brand[] = [];
  attributes: AttributeDefinition[] = [];
  products: CatalogProduct[] = [];
  selectedProductId = '';
  selectedProduct: CatalogProduct | null = null;
  productDraft = { brandId: '', categoryIds: [] as string[], attributes: {} as Record<string,string> };
  error = '';
  categoryDraft = { name: '', parentId: '' };
  brandDraft = { name: '', description: '' };
  attributeDraft = { code: '', label: '', type: 'TEXT' as AttributeDefinition['type'], required: false };

  get parentOptions():SelectOption[]{return [{value:'',label:'Top-level category'},...this.categories.map(x=>({value:x.id,label:x.name}))];}
  get productOptions():SelectOption[]{return this.products.map(x=>({value:x.id,label:x.name,detail:x.sku}));}
  get brandOptions():SelectOption[]{return [{value:'',label:'No brand'},...this.brands.map(x=>({value:x.id,label:x.name,disabled:!x.active,detail:x.active?'':'Inactive'}))];}
  get categoryOptions():SelectOption[]{return this.categories.map(x=>({value:x.id,label:x.name,disabled:!x.active,detail:x.parentId?'Under '+this.categoryName(x.parentId):x.active?'Top level':'Inactive'}));}
  constructor() { this.load(); this.loadProducts(); }

  get productAttributeDefinitions(): AttributeDefinition[] {
    const retained=new Set(Object.keys(this.selectedProduct?.attributes??{}));
    return this.attributes.filter(attribute=>attribute.active||retained.has(attribute.code));
  }

  load(): void {
    this.http.get<Category[]>('/api/v1/categories').subscribe({ next: rows => this.categories = rows, error: e => this.fail(e, 'Could not load categories.') });
    this.http.get<Brand[]>('/api/v1/brands').subscribe({ next: rows => this.brands = rows, error: e => this.fail(e, 'Could not load brands.') });
    this.http.get<AttributeDefinition[]>('/api/v1/attribute-definitions').subscribe({ next: rows => this.attributes = rows, error: e => this.fail(e, 'Could not load attribute definitions.') });
  }

  loadProducts(): void { this.http.get<CatalogProduct[]>('/api/v1/products').subscribe({next:rows=>this.products=rows,error:e=>this.fail(e,'Could not load products for classification.')}); }
  selectProduct(id:string):void{this.selectedProduct=this.products.find(row=>row.id===id)??null;if(!this.selectedProduct)return;this.productDraft={brandId:this.selectedProduct.brandId??'',categoryIds:[...this.selectedProduct.categoryIds],attributes:{...this.selectedProduct.attributes}};}
  toggleProductCategory(id:string,event:Event):void{const checked=(event.target as HTMLInputElement).checked;this.productDraft.categoryIds=checked?[...this.productDraft.categoryIds,id]:this.productDraft.categoryIds.filter(value=>value!==id);}
  setAttribute(code:string,value:string):void{this.productDraft.attributes={...this.productDraft.attributes,[code]:value};}
  saveProductMetadata():void{if(!this.selectedProduct)return;const attributes=Object.fromEntries(Object.entries(this.productDraft.attributes).map(([key,value])=>[key,value===''?null:value]));this.http.put(`/api/v1/products/${this.selectedProduct.id}/catalog-metadata`,{brandId:this.productDraft.brandId||null,categoryIds:this.productDraft.categoryIds,attributes}).subscribe({next:()=>{this.error='';this.loadProducts();this.selectedProduct=null;this.selectedProductId='';},error:e=>this.fail(e,'Could not save product classification.')});}

  categoryName(id: string): string { return this.categories.find(row => row.id === id)?.name ?? 'Unknown parent'; }
  createCategory(): void {
    this.http.post<Category>('/api/v1/categories', { name: this.categoryDraft.name.trim(), parentId: this.categoryDraft.parentId || null, active: true }).subscribe({
      next: () => { this.categoryDraft = { name: '', parentId: '' }; this.load(); this.error = ''; }, error: e => this.fail(e, 'Could not create category.')
    });
  }
  editCategory(row: Category): void {
    const name = prompt('Category name', row.name); if (!name?.trim()) return;
    const parentText = prompt('Parent category UUID, or leave blank for top level', row.parentId ?? ''); if (parentText === null) return;
    const parentId = parentText.trim() || null;
    this.http.put(`/api/v1/categories/${row.id}`, { name: name.trim(), parentId, active: row.active }).subscribe({ next: () => { this.load(); this.error = ''; }, error: e => this.fail(e, 'Could not update category.') });
  }
  toggleCategory(row: Category): void {
    this.http.put(`/api/v1/categories/${row.id}`, { name: row.name, parentId: row.parentId, active: !row.active }).subscribe({ next: () => this.load(), error: e => this.fail(e, 'Could not change category status.') });
  }

  createBrand(): void {
    this.http.post('/api/v1/brands', { ...this.brandDraft, name: this.brandDraft.name.trim(), active: true }).subscribe({ next: () => { this.brandDraft = { name: '', description: '' }; this.load(); this.error = ''; }, error: e => this.fail(e, 'Could not create brand.') });
  }
  editBrand(row: Brand): void {
    const name = prompt('Brand name', row.name); if (!name?.trim()) return;
    const description = prompt('Brand description', row.description ?? ''); if (description === null) return;
    this.http.put(`/api/v1/brands/${row.id}`, { name: name.trim(), description: description.trim(), active: row.active }).subscribe({ next: () => { this.load(); this.error = ''; }, error: e => this.fail(e, 'Could not update brand.') });
  }
  toggleBrand(row: Brand): void {
    this.http.put(`/api/v1/brands/${row.id}`, { name: row.name, description: row.description, active: !row.active }).subscribe({ next: () => this.load(), error: e => this.fail(e, 'Could not change brand status.') });
  }

  createAttribute(): void {
    this.http.post('/api/v1/attribute-definitions', { ...this.attributeDraft, code: this.attributeDraft.code.trim().toUpperCase(), label: this.attributeDraft.label.trim() }).subscribe({
      next: () => { this.attributeDraft = { code: '', label: '', type: 'TEXT', required: false }; this.load(); this.error = ''; }, error: e => this.fail(e, 'Could not create attribute definition.')
    });
  }
  editAttribute(row: AttributeDefinition): void {
    const label = prompt('Attribute label', row.label); if (!label?.trim()) return;
    this.http.put(`/api/v1/attribute-definitions/${row.id}`, { label: label.trim(), active: row.active }).subscribe({ next: () => { this.load(); this.error = ''; }, error: e => this.fail(e, 'Could not rename attribute.') });
  }
  toggleAttribute(row: AttributeDefinition): void {
    this.http.put(`/api/v1/attribute-definitions/${row.id}`, { label: row.label, active: !row.active }).subscribe({ next: () => this.load(), error: e => this.fail(e, 'Could not change attribute status.') });
  }

  private fail(error: any, fallback: string): void { this.error = error.error?.detail ?? fallback; }
}
