import { CommonModule } from '@angular/common';
import { Component, ElementRef, EventEmitter, Input, Output, ViewChild } from '@angular/core';
import { currentBackofficeLocale } from './backoffice-locale';
import { dataTransferText, type DataTransferTextKey } from './data-transfer-admin-locale';

export type DataTransferAction={type:'export'|'import';event?:Event}|{type:'dismissReport'};

@Component({
  selector:'artio-data-transfer-admin',
  standalone:true,
  imports:[CommonModule],
  template:`
    <section class="data-transfer-page">
      <div class="page-intro"><div><p class="eyebrow">{{t('eyebrow')}}</p><h2>{{t('title')}}</h2><p>{{t('intro')}}</p></div></div>
      @if(error){<p class="notice error" role="alert">{{error}}</p>}
      <div class="data-transfer-grid">
        <article class="panel data-transfer-card"><div class="data-transfer-icon" aria-hidden="true">⇧</div><p class="eyebrow">{{t('products')}}</p><h3>{{t('import')}}</h3><p class="muted">{{t('productsHelp')}}</p><p class="data-transfer-format">{{t('format')}}</p><input #fileInput type="file" accept=".csv,text/csv" hidden (change)="emit({type:'import',event:$event})"><div class="data-transfer-actions"><button type="button" class="primary" [disabled]="importing" (click)="fileInput.click()">{{importing?t('importing'):t('chooseFile')}}</button><span class="muted">{{selectedFile||t('noFile')}}</span></div>@if(importing){<p class="muted loading-indicator" role="status" aria-live="polite">{{t('importing')}}</p>}</article>
        <article class="panel data-transfer-card"><div class="data-transfer-icon" aria-hidden="true">⇩</div><p class="eyebrow">{{t('products')}}</p><h3>{{t('export')}}</h3><p class="muted">{{t('productsHelp')}}</p><p class="data-transfer-format">{{t('format')}}</p><div class="data-transfer-actions"><button type="button" class="secondary" [disabled]="exporting" (click)="emit({type:'export'})">{{exporting?t('exporting'):t('export')}}</button></div>@if(exporting){<p class="muted loading-indicator" role="status" aria-live="polite">{{t('exporting')}}</p>}</article>
      </div>
      <p class="muted data-transfer-note">{{t('noOtherTypes')}}</p>
      @if(report){<section class="panel data-transfer-report"><div class="panel-heading"><div><p class="eyebrow">{{t('report')}}</p><h3>{{t('products')}}</h3></div><button type="button" class="link-button" (click)="emit({type:'dismissReport'})">{{t('dismiss')}}</button></div><p class="muted">{{format('processed',{rows:report.rows,created:report.created,updated:report.updated,failed:report.failed})}}</p>@for(row of report.errors??[];track $index){<div class="row"><span class="row-main"><strong>{{format('row',{row:row.row})}} · {{row.sku||t('noSku')}}</strong><small>{{row.reason}}</small></span></div>}</section>}
    </section>
  `
})
export class DataTransferAdminComponent{
  @ViewChild('fileInput') fileInput?:ElementRef<HTMLInputElement>;
  @Input() report:any=null;@Input() error='';@Input() importing=false;@Input() exporting=false;
  @Output() action=new EventEmitter<DataTransferAction>();
  selectedFile='';
  t(key:DataTransferTextKey):string{return dataTransferText(currentBackofficeLocale(),key)}
  format(key:DataTransferTextKey,values:Record<string,string|number>):string{return Object.entries(values).reduce((text,[name,value])=>text.replaceAll(`{${name}}`,String(value)),this.t(key))}
  emit(action:DataTransferAction):void{if(action.type==='import'&&action.event){const input=action.event.target as HTMLInputElement;this.selectedFile=input.files?.[0]?.name??''}this.action.emit(action)}
}
