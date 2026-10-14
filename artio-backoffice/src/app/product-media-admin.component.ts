import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';

type ProductMedia = { id: string; altText: string; position: number; contentUrl: string; width: number; height: number };

@Component({
  selector: 'artio-product-media-admin',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <section class="panel" aria-labelledby="product-media-heading">
      <div class="panel-heading">
        <div><p class="eyebrow">CATALOG MEDIA</p><h2 id="product-media-heading">{{productName}} images</h2></div>
        <button type="button" class="link-button" (click)="close.emit()">Close</button>
      </div>
      <form class="create-form" (ngSubmit)="upload()">
        <label>Image file<input type="file" accept="image/jpeg,image/png,.jpg,.jpeg,.png" (change)="selectFile($event)" required></label>
        <label>Alternative text<input [(ngModel)]="altText" name="altText" maxlength="500" placeholder="Describe the image"></label>
        <button class="primary" [disabled]="!file || busy">{{busy?'Uploading…':'Upload image'}}</button>
      </form>
      <p class="error" *ngIf="error" role="alert">{{error}}</p>
      <div class="product-media-grid" *ngIf="items.length">
        <article class="product-media-card" *ngFor="let item of items; let index=index">
          <img [src]="item.contentUrl" [alt]="item.altText" width="240" height="180" loading="lazy">
          <p>{{item.altText || 'No alternative text'}} · {{item.width}}×{{item.height}}</p>
          <div class="row">
            <label class="link-button">Replace<input type="file" accept="image/jpeg,image/png,.jpg,.jpeg,.png" hidden (change)="replace(item,$event)"></label>
            <button type="button" class="link-button" [disabled]="index===0" [attr.aria-label]="'Move image '+(index+1)+' earlier'" (click)="move(index,-1)">← Earlier</button>
            <button type="button" class="link-button" [disabled]="index===items.length-1" [attr.aria-label]="'Move image '+(index+1)+' later'" (click)="move(index,1)">Later →</button>
            <button type="button" class="link-button danger" [attr.aria-label]="'Delete image '+(index+1)" (click)="remove(item)">Delete</button>
          </div>
        </article>
      </div>
      <p class="empty" *ngIf="!items.length">No product images yet.</p>
    </section>`
})
export class ProductMediaAdminComponent implements OnChanges {
  @Input({required:true}) productId='';
  @Input() productName='';
  @Output() close = new EventEmitter<void>();
  private http=inject(HttpClient);
  items:ProductMedia[]=[];
  file:File|null=null;
  altText='';
  error='';
  busy=false;

  ngOnChanges(changes:SimpleChanges):void { if(changes['productId']&&this.productId)this.load(); }
  selectFile(event:Event):void { const input=event.target as HTMLInputElement; this.file=input.files?.[0]??null; input.value=''; this.error=''; }
  load():void { this.http.get<ProductMedia[]>(`/api/v1/products/${this.productId}/media`).subscribe({next:items=>this.items=items,error:e=>this.error=e.error?.detail??'Could not load product images.'}); }
  upload():void {
    if(!this.file)return;
    if(this.file.size>10*1024*1024){this.error='Image uploads are limited to 10 MiB.';return;}
    if(!this.isSupported(this.file)){this.error='Choose a JPEG or PNG image.';return;}
    const form=new FormData();form.append('file',this.file,this.file.name);
    this.busy=true;
    this.http.post<ProductMedia>(`/api/v1/products/${this.productId}/media?altText=${encodeURIComponent(this.altText)}`,form).subscribe({
      next:()=>{this.busy=false;this.file=null;this.altText='';this.load()},
      error:e=>{this.busy=false;this.error=e.error?.detail??'Could not upload the image.'}
    });
  }
  replace(item:ProductMedia,event:Event):void {
    const input=event.target as HTMLInputElement;const file=input.files?.[0];input.value='';if(!file)return;
    if(file.size>10*1024*1024){this.error='Image uploads are limited to 10 MiB.';return;}
    if(!this.isSupported(file)){this.error='Choose a JPEG or PNG image.';return;}
    const form=new FormData();form.append('file',file,file.name);
    this.http.put<ProductMedia>(`/api/v1/products/${this.productId}/media/${item.id}?altText=${encodeURIComponent(item.altText)}`,form).subscribe({next:()=>this.load(),error:e=>this.error=e.error?.detail??'Could not replace the image.'});
  }
  private isSupported(file:File):boolean { return ['image/jpeg','image/png'].includes(file.type)||/\.(jpe?g|png)$/i.test(file.name); }
  move(index:number,delta:-1|1):void {
    const reordered=[...this.items];const target=index+delta;[reordered[index],reordered[target]]=[reordered[target],reordered[index]];
    this.http.put<ProductMedia[]>(`/api/v1/products/${this.productId}/media/order`,{mediaIds:reordered.map(item=>item.id)}).subscribe({next:items=>this.items=items,error:e=>this.error=e.error?.detail??'Could not reorder images.'});
  }
  remove(item:ProductMedia):void {
    if(!window.confirm('Delete this product image?'))return;
    this.http.delete(`/api/v1/products/${this.productId}/media/${item.id}`).subscribe({next:()=>this.load(),error:e=>this.error=e.error?.detail??'Could not delete the image.'});
  }
}
