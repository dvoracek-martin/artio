import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';

type Category = { id: string; name: string; parentId: string | null; active: boolean };
type Brand = { id: string; name: string; description: string; active: boolean };
type AttributeDefinition = { id: string; code: string; label: string; type: 'TEXT' | 'NUMBER' | 'BOOLEAN'; required: boolean; active: boolean };

@Component({
  selector: 'artio-catalog-metadata-admin',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <section>
      <div class="panel">
        <div class="panel-heading"><div><p class="eyebrow">CATALOG STRUCTURE</p><h2>Categories</h2></div><button class="link-button" (click)="load()">Refresh</button></div>
        <form class="create-form" (ngSubmit)="createCategory()">
          <input [(ngModel)]="categoryDraft.name" name="categoryName" maxlength="120" placeholder="Category name" required>
          <select [(ngModel)]="categoryDraft.parentId" name="categoryParent"><option value="">Top-level category</option><option *ngFor="let category of categories" [value]="category.id">{{category.name}}</option></select>
          <button class="primary">Add category</button>
        </form>
        <div class="row" *ngFor="let category of categories"><span class="row-main"><strong>{{category.name}}</strong><small>{{category.parentId?'Child of '+categoryName(category.parentId):'Top level'}} · {{category.active?'Active':'Inactive'}}</small></span><button class="link-button" (click)="editCategory(category)">Edit</button><button class="link-button" (click)="toggleCategory(category)">{{category.active?'Deactivate':'Activate'}}</button></div>
        <p *ngIf="!categories.length" class="empty">No categories are configured.</p>
      </div>

      <div class="panel">
        <div class="panel-heading"><div><p class="eyebrow">MANUFACTURERS</p><h2>Brands</h2></div></div>
        <form class="create-form" (ngSubmit)="createBrand()"><input [(ngModel)]="brandDraft.name" name="brandName" maxlength="160" placeholder="Brand name" required><input [(ngModel)]="brandDraft.description" name="brandDescription" maxlength="1000" placeholder="Description"><button class="primary">Add brand</button></form>
        <div class="row" *ngFor="let brand of brands"><span class="row-main"><strong>{{brand.name}}</strong><small>{{brand.description}} · {{brand.active?'Active':'Inactive'}}</small></span><button class="link-button" (click)="editBrand(brand)">Edit</button><button class="link-button" (click)="toggleBrand(brand)">{{brand.active?'Deactivate':'Activate'}}</button></div>
        <p *ngIf="!brands.length" class="empty">No brands are configured.</p>
      </div>

      <div class="panel">
        <div class="panel-heading"><div><p class="eyebrow">PRODUCT DATA</p><h2>Attribute definitions</h2></div></div>
        <form class="create-form" (ngSubmit)="createAttribute()"><input [(ngModel)]="attributeDraft.code" name="attributeCode" pattern="[A-Z][A-Z0-9_]{1,31}" maxlength="32" placeholder="CODE" required><input [(ngModel)]="attributeDraft.label" name="attributeLabel" maxlength="120" placeholder="Label" required><select [(ngModel)]="attributeDraft.type" name="attributeType"><option value="TEXT">Text</option><option value="NUMBER">Number</option><option value="BOOLEAN">Boolean</option></select><label><input type="checkbox" [(ngModel)]="attributeDraft.required" name="attributeRequired"> Required</label><button class="primary">Add attribute</button></form>
        <div class="row" *ngFor="let attribute of attributes"><span class="row-main"><strong>{{attribute.label}} · {{attribute.code}}</strong><small>{{attribute.type}} · {{attribute.required?'Required':'Optional'}} · {{attribute.active?'Active':'Inactive'}}</small></span><button class="link-button" (click)="editAttribute(attribute)">Rename</button><button class="link-button" (click)="toggleAttribute(attribute)">{{attribute.active?'Deactivate':'Activate'}}</button></div>
        <p *ngIf="!attributes.length" class="empty">No attribute definitions are configured.</p>
      </div>
      <p class="error" *ngIf="error" role="alert">{{error}}</p>
    </section>
  `
})
export class CatalogMetadataAdminComponent {
  private readonly http = inject(HttpClient);
  categories: Category[] = [];
  brands: Brand[] = [];
  attributes: AttributeDefinition[] = [];
  error = '';
  categoryDraft = { name: '', parentId: '' };
  brandDraft = { name: '', description: '' };
  attributeDraft = { code: '', label: '', type: 'TEXT' as AttributeDefinition['type'], required: false };

  constructor() { this.load(); }

  load(): void {
    this.http.get<Category[]>('/api/v1/categories').subscribe({ next: rows => this.categories = rows, error: e => this.fail(e, 'Could not load categories.') });
    this.http.get<Brand[]>('/api/v1/brands').subscribe({ next: rows => this.brands = rows, error: e => this.fail(e, 'Could not load brands.') });
    this.http.get<AttributeDefinition[]>('/api/v1/attribute-definitions').subscribe({ next: rows => this.attributes = rows, error: e => this.fail(e, 'Could not load attribute definitions.') });
  }

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
