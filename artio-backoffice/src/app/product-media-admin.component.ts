
import { HttpClient } from '@angular/common/http';
import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges, inject, ChangeDetectionStrategy, ElementRef, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { backofficeText, currentBackofficeLocale, BackofficeTextKey } from './backoffice-locale';
import { productMediaText, ProductMediaLocaleKey } from './product-media-locale';

type ProductMedia = { id: string; altText: string; position: number; contentUrl: string; width: number; height: number };

@Component({
    selector: 'artio-product-media-admin',
    imports: [FormsModule],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `
    <dialog #mediaDialog class="workspace-dialog media-dialog" aria-labelledby="product-media-heading" (close)="close.emit()"><section class="workspace-dialog-panel" aria-labelledby="product-media-heading">
      <div class="panel-heading">
        <div><p class="eyebrow">{{m('eyebrow')}}</p><h2 id="product-media-heading">{{productName}} {{m('titleSuffix')}}</h2></div>
        <button type="button" class="link-button" (click)="close.emit()">{{m('close')}}</button>
      </div>
      @if (storageLoading) { <p class="muted loading-indicator" role="status">{{m('loading')}}</p> }
      @if (!storageLoading && !uploadAvailable) { <div class="notice"><p>{{m('storagePluginRequired')}}</p><button type="button" class="link-button" (click)="installStoragePlugin.emit()">{{m('installStoragePlugin')}}</button></div> }
      @if (!storageLoading && uploadAvailable) { <form class="create-form" (ngSubmit)="upload(uploadInput)">
        <label>{{m('imageFile')}}<input #uploadInput type="file" accept="image/jpeg,image/png,.jpg,.jpeg,.png" (change)="selectFile($event)" required></label>
        <label>{{m('alternativeText')}}<input [(ngModel)]="altText" name="altText" maxlength="500" [placeholder]="m('alternativeTextPlaceholder')"></label>
        <button class="primary" [disabled]="!file || busy">{{busy?m('uploading'):m('upload')}}</button>
      </form> }
      @if (loading) { <p class="muted loading-indicator" role="status">{{m('loading')}}</p> }
      @if (error) {
        <p class="error" role="alert">{{error}}</p>
      }
      @if (items.length) {
        <div class="product-media-grid">
          @for (item of items; track item; let index = $index) {
            <article class="product-media-card">
              <img [src]="item.contentUrl" [alt]="item.altText" width="240" height="180" loading="lazy">
            <p>{{item.altText || m('noAlternativeText')}} · {{item.width}}×{{item.height}}</p>
              <div class="row">
                @if (uploadAvailable) { <label class="link-button">{{m('replace')}}<input type="file" accept="image/jpeg,image/png,.jpg,.jpeg,.png" hidden (change)="replace(item,$event)"></label> }
                <button type="button" class="link-button" [disabled]="index===0" [attr.aria-label]="m('moveEarlier',{index:(index+1).toString()})" (click)="move(index,-1)">{{m('earlier')}}</button>
                <button type="button" class="link-button" [disabled]="index===items.length-1" [attr.aria-label]="m('moveLater',{index:(index+1).toString()})" (click)="move(index,1)">{{m('later')}}</button>
                <button type="button" class="link-button danger" [attr.aria-label]="m('deleteImage',{index:(index+1).toString()})" (click)="remove(item)">{{m('delete')}}</button>
              </div>
            </article>
          }
        </div>
      }
      @if (!loading && !error && !items.length) {
        <p class="empty">{{m('empty')}}</p>
      }
      @if (deleteTarget) {
        <dialog #deleteDialog class="refund-dialog" aria-labelledby="delete-media-title" (cancel)="cancelDeleteDialog($event)" (close)="deleteDialogClosed()">
          <form class="refund-dialog-content" (ngSubmit)="confirmDelete()">
            <p class="eyebrow">{{t('deleteMediaEyebrow')}}</p>
            <h2 id="delete-media-title">{{t('deleteMediaTitle')}}</h2>
            <p class="muted">{{t('deleteMediaDescription')}}</p>
            <p class="refund-payment-summary"><strong>{{deleteTarget.altText||t('deleteMediaNoAlt')}}</strong></p>
            @if (deleteError) { <p class="error" role="alert">{{deleteError}}</p> }
            @if (deleteBusy) { <p class="muted" role="status">{{t('deleteMediaBusy')}}</p> }
            <div class="refund-dialog-actions">
              <button type="button" class="secondary" [disabled]="deleteBusy" (click)="closeDeleteDialog()">{{t('refundCancel')}}</button>
              <button type="submit" class="primary" [disabled]="deleteBusy">{{t('deleteMediaConfirm')}}</button>
            </div>
          </form>
        </dialog>
      }
    </section></dialog>`
})
export class ProductMediaAdminComponent implements OnChanges {
  @ViewChild('mediaDialog') private mediaDialogRef?:ElementRef<HTMLDialogElement>;
  @ViewChild('deleteDialog') private deleteDialogRef?:ElementRef<HTMLDialogElement>;
  t(key:BackofficeTextKey):string{return backofficeText(currentBackofficeLocale(),key)}
  m(key:ProductMediaLocaleKey,values:Record<string,string>={}):string{return productMediaText(currentBackofficeLocale(),key,values)}
  @Input({required:true}) productId='';
  @Input() productName='';
  @Output() close = new EventEmitter<void>();
  @Output() installStoragePlugin = new EventEmitter<void>();
  private http=inject(HttpClient);
  items:ProductMedia[]=[];
  file:File|null=null;
  altText='';
  error='';
  loading=false;
  storageLoading=false;
  uploadAvailable=false;
  busy=false;
  deleteTarget:ProductMedia|null=null;
  deleteBusy=false;
  deleteError='';

