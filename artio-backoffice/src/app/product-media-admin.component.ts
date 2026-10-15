
import { HttpClient } from '@angular/common/http';
import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges, inject, ChangeDetectionStrategy, ElementRef, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { backofficeText, currentBackofficeLocale, BackofficeTextKey } from './backoffice-locale';

type ProductMedia = { id: string; altText: string; position: number; contentUrl: string; width: number; height: number };

@Component({
    selector: 'artio-product-media-admin',
    imports: [FormsModule],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `
    <section class="panel" aria-labelledby="product-media-heading">
      <div class="panel-heading">
        <div><p class="eyebrow">CATALOG MEDIA</p><h2 id="product-media-heading">{{productName}} images</h2></div>
        <button type="button" class="link-button" (click)="close.emit()">Close</button>
      </div>
      <form class="create-form" (ngSubmit)="upload(uploadInput)">
        <label>Image file<input #uploadInput type="file" accept="image/jpeg,image/png,.jpg,.jpeg,.png" (change)="selectFile($event)" required></label>
        <label>Alternative text<input [(ngModel)]="altText" name="altText" maxlength="500" placeholder="Describe the image"></label>
        <button class="primary" [disabled]="!file || busy">{{busy?'Uploading…':'Upload image'}}</button>
      </form>
      @if (error) {
        <p class="error" role="alert">{{error}}</p>
      }
      @if (items.length) {
        <div class="product-media-grid">
          @for (item of items; track item; let index = $index) {
            <article class="product-media-card">
              <img [src]="item.contentUrl" [alt]="item.altText" width="240" height="180" loading="lazy">
              <p>{{item.altText || 'No alternative text'}} · {{item.width}}×{{item.height}}</p>
              <div class="row">
                <label class="link-button">Replace<input type="file" accept="image/jpeg,image/png,.jpg,.jpeg,.png" hidden (change)="replace(item,$event)"></label>
                <button type="button" class="link-button" [disabled]="index===0" [attr.aria-label]="'Move image '+(index+1)+' earlier'" (click)="move(index,-1)">← Earlier</button>
                <button type="button" class="link-button" [disabled]="index===items.length-1" [attr.aria-label]="'Move image '+(index+1)+' later'" (click)="move(index,1)">Later →</button>
                <button type="button" class="link-button danger" [attr.aria-label]="'Delete image '+(index+1)" (click)="remove(item)">Delete</button>
              </div>
            </article>
          }
        </div>
      }
      @if (!items.length) {
        <p class="empty">No product images yet.</p>
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
    </section>`
})
export class ProductMediaAdminComponent implements OnChanges {
  @ViewChild('deleteDialog') private deleteDialogRef?:ElementRef<HTMLDialogElement>;
  t(key:BackofficeTextKey):string{return backofficeText(currentBackofficeLocale(),key)}
  @Input({required:true}) productId='';
  @Input() productName='';
  @Output() close = new EventEmitter<void>();
  private http=inject(HttpClient);
  items:ProductMedia[]=[];
  file:File|null=null;
  altText='';
  error='';
  busy=false;
  deleteTarget:ProductMedia|null=null;
  deleteBusy=false;
  deleteError='';

  ngOnChanges(changes:SimpleChanges):void { if(changes['productId']&&this.productId)this.load(); }
  selectFile(event:Event):void { const input=event.target as HTMLInputElement; this.file=input.files?.[0]??null; this.error=''; }
  load():void { this.http.get<ProductMedia[]>(`/api/v1/products/${this.productId}/media`).subscribe({next:items=>this.items=items,error:e=>this.error=e.error?.message??e.error?.detail??'Could not load product images.'}); }
  upload(input:HTMLInputElement):void {
    if(this.busy)return;
    if(!this.file)return;
    if(this.file.size>10*1024*1024){this.error='Image uploads are limited to 10 MiB.';return;}
    if(!this.isSupported(this.file)){this.error='Choose a JPEG or PNG image.';return;}
    const form=new FormData();form.append('file',this.file,this.file.name);
    this.busy=true;
    this.http.post<ProductMedia>(`/api/v1/products/${this.productId}/media?altText=${encodeURIComponent(this.altText)}`,form).subscribe({
      next:()=>{this.busy=false;this.file=null;input.value='';this.altText='';this.error='';this.load()},
      error:e=>{this.busy=false;this.error=e.error?.message??e.error?.detail??'Could not upload the image.'}
    });
  }
  replace(item:ProductMedia,event:Event):void {
    const input=event.target as HTMLInputElement;const file=input.files?.[0];input.value='';if(!file)return;
    if(file.size>10*1024*1024){this.error='Image uploads are limited to 10 MiB.';return;}
    if(!this.isSupported(file)){this.error='Choose a JPEG or PNG image.';return;}
    const form=new FormData();form.append('file',file,file.name);
    this.http.put<ProductMedia>(`/api/v1/products/${this.productId}/media/${item.id}?altText=${encodeURIComponent(item.altText)}`,form).subscribe({next:()=>this.load(),error:e=>this.error=e.error?.message??e.error?.detail??'Could not replace the image.'});
  }
  private isSupported(file:File):boolean { return ['image/jpeg','image/png'].includes(file.type)||/\.(jpe?g|png)$/i.test(file.name); }
  move(index:number,delta:-1|1):void {
    const reordered=[...this.items];const target=index+delta;[reordered[index],reordered[target]]=[reordered[target],reordered[index]];
    this.http.put<ProductMedia[]>(`/api/v1/products/${this.productId}/media/order`,{mediaIds:reordered.map(item=>item.id)}).subscribe({next:items=>this.items=items,error:e=>this.error=e.error?.message??e.error?.detail??'Could not reorder images.'});
  }
  remove(item:ProductMedia):void {
    this.deleteTarget=item;this.deleteBusy=false;this.deleteError='';setTimeout(()=>{const dialog=this.deleteDialogRef?.nativeElement;if(this.deleteTarget?.id===item.id&&dialog&&!dialog.open)dialog.showModal()},0);
  }
  cancelDeleteDialog(event:Event):void{if(this.deleteBusy)event.preventDefault()}
  deleteDialogClosed():void{if(this.deleteBusy)return;this.deleteTarget=null;this.deleteError=''}
  closeDeleteDialog():void{if(!this.deleteBusy)this.deleteDialogRef?.nativeElement.close()}
  confirmDelete():void{const item=this.deleteTarget;if(!item||this.deleteBusy)return;this.deleteBusy=true;this.deleteError='';this.http.delete(`/api/v1/products/${this.productId}/media/${item.id}`).subscribe({next:()=>{this.deleteBusy=false;this.deleteDialogRef?.nativeElement.close();this.load()},error:e=>{this.deleteBusy=false;this.deleteError=e.error?.message??e.error?.detail??this.t('deleteMediaFailure')}})}
}
