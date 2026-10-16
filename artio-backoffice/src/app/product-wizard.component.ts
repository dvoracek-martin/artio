
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, EventEmitter, OnDestroy, OnInit, Output, inject, ChangeDetectionStrategy } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SearchSelectComponent, SelectOption } from './search-select.component';
import { ProductWizardTextKey, productWizardText } from './product-wizard-locale';
import { catchError, firstValueFrom, forkJoin, of, timeout } from 'rxjs';

type Category = { id: string; name: string; parentId: string | null; active: boolean };
type Brand = { id: string; name: string; active: boolean };
type Attribute = { code: string; label: string; type: string; required: boolean; active: boolean };
type ImageDraft = { file: File; url: string; alt: string; uploaded: boolean };

@Component({
    selector: 'artio-product-wizard', imports: [FormsModule, SearchSelectComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    templateUrl: './product-wizard.component.html'
})
export class ProductWizardComponent implements OnInit, OnDestroy {
  @Output() cancel = new EventEmitter<void>();
  @Output() created = new EventEmitter<string>();
  @Output() installStoragePlugin = new EventEmitter<void>();
  private http = inject(HttpClient);
  private destroyed = false;
  get steps():string[]{return [this.t('stepEssentials'),this.t('stepOrganization'),this.t('stepImages'),this.t('stepReview')]}
  t(key:ProductWizardTextKey,values:Record<string,string|number>={}):string{return Object.entries(values).reduce((text,[name,value])=>text.replaceAll(`{${name}}`,String(value)),productWizardText(key))}
  stepDescription(index:number):string{return this.t(index===0?'stepIdentity':index===1?'stepCategories':index===2?'stepUpload':'stepLastLook')}
  step = 0;
  loading = true;
  busy = false;
  error = '';
  progress = '';
  productId: string | null = null;
  categories: Category[] = [];
  brands: Brand[] = [];
  attributes: Attribute[] = [];
  images: ImageDraft[] = [];
  draft = { name: '', sku: '', description: '', variantName: 'Standard', variantSku: '', brandId: '', categoryIds: [] as string[], attributes: {} as Record<string, string> };
  publish = false;
  categoryName = '';
  creatingCategory = false;
  uploadAvailable = false;

  ngOnInit(): void { this.loadOptions(); }
  ngOnDestroy(): void { this.destroyed = true; this.images.forEach(image => URL.revokeObjectURL(image.url)); }
  loadOptions(): void {
    this.loading = true;
    this.error = '';
    forkJoin({ categories: this.http.get<Category[]>('/api/v1/categories'), brands: this.http.get<Brand[]>('/api/v1/brands'), attributes: this.http.get<Attribute[]>('/api/v1/attribute-definitions'), storage: this.http.get<{ uploadAvailable: boolean }>('/api/v1/admin/catalog/media/storage-status').pipe(catchError(() => of({ uploadAvailable: false }))) }).pipe(timeout({ first: 30000 })).subscribe({
      next: data => { this.categories = data.categories.filter(x => x.active); this.brands = data.brands.filter(x => x.active); this.attributes = data.attributes.filter(x => x.active); this.uploadAvailable = data.storage.uploadAvailable; this.loading = false; },
      error: e => { this.loading = false; this.error = this.message(e, this.t('loadFailure')); }
    });
  }
  categoryLabel(category: Category): string {
    const names = [category.name];
    const seen = new Set([category.id]);
    let parent = this.categories.find(x => x.id === category.parentId);
    while (parent && !seen.has(parent.id)) { names.unshift(parent.name); seen.add(parent.id); parent = this.categories.find(x => x.id === parent!.parentId); }
    return names.join(' / ');
  }
  get categoryOptions():SelectOption[]{return this.categories.map(x=>({value:x.id,label:this.categoryLabel(x)}));}
  get brandOptions():SelectOption[]{return [{value:'',label:this.t('noBrand')},...this.brands.map(x=>({value:x.id,label:x.name}))];}
  get selectedCategories(): string { return this.categories.filter(x => this.draft.categoryIds.includes(x.id)).map(x => this.categoryLabel(x)).join(', '); }
  get brandName(): string { return this.brands.find(x => x.id === this.draft.brandId)?.name ?? this.t('noBrand'); }
  setSku(value: string): void {
    const previous = this.draft.sku;
    this.draft.sku = value.toUpperCase().trim();
    if (!this.draft.variantSku || this.draft.variantSku === previous.slice(0, 46) + '-STD') this.draft.variantSku = this.draft.sku.slice(0, 46) + '-STD';
  }
  toggleCategory(id: string): void {
    this.draft.categoryIds = this.draft.categoryIds.includes(id) ? this.draft.categoryIds.filter(x => x !== id) : [...this.draft.categoryIds, id];
  }
  async createCategory(): Promise<void> {
    if (!this.categoryName.trim() || this.creatingCategory) return;
    this.creatingCategory = true;
    this.error = '';
    try {
      const category = await firstValueFrom(this.http.post<Category>('/api/v1/categories', { name: this.categoryName.trim(), parentId: null, active: true }));
      this.categories = [...this.categories, category];
      this.draft.categoryIds.push(category.id);
      this.categoryName = '';
    } catch (e) { this.error = this.message(e, this.t('createCategoryFailure')); }
    finally { this.creatingCategory = false; }
  }
  validate(step: number): boolean {
    this.error = '';
    if (step === 0) {
      if (!this.draft.name.trim() || this.draft.name.length > 500) this.error = this.t('productNameError');
      else if (!/^[A-Z0-9-]{3,50}$/.test(this.draft.sku)) this.error = this.t('skuError');
      else if (!this.draft.variantName.trim() || this.draft.variantName.length > 250) this.error = this.t('variantNameError');
      else if (!/^[A-Z0-9-]{3,50}$/.test(this.draft.variantSku)) this.error = this.t('variantSkuError');
      else if (this.draft.description.length > 5000) this.error = this.t('descriptionError');
    }
    if (step === 1) {
      if (!this.draft.categoryIds.length) this.error = this.t('chooseCategoryError');
      for (const attribute of this.attributes) {
        const value = String(this.draft.attributes[attribute.code] ?? '').trim();
        if (attribute.required && !value) { this.error = this.t('attributeValueError',{label:attribute.label}); break; }
        if (value && attribute.type === 'NUMBER' && !Number.isFinite(Number(value))) { this.error = this.t('attributeNumberError',{label:attribute.label}); break; }
      }
    }
    return !this.error;
  }
  next(): void { if (this.validate(this.step)) { this.step++; this.focusHeading(); } }
  back(): void { this.error = ''; this.step--; this.focusHeading(); }
  edit(step: number): void { if (!this.busy && !this.productId) { this.step = step; this.focusHeading(); } }
  private focusHeading(): void { setTimeout(() => document.getElementById('wizard-heading')?.focus()); }
  selectImages(event: Event): void { const input = event.target as HTMLInputElement; this.addImages(Array.from(input.files ?? [])); input.value = ''; }
  drop(event: DragEvent): void { event.preventDefault(); if (!this.busy && !this.productId) this.addImages(Array.from(event.dataTransfer?.files ?? [])); }
  addImages(files: File[]): void {
    this.error = '';
    if (!this.uploadAvailable) { this.error = this.t('storagePluginRequired'); return; }
    for (const file of files) {
      if (this.images.length >= 100) { this.error = this.t('imageCountError'); break; }
      if (!['image/jpeg', 'image/png'].includes(file.type) || !file.size || file.size > 10 * 1024 * 1024) { this.error = this.t('imageFileError',{file:file.name}); continue; }
      this.images.push({ file, url: URL.createObjectURL(file), alt: this.draft.name, uploaded: false });
    }
  }
  removeImage(index: number): void { URL.revokeObjectURL(this.images[index].url); this.images.splice(index, 1); }
  moveImage(index: number, delta: number): void { const target = index + delta; if (target < 0 || target >= this.images.length) return; [this.images[index], this.images[target]] = [this.images[target], this.images[index]]; }
  async save(): Promise<void> {
    if (this.busy) return;
    if (!this.productId) for (let step = 0; step < 2; step++) if (!this.validate(step)) { this.step = step; return; }
    this.busy = true;
    this.error = '';
    try {
      if (!this.productId) {
        this.progress = this.t('creatingDraft');
        const attributes = Object.fromEntries(Object.entries(this.draft.attributes).filter(([, value]) => String(value ?? '').trim()).map(([key, value]) => [key, String(value).trim()]));
        const product = await firstValueFrom(this.http.post<{ id: string }>('/api/v1/products/drafts', { ...this.draft, name: this.draft.name.trim(), description: this.draft.description.trim(), variantName: this.draft.variantName.trim(), brandId: this.draft.brandId || null, attributes }));
        this.productId = product.id;
      }
      for (const [index, image] of this.images.entries()) {
        if (this.destroyed) return;
        if (image.uploaded) continue;
        this.progress = this.t('uploadingImage',{current:index+1,total:this.images.length});
        const body = new FormData(); body.append('file', image.file, image.file.name);
        await firstValueFrom(this.http.post(`/api/v1/products/${this.productId}/media`, body, { params: { altText: image.alt } }));
        image.uploaded = true;
      }
      if (this.destroyed) return;
      if (this.publish) {
        this.progress = this.t('activatingProduct');
        await firstValueFrom(this.http.put(`/api/v1/products/${this.productId}/active`, { active: true }));
      }
      if (!this.destroyed) this.created.emit(this.productId);
    } catch (e) {
      this.error = this.message(e, this.t('saveFailure')) + (this.productId ? this.t('savedDraftRetry') : this.t('checkDetailsRetry'));
    } finally { this.busy = false; this.progress = ''; }
  }
  private message(error: unknown, fallback: string): string { return error instanceof HttpErrorResponse ? error.error?.message ?? error.error?.detail ?? fallback : fallback; }
}
