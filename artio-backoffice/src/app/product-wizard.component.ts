
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, DoCheck, ElementRef, EventEmitter, HostListener, OnDestroy, OnInit, Output, ViewChild, inject, ChangeDetectionStrategy } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SearchSelectComponent, SelectOption } from './search-select.component';
import { ProductWizardTextKey, productWizardText } from './product-wizard-locale';
import { firstValueFrom, forkJoin, timeout } from 'rxjs';
import { backofficeText, currentBackofficeLocale } from './backoffice-locale';

type Category = { id: string; name: string; parentId: string | null; active: boolean };
type Brand = { id: string; name: string; active: boolean };
type Attribute = { code: string; label: string; type: string; required: boolean; active: boolean };
type ImageDraft = { file: File; url: string; alt: string; uploaded: boolean; previewError?: boolean };

@Component({
    selector: 'artio-product-wizard', imports: [FormsModule, SearchSelectComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    templateUrl: './product-wizard.component.html'
})
export class ProductWizardComponent implements OnInit, OnDestroy, DoCheck {
  @ViewChild('discardDialog') discardDialogRef?:ElementRef<HTMLDialogElement>;
  @Output() cancel = new EventEmitter<void>();
  @Output() created = new EventEmitter<{ id: string; name: string; description: string; sku: string; active: boolean }>();
  @Output() installStoragePlugin = new EventEmitter<void>();
  @Output() toast = new EventEmitter<string>();
  private http = inject(HttpClient);
  private destroyed = false;
  get steps():string[]{return [this.t('stepEssentials'),this.t('stepOrganization'),this.t('stepImages'),this.t('stepReview')]}
  t(key:ProductWizardTextKey,values:Record<string,string|number>={}):string{return Object.entries(values).reduce((text,[name,value])=>text.replaceAll(`{${name}}`,String(value)),productWizardText(key))}
  stepDescription(index:number):string{return this.t(index===0?'stepIdentity':index===1?'stepCategories':index===2?'stepUpload':'stepLastLook')}
  step = 0;
  optionsLoading = false;
  optionsLoaded = false;
  storageLoading = false;
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
  categoryDescription = '';
  categoryCreateOpen = false;
  brandDraft = { name: '', description: '' };
  brandCreateOpen = false;
  creatingCategory = false;
  creatingBrand = false;
  uploadAvailable = false;
  draftRestored=false;
  draftReady=false;
  private draftSaveTimer:number|undefined;
  private lastDraftSnapshot='';

  ngOnInit(): void { void this.restoreDraft(); }
  ngDoCheck():void{if(!this.draftReady||this.productId)return;const snapshot=JSON.stringify({step:this.step,draft:this.draft,publish:this.publish,categoryName:this.categoryName,categoryDescription:this.categoryDescription,brandDraft:this.brandDraft,images:this.images.map(image=>({fileName:image.file.name,fileSize:image.file.size,fileType:image.file.type,lastModified:image.file.lastModified,alt:image.alt,uploaded:image.uploaded}))});if(snapshot===this.lastDraftSnapshot)return;this.lastDraftSnapshot=snapshot;if(this.draftSaveTimer)window.clearTimeout(this.draftSaveTimer);this.draftSaveTimer=window.setTimeout(()=>void this.persistDraft(),180);}
  ngOnDestroy(): void { this.destroyed = true; this.images.forEach(image => URL.revokeObjectURL(image.url)); }
  private async openDraftDatabase():Promise<IDBDatabase>{return await new Promise((resolve,reject)=>{const request=indexedDB.open('artio-backoffice-drafts',1);request.onupgradeneeded=()=>{if(!request.result.objectStoreNames.contains('drafts'))request.result.createObjectStore('drafts')};request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error)});}
  private async restoreDraft():Promise<void>{try{if(typeof indexedDB==='undefined')return;const db=await this.openDraftDatabase();if(sessionStorage.getItem('artio-start-new-product')==='1'){sessionStorage.removeItem('artio-start-new-product');await new Promise<void>(resolve=>{const transaction=db.transaction('drafts','readwrite');transaction.objectStore('drafts').delete('new-product');transaction.oncomplete=()=>resolve();transaction.onerror=()=>resolve()});db.close();return}const saved=await new Promise<any>((resolve,reject)=>{const request=db.transaction('drafts','readonly').objectStore('drafts').get('new-product');request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error)});if(saved&&!this.destroyed){this.draft={...this.draft,...saved.draft};this.step=saved.step??0;this.publish=!!saved.publish;this.categoryName=saved.categoryName??'';this.categoryDescription=saved.categoryDescription??'';this.brandDraft=saved.brandDraft??{name:'',description:''};this.images=(saved.images??[]).filter((image:any)=>image.file instanceof File).map((image:any)=>({...image,url:URL.createObjectURL(image.file)}));this.draftRestored=true}db.close()}catch{/* Draft persistence is best-effort when browser storage is unavailable. */}finally{this.draftReady=true;this.lastDraftSnapshot=JSON.stringify({step:this.step,draft:this.draft,publish:this.publish,categoryName:this.categoryName,categoryDescription:this.categoryDescription,brandDraft:this.brandDraft,images:this.images.map(image=>({fileName:image.file.name,fileSize:image.file.size,fileType:image.file.type,lastModified:image.file.lastModified,alt:image.alt,uploaded:image.uploaded}))})}}
  private async persistDraft():Promise<void>{try{if(typeof indexedDB==='undefined'||this.productId||!this.draftReady)return;const db=await this.openDraftDatabase();await new Promise<void>((resolve,reject)=>{const transaction=db.transaction('drafts','readwrite');transaction.objectStore('drafts').put({step:this.step,draft:this.draft,publish:this.publish,categoryName:this.categoryName,categoryDescription:this.categoryDescription,brandDraft:this.brandDraft,images:this.images.map(({file,alt,uploaded})=>({file,alt,uploaded}))},'new-product');transaction.oncomplete=()=>resolve();transaction.onerror=()=>reject(transaction.error)});db.close();this.draftRestored=true}catch{/* Quota or browser privacy settings may prevent persistence. */}}
  openDiscardConfirmation():void{queueMicrotask(()=>this.discardDialogRef?.nativeElement.showModal())}
  cancelDiscardConfirmation(event:Event):void{event.preventDefault();this.discardDialogRef?.nativeElement.close()}
  discardConfirmationText(key:'adminDeleteTitle'|'adminDeleteDescription'|'adminDeleteConfirm'):string{return backofficeText(currentBackofficeLocale(),key)}
  async discardDraft():Promise<void>{this.discardDialogRef?.nativeElement.close();this.images.forEach(image=>URL.revokeObjectURL(image.url));this.images=[];this.step=0;this.draft={name:'',sku:'',description:'',variantName:'Standard',variantSku:'',brandId:'',categoryIds:[],attributes:{}};this.publish=false;this.categoryName='';this.categoryDescription='';this.brandDraft={name:'',description:''};this.draftRestored=false;try{const db=await this.openDraftDatabase();await new Promise<void>(resolve=>{const transaction=db.transaction('drafts','readwrite');transaction.objectStore('drafts').delete('new-product');transaction.oncomplete=()=>resolve();transaction.onerror=()=>resolve()});db.close()}catch{}this.lastDraftSnapshot='';}
  async clearDraft():Promise<void>{try{const db=await this.openDraftDatabase();await new Promise<void>(resolve=>{const transaction=db.transaction('drafts','readwrite');transaction.objectStore('drafts').delete('new-product');transaction.oncomplete=()=>resolve();transaction.onerror=()=>resolve()});db.close()}catch{}}
  @HostListener('window:focus') onWindowFocus():void{if(this.step===2&&!this.uploadAvailable)this.loadStorageAvailability();}
  loadOptions(): void {
    if (this.optionsLoading) return;
    this.optionsLoading = true;
    this.error = '';
    forkJoin({ categories: this.http.get<Category[]>('/api/v1/categories'), brands: this.http.get<Brand[]>('/api/v1/brands'), attributes: this.http.get<Attribute[]>('/api/v1/attribute-definitions') }).pipe(timeout({ first: 30000 })).subscribe({
      next: data => { this.categories = data.categories.filter(x => x.active); this.brands = data.brands.filter(x => x.active); this.attributes = data.attributes.filter(x => x.active); this.optionsLoaded = true; this.optionsLoading = false; },
      error: e => { this.optionsLoading = false; this.error = this.message(e, this.t('loadFailure')); }
    });
  }
  private loadStorageAvailability(): void {
    if (this.storageLoading) return;
    this.storageLoading = true;
    this.http.get<{ uploadAvailable: boolean }>('/api/v1/admin/catalog/media/storage-status').subscribe({
      next: status => { this.uploadAvailable = status.uploadAvailable; this.storageLoading = false; },
      error: () => { this.uploadAvailable = false; this.storageLoading = false; }
    });
  }
  refreshStorageAvailability():void{this.loadStorageAvailability()}
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
    const name=this.categoryName.trim();
    const description=this.categoryDescription.trim();
    const optimisticId=`pending-${crypto.randomUUID()}`;
    const optimistic:Category={id:optimisticId,name,parentId:null,active:true};
    this.categories=[...this.categories,optimistic];
    this.draft.categoryIds=[...this.draft.categoryIds,optimisticId];
    this.categoryName='';
    this.categoryDescription='';
    this.creatingCategory = true;
    this.error = '';
    try {
      const category = await firstValueFrom(this.http.post<Category>('/api/v1/categories', { name, description, parentId: null, active: true }));
      this.categories=this.categories.map(item=>item.id===optimisticId?category:item);
      this.draft.categoryIds=this.draft.categoryIds.map(id=>id===optimisticId?category.id:id);
      this.categoryCreateOpen=false;
      this.toast.emit(this.t('categoryCreated'));
    } catch (e) { this.categories=this.categories.filter(item=>item.id!==optimisticId);this.draft.categoryIds=this.draft.categoryIds.filter(id=>id!==optimisticId);this.categoryName=name;this.categoryDescription=description;this.error = this.message(e, this.t('createCategoryFailure'));this.toast.emit(this.error); }
    finally { this.creatingCategory = false; }
  }
  async createBrand(): Promise<void> {
    if (!this.brandDraft.name.trim() || this.creatingBrand) return;
    const draft={name:this.brandDraft.name.trim(),description:this.brandDraft.description.trim()};
    const optimisticId=`pending-${crypto.randomUUID()}`;
    const optimistic:Brand={id:optimisticId,name:draft.name,active:true};
    this.brands=[...this.brands,optimistic];
    this.draft.brandId=optimisticId;
    this.brandDraft={name:'',description:''};
    this.creatingBrand = true;
    this.error = '';
    try {
      const brand = await firstValueFrom(this.http.post<Brand>('/api/v1/brands', { ...draft, active: true }));
      this.brands=this.brands.map(item=>item.id===optimisticId?brand:item);
      if(this.draft.brandId===optimisticId)this.draft.brandId=brand.id;
      this.brandCreateOpen=false;
      this.toast.emit(this.t('brandCreated'));
    } catch (e) { this.brands=this.brands.filter(item=>item.id!==optimisticId);if(this.draft.brandId===optimisticId)this.draft.brandId='';this.brandDraft=draft;this.error = this.message(e, this.t('createBrandFailure'));this.toast.emit(this.error); }
    finally { this.creatingBrand = false; }
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
  next(): void {
    if(this.creatingCategory||this.creatingBrand)return;
    if (!this.validate(this.step)) return;
    if (this.step === 2 && this.images.some(image => image.previewError)) {
      this.error = this.t('imagePreviewFailure');
      return;
    }
    this.step++;
    if (this.step === 1 && !this.optionsLoaded) this.loadOptions();
    if (this.step === 2) this.loadStorageAvailability();
    this.focusHeading();
  }
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
  imagePreviewFailed(image: ImageDraft): void {
    image.previewError = true;
    this.error = this.t('imagePreviewFailure');
  }
  moveImage(index: number, delta: number): void { const target = index + delta; if (target < 0 || target >= this.images.length) return; [this.images[index], this.images[target]] = [this.images[target], this.images[index]]; }
  async save(): Promise<void> {
    if (this.busy) return;
    if (this.images.some(image => image.previewError)) {
      this.step = 2;
      this.error = this.t('imagePreviewFailure');
      return;
    }
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
      if (!this.destroyed) { await this.clearDraft(); this.created.emit({ id: this.productId!, name: this.draft.name.trim(), description: this.draft.description.trim(), sku: this.draft.sku, active: this.publish }); }
    } catch (e) {
      this.error = this.message(e, this.t('saveFailure')) + (this.productId ? this.t('savedDraftRetry') : this.t('checkDetailsRetry'));
    } finally { this.busy = false; this.progress = ''; }
  }
  private message(error: unknown, fallback: string): string { return error instanceof HttpErrorResponse ? error.error?.message ?? error.error?.detail ?? fallback : fallback; }
}
