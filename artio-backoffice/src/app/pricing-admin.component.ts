import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { currentBackofficeLocale } from './backoffice-locale';
import { pricingAdminText, type PricingAdminTextKey } from './pricing-admin-locale';
import { SearchSelectComponent, type SelectOption } from './search-select.component';
import { SellableSelectComponent } from './sellable-select.component';

export type PricingAdminAction={type:'togglePrice'|'createPrice'|'assignTaxCategory'};

@Component({selector:'artio-pricing-admin',imports:[CommonModule,FormsModule,SearchSelectComponent,SellableSelectComponent],template:`
  <section><div class="toolbar"><p>{{t('intro')}}</p><button type="button" class="primary" (click)="emit({type:'togglePrice'})">＋ {{t('addPrice')}}</button></div>
    @if(showPrice){<form class="create-form" (ngSubmit)="emit({type:'createPrice'})"><artio-sellable-select [(ngModel)]="priceDraft.sellableId" name="sellable" [label]="t('pricedVariant')"></artio-sellable-select><label>{{t('amount')}}<input [(ngModel)]="priceDraft.amount" name="amount" type="number" min="0.01" step="0.01" required></label><label>{{t('currency')}}<artio-search-select [(ngModel)]="priceDraft.currency" name="currency" [label]="t('priceCurrency')" [options]="currencyOptions"></artio-search-select></label><label>{{t('market')}}<input [(ngModel)]="priceDraft.market" name="market" [placeholder]="t('marketExample')" maxlength="2" required></label><label>{{t('channel')}}<input [(ngModel)]="priceDraft.channel" name="channel" [placeholder]="t('channelExample')" maxlength="64" required></label><label><input type="checkbox" [(ngModel)]="priceDraft.pricesIncludeTax" name="pricesIncludeTax"> {{t('taxInclusive')}}</label><button class="primary">{{t('savePrice')}}</button></form>}
    <div class="panel table-panel"><table><thead><tr><th scope="col">{{t('sellable')}}</th><th scope="col">{{t('marketChannel')}}</th><th scope="col">{{t('price')}}</th><th scope="col">{{t('taxMode')}}</th><th scope="col">{{t('status')}}</th></tr></thead><tbody>@for(p of prices;track p.id){<tr><td>{{p.sellableId}}</td><td>{{p.market}} / {{p.channel}}</td><td>{{p.amount}} {{p.currency}}</td><td>{{p.pricesIncludeTax?t('inclusive'):t('exclusive')}}</td><td>{{p.active?t('active'):t('inactive')}}</td></tr>}@empty{<tr><td colspan="5" class="empty">{{t('noPrices')}}</td></tr>}</tbody></table></div>
    <section class="panel"><div class="panel-heading"><div><p class="eyebrow">{{t('taxPolicy')}}</p><h2>{{t('sellableCategories')}}</h2></div></div><p class="muted">{{t('taxGuidance')}}</p><form class="create-form" (ngSubmit)="emit({type:'assignTaxCategory'})"><artio-sellable-select [(ngModel)]="taxAssignment.sellableId" name="taxSellableId" [label]="t('taxVariant')"></artio-sellable-select><label>{{t('category')}}<select [(ngModel)]="taxAssignment.category" name="taxCategory"><option value="STANDARD">{{t('standard')}}</option><option value="REDUCED">{{t('reduced')}}</option><option value="ACCOMMODATION">{{t('accommodation')}}</option></select></label><button class="primary">{{t('assignCategory')}}</button></form>
      @if(taxRules.length){<div class="table-panel"><table><thead><tr><th scope="col">{{t('jurisdiction')}}</th><th scope="col">{{t('category')}}</th><th scope="col">{{t('rate')}}</th><th scope="col">{{t('validFrom')}}</th><th scope="col">{{t('status')}}</th></tr></thead><tbody>@for(rule of taxRules;track rule.id){<tr><td>{{rule.jurisdiction}}</td><td>{{rule.category}}</td><td>{{rule.percentage}}%</td><td>{{rule.validFrom}}</td><td>{{rule.active?t('active'):t('inactive')}}</td></tr>}</tbody></table></div>}@else{<p class="empty" role="status">{{t('noRules')}}</p>}
      @if(sellableTaxCategories.length){<div class="table-panel"><table><thead><tr><th scope="col">{{t('sellable')}}</th><th scope="col">{{t('assignedCategory')}}</th></tr></thead><tbody>@for(assignment of sellableTaxCategories;track assignment.sellableId){<tr><td>{{assignment.sellableId}}</td><td>{{assignment.category}}</td></tr>}</tbody></table></div>}@else{<p class="empty" role="status">{{t('noAssignments')}}</p>}
    </section>
  </section>
`})
export class PricingAdminComponent{
 @Input() showPrice=false;@Input() priceDraft:any={};@Input() prices:any[]=[];@Input() taxRules:any[]=[];@Input() sellableTaxCategories:any[]=[];@Input() taxAssignment:any={};@Input() currencyOptions:SelectOption[]=[];
 @Output() action=new EventEmitter<PricingAdminAction>();
 t(key:PricingAdminTextKey):string{return pricingAdminText(currentBackofficeLocale(),key)}
 emit(action:PricingAdminAction):void{this.action.emit(action)}
}
