import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { currentBackofficeLocale } from './backoffice-locale';
import { bundleAdminText, type BundleAdminTextKey } from './bundle-admin-locale';
import { SearchSelectComponent, type SelectOption } from './search-select.component';
import { SellableSelectComponent } from './sellable-select.component';

export type BundleAdminAction =
  | { type: 'toggleEditor' | 'create' | 'addGroup' | 'addMarketPrice' | 'saveTranslations' | 'closeTranslations' }
  | { type: 'bundleTypeChanged' }
  | { type: 'removeGroup' | 'removeMarketPrice'; index: number }
  | { type: 'activate'; bundle: any }
  | { type: 'editTranslations'; bundle: any }
  | { type: 'localeChanged'; locale: string };

@Component({
  selector: 'artio-bundle-admin',
  imports: [CommonModule, FormsModule, SearchSelectComponent, SellableSelectComponent],
  template: `
    <section><div class="toolbar"><p>{{t('intro')}}</p><button type="button" class="primary" (click)="emit({type:'toggleEditor'})">＋ {{t('newBundle')}}</button></div>
      @if (showBundle) {
        <form class="create-form" (ngSubmit)="emit({type:'create'})">
          <input [(ngModel)]="bundleDraft.name" name="bundleName" [placeholder]="t('name')" maxlength="200" required>
          <input [(ngModel)]="bundleDraft.sku" name="bundleSku" [placeholder]="t('sku')" maxlength="64" required>
          <label>{{t('type')}}<select [(ngModel)]="bundleDraft.type" name="bundleType" (ngModelChange)="emit({type:'bundleTypeChanged'})"><option value="CONFIGURABLE">{{t('configurable')}}</option><option value="FIXED">{{t('fixed')}}</option></select></label>
          @if (bundleDraft.type==='FIXED') {
            <artio-sellable-select [ngModel]="bundleSellables" (ngModelChange)="bundleSellablesChange.emit($event)" name="fixedSellables" [label]="t('fixedProducts')" [multiple]="true"></artio-sellable-select>
          }
          @if (bundleDraft.type==='CONFIGURABLE') {
            @for (group of bundleGroups; track group; let i = $index) {
              <div class="panel"><div class="panel-heading"><div><p class="eyebrow">{{format('group',{number:i+1})}}</p><h3>{{group.name||t('newGroup')}}</h3></div><button type="button" class="link-button" (click)="emit({type:'removeGroup',index:i})" [disabled]="bundleGroups.length===1">{{t('removeGroup')}}</button></div>
                <div class="form-grid"><label>{{t('groupCode')}}<input [(ngModel)]="group.code" [name]="'bundleGroupCode'+i" pattern="[a-z][a-z0-9_-]{1,63}" maxlength="64" required></label><label>{{t('customerLabel')}}<input [(ngModel)]="group.name" [name]="'bundleGroupName'+i" maxlength="200" required></label><label>{{t('minChoices')}}<input [(ngModel)]="group.minimumSelections" [name]="'bundleGroupMin'+i" type="number" min="0" [max]="group.sellables.length" required></label><label>{{t('maxChoices')}}<input [(ngModel)]="group.maximumSelections" [name]="'bundleGroupMax'+i" type="number" min="0" [max]="group.sellables.length" required></label></div>
                <artio-sellable-select [(ngModel)]="group.sellables" [name]="'bundleGroupSellables'+i" [label]="format('choices',{group:group.name||t('newGroup')})" [multiple]="true"></artio-sellable-select>
              </div>
            }
            <button type="button" class="link-button" (click)="emit({type:'addGroup'})">＋ {{t('addGroup')}}</button>
          }
          <label>{{t('pricing')}}<select [(ngModel)]="bundleDraft.pricingMode" name="bundlePricingMode"><option value="COMPONENT_SUM">{{t('componentSum')}}</option><option value="FIXED_PRICE">{{t('fixedPrice')}}</option><option value="PERCENTAGE_OFF_COMPONENTS">{{t('percentage')}}</option></select></label>
          @if (bundleDraft.pricingMode==='FIXED_PRICE') {
            <div class="form-grid"><label>{{t('fixedAmount')}}<input [(ngModel)]="bundleDraft.fixedAmount" name="bundleFixedAmount" type="number" min="0.01" step="0.01" required></label><label>{{t('currency')}}<artio-search-select [(ngModel)]="bundleDraft.currency" name="bundleCurrency" [label]="t('fixedCurrency')" [options]="currencyOptions"></artio-search-select></label></div>
            <div class="panel"><p class="muted">{{t('marketHelp')}}</p>@for (price of bundleMarketPrices; track price; let i = $index) {
              <div class="form-grid"><label>{{t('market')}}<input [(ngModel)]="price.market" [name]="'bundleMarket'+i" pattern="[A-Za-z]{2}" maxlength="2" required></label><label>{{t('amount')}}<input [(ngModel)]="price.amount" [name]="'bundleMarketAmount'+i" type="number" min="0.01" step="0.01" required></label><label>{{t('currency')}}<artio-search-select [(ngModel)]="price.currency" [name]="'bundleMarketCurrency'+i" [label]="t('marketCurrency')" [options]="currencyOptions"></artio-search-select></label><button type="button" class="link-button" (click)="emit({type:'removeMarketPrice',index:i})">{{t('remove')}}</button></div>
            }<button type="button" class="link-button" (click)="emit({type:'addMarketPrice'})">＋ {{t('addMarket')}}</button></div>
          }
          @if (bundleDraft.pricingMode==='PERCENTAGE_OFF_COMPONENTS') { <label>{{t('discount')}}<input [(ngModel)]="bundleDraft.discountPercentage" name="bundleDiscount" type="number" min="0" max="100" step="0.01" required></label> }
          <button class="primary">{{t('create')}}</button>
        </form>
      }
      <div class="bundle-list">@for (bundle of bundles; track bundle.id) {
        <article><div class="bundle-mark" aria-hidden="true">✳</div><p class="eyebrow">{{bundle.type}} · {{bundle.pricingRule.mode}}</p><h2>{{bundle.name}}</h2><small>{{bundle.type==='FIXED'?format('componentsFixed',{count:bundle.components.length}):format('choiceGroups',{count:bundle.optionGroups.length})}}</small><span class="pill" [class.inactive]="!bundle.active">{{bundle.active?t('active'):t('inactive')}}</span><button type="button" class="link-button" (click)="emit({type:'activate',bundle})">{{bundle.active?t('deactivate'):t('activate')}}</button><button type="button" class="link-button" (click)="emit({type:'editTranslations',bundle})">{{t('translations')}}</button></article>
      }@empty { <p class="empty" role="status">{{t('empty')}}</p> }</div>
      @if (bundleTranslationBundle) {
        <div class="panel"><div class="panel-heading"><div><p class="eyebrow">{{t('localized')}}</p><h2>{{bundleTranslationBundle.name}}</h2></div><button type="button" class="link-button" (click)="emit({type:'closeTranslations'})">{{t('close')}}</button></div>
          <form class="create-form" (ngSubmit)="emit({type:'saveTranslations'})"><select [ngModel]="bundleTranslationLocale" name="bundleTranslationLocale" (ngModelChange)="emit({type:'localeChanged',locale:$event})"><option value="de">{{t('german')}} · de</option><option value="it">{{t('italian')}} · it</option><option value="fr">{{t('french')}} · fr</option><option value="en">{{t('english')}} · en</option></select><input [(ngModel)]="bundleTranslationDraft.name" name="localizedBundleName" [placeholder]="t('translatedName')" required>@for (group of bundleTranslationBundle.optionGroups; track group.code) {
            <label>{{group.name}}<input [(ngModel)]="bundleTranslationDraft.optionGroupNames[group.code]" [name]="'bundle-translation-'+group.code" [placeholder]="t('groupLabel')" required></label>
          }<button class="primary">{{t('saveTranslations')}}</button></form>
        </div>
      }
    </section>
  `
})
export class BundleAdminComponent {
  @Input() bundles: any[] = [];
  @Input() showBundle = false;
  @Input() bundleDraft: any = {};
  @Input() bundleGroups: any[] = [];
  @Input() bundleSellables: string[] = [];
  @Input() bundleMarketPrices: any[] = [];
  @Input() currencyOptions: SelectOption[] = [];
  @Input() bundleTranslationBundle: any = null;
  @Input() bundleTranslationLocale = 'de';
  @Input() bundleTranslationDraft: any = { name: '', optionGroupNames: {} };
  @Output() action = new EventEmitter<BundleAdminAction>();
  @Output() bundleSellablesChange = new EventEmitter<string[]>();

  t(key: BundleAdminTextKey): string { return bundleAdminText(currentBackofficeLocale(), key); }
  format(key: BundleAdminTextKey, values: Record<string, string | number>): string {
    return Object.entries(values).reduce((result, [name, value]) => result.replaceAll(`{${name}}`, String(value)), this.t(key));
  }
  emit(action: BundleAdminAction): void { this.action.emit(action); }
}
