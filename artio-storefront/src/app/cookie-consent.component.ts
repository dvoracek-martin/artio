import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LocaleService } from './locale.service';

type Consent={essential:true;analytics:boolean;marketing:boolean;policyVersion:string;updatedAt:string};
@Component({selector:'artio-cookie-consent',standalone:true,imports:[CommonModule,FormsModule],template:`
<section class="consent-panel" *ngIf="visible" aria-labelledby="consent-title" role="dialog" aria-modal="false">
 <div class="consent-copy"><p class="eyebrow">{{t('privacyEyebrow')}}</p><h2 id="consent-title">{{t('consentTitle')}}</h2><p>{{t('consentBody')}} <a href="/privacy">{{t('privacyNotice')}}</a></p></div>
 <div class="consent-options"><label><input type="checkbox" checked disabled> {{t('essential')}}</label><label><input type="checkbox" [(ngModel)]="analytics"> {{t('analytics')}}</label><label><input type="checkbox" [(ngModel)]="marketing"> {{t('marketing')}}</label></div>
 <div class="consent-actions"><button type="button" class="consent-secondary" (click)="reject()">{{t('reject')}}</button><button type="button" class="consent-primary" (click)="save()">{{t('accept')}}</button></div>
</section>
<button *ngIf="!visible" class="privacy-launcher" type="button" (click)="visible=true">{{t('manage')}}</button>
`})
export class CookieConsentComponent {
 private http=inject(HttpClient);private locale=inject(LocaleService);visible=false;analytics=false;marketing=false;
 constructor(){const raw=localStorage.getItem('artio-consent-v1');if(!raw)this.visible=true;else try{const p=JSON.parse(raw);this.analytics=!!p.analytics;this.marketing=!!p.marketing;}catch{this.visible=true;}}
 t(key:string):string{return this.locale.translate(key);}
 reject():void{this.analytics=false;this.marketing=false;this.save();}
 save():void{const value:Consent={essential:true,analytics:this.analytics,marketing:this.marketing,policyVersion:'2026-10',updatedAt:new Date().toISOString()};localStorage.setItem('artio-consent-v1',JSON.stringify(value));const token=localStorage.getItem('artio-token');if(token)this.http.post('/api/v1/privacy/consent',value).subscribe({error:()=>undefined});this.visible=false;}
}