  ngOnChanges(changes:SimpleChanges):void { if(changes['productId']&&this.productId){this.load();setTimeout(()=>{const dialog=this.mediaDialogRef?.nativeElement;if(dialog&&!dialog.open)dialog.showModal()},0)} }
  selectFile(event:Event):void { const input=event.target as HTMLInputElement; this.file=input.files?.[0]??null; this.error=''; }
  load():void { this.loading=true;this.storageLoading=true;this.error='';this.http.get<{uploadAvailable:boolean}>('/api/v1/admin/catalog/media/storage-status').subscribe({next:status=>{this.uploadAvailable=status.uploadAvailable;this.storageLoading=false},error:()=>{this.uploadAvailable=false;this.storageLoading=false}});this.http.get<ProductMedia[]>(`/api/v1/products/${this.productId}/media`).subscribe({next:items=>{this.items=items;this.loading=false},error:e=>{this.loading=false;this.error=e.error?.message??e.error?.detail??this.m('loadFailure')}}); }
  upload(input:HTMLInputElement):void {
    if(this.busy)return;
    if(!this.uploadAvailable){this.error=this.m('storagePluginRequired');return;}
    if(!this.file)return;
    if(this.file.size>10*1024*1024){this.error=this.m('uploadLimit');return;}
    if(!this.isSupported(this.file)){this.error=this.m('unsupported');return;}
    const form=new FormData();form.append('file',this.file,this.file.name);
    this.busy=true;
    this.http.post<ProductMedia>(`/api/v1/products/${this.productId}/media?altText=${encodeURIComponent(this.altText)}`,form).subscribe({
      next:()=>{this.busy=false;this.file=null;input.value='';this.altText='';this.error='';this.load()},
      error:e=>{this.busy=false;this.error=e.error?.message??e.error?.detail??this.m('uploadFailure')}
    });
  }
  replace(item:ProductMedia,event:Event):void {
    const input=event.target as HTMLInputElement;const file=input.files?.[0];input.value='';if(!file)return;if(!this.uploadAvailable){this.error=this.m('storagePluginRequired');return;}
    if(file.size>10*1024*1024){this.error=this.m('uploadLimit');return;}
    if(!this.isSupported(file)){this.error=this.m('unsupported');return;}
    const form=new FormData();form.append('file',file,file.name);
    this.http.put<ProductMedia>(`/api/v1/products/${this.productId}/media/${item.id}?altText=${encodeURIComponent(item.altText)}`,form).subscribe({next:()=>this.load(),error:e=>this.error=e.error?.message??e.error?.detail??this.m('replaceFailure')});
  }
  private isSupported(file:File):boolean { return ['image/jpeg','image/png'].includes(file.type)||/\.(jpe?g|png)$/i.test(file.name); }
  move(index:number,delta:-1|1):void {
    const reordered=[...this.items];const target=index+delta;[reordered[index],reordered[target]]=[reordered[target],reordered[index]];
    this.http.put<ProductMedia[]>(`/api/v1/products/${this.productId}/media/order`,{mediaIds:reordered.map(item=>item.id)}).subscribe({next:items=>this.items=items,error:e=>this.error=e.error?.message??e.error?.detail??this.m('reorderFailure')});
  }
  remove(item:ProductMedia):void {
    this.deleteTarget=item;this.deleteBusy=false;this.deleteError='';setTimeout(()=>{const dialog=this.deleteDialogRef?.nativeElement;if(this.deleteTarget?.id===item.id&&dialog&&!dialog.open)dialog.showModal()},0);
  }
  cancelDeleteDialog(event:Event):void{if(this.deleteBusy)event.preventDefault()}
  deleteDialogClosed():void{if(this.deleteBusy)return;this.deleteTarget=null;this.deleteError=''}
  closeDeleteDialog():void{if(!this.deleteBusy)this.deleteDialogRef?.nativeElement.close()}
  confirmDelete():void{const item=this.deleteTarget;if(!item||this.deleteBusy)return;this.deleteBusy=true;this.deleteError='';this.http.delete(`/api/v1/products/${this.productId}/media/${item.id}`).subscribe({next:()=>{this.deleteBusy=false;this.deleteDialogRef?.nativeElement.close();this.load()},error:e=>{this.deleteBusy=false;this.deleteError=e.error?.message??e.error?.detail??this.t('deleteMediaFailure')}})}
}
