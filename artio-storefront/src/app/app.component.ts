import { CommonModule, DOCUMENT } from '@angular/common';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Component, inject, ChangeDetectionStrategy } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Meta, Title } from '@angular/platform-browser';
import { CookieConsentComponent } from './cookie-consent.component';
import { LocaleService, StoreLocale } from './locale.service';

type Variant = { id: string; name: string; active: boolean };
type Product = { id: string; name: string; description: string; sku: string; active: boolean; seoSlug?:string|null; seoTitle?:string|null; seoDescription?:string|null; media?:Array<{id:string;contentUrl:string;altText:string;position:number}>; variants?: Variant[] };
type ProductQuote = { unitListPrice:{amount:number|string;currency:string|{currencyCode:string}}; total:{amount:number|string;currency:string|{currencyCode:string}} };
type Bundle = { id: string; name: string; localizedName?:string; type: string; active: boolean; optionGroups?:Array<{code:string;name:string;localizedName?:string;minimumSelections:number;maximumSelections:number;choices:Array<{sellableId:string;quantity:number}>}> };
type CartItem = { variantId:string;quantity:number;unitPrice:number;currency:string;bundleId?:string };

@Component({
    selector: 'artio-root', imports: [CommonModule, FormsModule, CookieConsentComponent], changeDetection: ChangeDetectionStrategy.Eager,
 template: `
<header class="top"><a class="brand" href="/">artio<span>®</span></a><nav><a href="/shop">{{t("shop")}}</a><a href="/bundles">{{t("bundles")}}</a><a href="/cart">{{t("cart")}}</a><a href="/orders">{{t("orders")}}</a></nav><select class="locale-switch" [attr.aria-label]="t('language')" [(ngModel)]="locale" (change)="changeLocale()"><option value="en">EN</option><option value="de">DE</option><option value="fr">FR</option><option value="it">IT</option></select><div class="account">@if (!token) {
<a href="/login">{{t("signIn")}}</a>
}@if (token) {
<button class="text-button" (click)="logout()">{{t("signOut")}}</button>
}<a class="bag" href="/cart">{{t('bag')}} <b>{{ cartCount }}</b></a></div></header>
<main>
  @if (page === '' || page === 'home') {
    <section class="hero"><div class="hero-copy"><p class="eyebrow">{{t('heroEyebrow')}}</p><h1>{{t('heroTitle')}}<br><em>{{t('heroTitleSecond')}}</em></h1><p>{{t('heroBody')}}</p><a class="button" href="/shop">{{t('explore')}} <span>↗</span></a></div><div class="hero-art"><div class="orb orb-one"></div><div class="orb orb-two"></div><div class="plant">✳</div><div class="hero-note">{{t('objectsFor')}}<br>{{t('everydayLiving')}}</div></div></section>
  }
  @if (page === 'shop' || page === '') {
    <section class="section"><div class="section-heading"><div><p class="eyebrow">{{t('collection')}}</p><h2>{{t('shopHeading')}}</h2></div><label class="search">⌕ <input [(ngModel)]="query" (ngModelChange)="loadProducts()" placeholder="{{t('search')}}" [attr.aria-label]="t('search')"></label></div>@if (error) {
    <p class="notice">{{ error }}</p>
    }<div class="grid">@for (product of products; track product) {
    <article class="product" [id]="'product-' + product.id"><a class="product-image" [href]="productHref(product)">@if (product.media?.[0]; as image) {
      <img [src]="image.contentUrl" [alt]="image.altText || product.name" loading="lazy">
      }@if (!product.media?.length) {
      <span>{{ initials(product.name) }}</span>
      }</a><div class="product-meta"><span>ARTIO EDIT</span><span>↗</span></div><h3><a [href]="productHref(product)">{{ product.name }}</a></h3><p>{{ product.description }}</p>@if (product.variants?.length) {
      <select [(ngModel)]="selectedVariants[product.id]" [ngModelOptions]="{standalone:true}" [attr.aria-label]="t('chooseOption')">@for (variant of product.variants; track variant) {
        <option [value]="variant.id">{{ variant.name }}</option>
      }</select>
      }<button class="add" (click)="add(product)">{{t('add')}} <span>＋</span></button></article>
      }@if (!products.length && !error) {
      <p class="empty">{{t('collectionEmpty')}}</p>
    }</div></section>
  }
  @if (page === 'products') {
    <section class="section product-detail" aria-live="polite" [attr.aria-busy]="productLoading">
      @if (productLoading) { <p class="empty">{{t('productLoading')}}</p> }
      @if (!productLoading && !focusedProduct) { <p class="notice">{{t('productUnavailable')}}</p><a class="button small" href="/shop">{{t('backToShop')}}</a> }
      @if (focusedProduct) {
        <a class="product-back" href="/shop">← {{t('backToShop')}}</a>
        <div class="product-detail-layout">
          <div class="product-gallery">
            @if (focusedProduct.media?.[0]; as image) { <img [src]="image.contentUrl" [alt]="image.altText || focusedProduct.name"> }
            @if (!focusedProduct.media?.length) { <span>{{initials(focusedProduct.name)}}</span> }
          </div>
          <div class="product-detail-copy">
            <p class="eyebrow">{{t('sku')}} · {{focusedProduct.sku}}</p>
            <h1>{{focusedProduct.seoTitle || focusedProduct.name}}</h1>
            <p class="product-description">{{focusedProduct.seoDescription || focusedProduct.description}}</p>
            @if (focusedProduct.variants?.length) {
              <label class="product-option">{{t('chooseOption')}}
                <select [ngModel]="selectedVariants[focusedProduct.id]" [ngModelOptions]="{standalone:true}" (ngModelChange)="selectProductVariant($event)">
                  @for (variant of focusedProduct.variants; track variant.id) { <option [value]="variant.id">{{variant.name}}</option> }
                </select>
              </label>
            }
            @if (productQuote; as quote) { <p class="product-price">{{quote.total.amount}} {{moneyCurrency(quote.total.currency)}}</p> }
            <button class="button product-buy" (click)="add(focusedProduct)">{{t('add')}} <span>＋</span></button>
          </div>
        </div>
      }
    </section>
  }
  @if (page === 'bundles') {
    <section class="section"><p class="eyebrow">{{t('madePersonal')}}</p><h2>{{ focusedBundle?.name ?? t('bundles') }}</h2>@if (!focusedBundle) {
    <p>{{t('bundleIntro')}}</p>
    }@if (focusedBundle) {
    <div class="bundle-builder">@for (group of focusedBundle.optionGroups; track group) {
      <div class="bundle-choice-group"><h3>{{group.name}}</h3><p>{{t('chooseRange')}} {{group.minimumSelections}}–{{group.maximumSelections}} {{t('options')}}</p>@for (choice of group.choices; track choice) {
      <label><input type="checkbox" [checked]="isSelected(group.code,choice.sellableId)" (change)="toggleChoice(group.code,choice.sellableId)"><span>{{choice.sellableId}}</span></label>
    }</div>
    }<button class="button" (click)="quoteBundle()">{{t('getPrice')}} ↗</button>@if (bundleQuote) {
    <p class="bundle-quote">{{bundleQuote.totalPrice.amount}} {{bundleQuote.totalPrice.currency}} <small>{{t('forConfiguration')}}</small></p>
    }@if (bundleQuote) {
    <button class="button" (click)="addBundleToCart()">{{t('addConfigured')}} ＋</button>
    }@if (error) {
    <p class="notice">{{error}}</p>
  }</div>
  }@if (!focusedBundle) {
  <div class="grid bundle-grid">@for (bundle of bundles; track bundle) {
    <article class="bundle-card"><div class="bundle-mark">✳</div><p class="eyebrow">{{ bundle.type }}</p><h3>{{ bundle.name }}</h3><p>{{t('bundleCardCopy')}}</p><button class="button small" (click)="configure(bundle)">{{t('chooseOptions')}} ↗</button></article>
    }@if (!bundles.length) {
    <p class="empty">{{t('bundlesEmpty')}}</p>
  }</div>
}</section>
}
@if (page === 'cart') {
  <section class="section narrow"><p class="eyebrow">{{t('selection')}}</p><h2>{{t('bagTitle')}}</h2>@if (!token) {
  <p>{{t('signInToContinue')}}</p>
  }@if (!token) {
  <a href="/login" class="button small">{{t('signIn')}}</a>
  }@for (item of cartItems; track item) {
  <div class="cart-line"><div><strong>{{ item.bundleId ? t('bundleComponent') : item.variantId }}</strong>@if (item.bundleId) {
  <small>{{t('partOfBundle')}} {{item.bundleId}}</small>
  }<small>{{t('quantity')}} {{ item.quantity }}</small></div><span>{{ item.unitPrice }} {{ item.currency }}</span><button class="text-button" (click)="remove(item.variantId)">{{t('remove')}}</button></div>
  }@if (cartItems.length) {
  <a class="button" href="/checkout">{{t('continueCheckout')}} ↗</a>
}</section>
}
@if (page === 'login' || page === 'register') {
  <section class="section narrow auth"><p class="eyebrow">{{t('welcome')}}</p><h2>{{ page === 'login' ? t('goodToSee') : t('createAccount') }}</h2><form (ngSubmit)="submitAuth()">@if (page === 'register') {
  <input [(ngModel)]="firstName" name="firstName" placeholder="{{t('firstName')}}" required>
  }@if (page === 'register') {
  <input [(ngModel)]="lastName" name="lastName" placeholder="{{t('lastName')}}" required>
  }<input [(ngModel)]="email" name="email" type="email" placeholder="{{t('email')}}" required><input [(ngModel)]="password" name="password" type="password" placeholder="{{t('password')}}" minlength="12" required>@if (page === 'login') {
  <input [(ngModel)]="mfaCode" name="mfaCode" type="text" inputmode="numeric" autocomplete="one-time-code" placeholder="{{t('mfa')}}">
  }<button class="button" type="submit">{{ page === 'login' ? t('signIn') : t('createAccount') }} ↗</button></form>@if (page === 'login' && googleOidcEnabled) {
  <a class="button" href="/oauth2/authorization/google">{{t('continueGoogle')}}</a>
  }@if (error) {
  <p class="notice">{{ error }}</p>
  }<a [href]="page === 'login' ? '/register' : '/login'">{{ page === 'login' ? t('newHere') : t('existingAccount') }}</a></section>
}
@if (page === 'orders') {
  <section class="section narrow"><p class="eyebrow">{{t('account')}}</p><h2>{{t('orderHistory')}}</h2>@for (order of orders; track order) {
  <div class="cart-line"><div><strong>{{t('order')}} {{ order.orderId }}</strong><small>{{ statusLabel(order.status) }} @if (order.trackingNumber) {
  <span>· {{t('tracking')}} {{order.trackingNumber}}</span>
  }</small></div><span>{{ order.total }} {{ order.currency }}</span>@if (order.status==='PENDING') {
  <button class="text-button" (click)="payOrder(order)">{{t('payNow')}} ↗</button>
  }@if (order.status==='PENDING') {
  <button class="text-button" (click)="cancelOrder(order)">{{t('cancelOrder')}}</button>
  }@if (order.status==='PARTIALLY_SHIPPED' || order.status==='SHIPPED' || order.status==='DELIVERED' || order.status==='RETURNED') {
  <button class="text-button" (click)="viewOrderHistory(order)">{{t('returnsHistory')}}</button>
}</div>
}@if (customerHistoryOrder) {
<div class="panel"><h3>{{t('returnsHistory')}}</h3>@for (event of customerTimeline; track event) {
<p><strong>{{timelineLabel(event.eventType)}}</strong>@if (timelineDetail(event)) {
<span> · {{timelineDetail(event)}}</span>
} · {{event.occurredAt | date:'medium'}}</p>
}@for (item of customerReturns; track item) {
<div class="cart-line"><strong>{{statusLabel(item.status)}}</strong><span>{{item.reason}}</span></div>
}@if (customerHistoryOrder.status==='PARTIALLY_SHIPPED' || customerHistoryOrder.status==='SHIPPED' || customerHistoryOrder.status==='DELIVERED') {
<p><button class="text-button" (click)="requestReturn(customerHistoryOrder)">{{t('requestReturn')}}</button></p>
}</div>
}@if (!orders.length) {
<p>{{t('ordersEmpty')}}</p>
}@if (error) {
<p class="notice">{{error}}</p>
}</section>
}
@if (page === 'checkout' && !location.search.includes('success')) {
  <section class="section narrow auth"><p class="eyebrow">{{t('checkoutEyebrow')}}</p><h2>{{t('checkoutTitle')}}</h2><form (ngSubmit)="checkout()"><label>{{t('recipient')}}<input [(ngModel)]="recipientName" name="recipientName" autocomplete="name" placeholder="{{t('recipient')}}" (ngModelChange)="loadShippingMethods()" required></label><label>{{t('street')}}<input [(ngModel)]="shippingAddress" name="shippingAddress" autocomplete="address-line1" placeholder="{{t('street')}}" (ngModelChange)="loadShippingMethods()" required></label><div class="form-grid"><label>{{t('postal')}}<input [(ngModel)]="postalCode" name="postalCode" autocomplete="postal-code" placeholder="{{t('postal')}}" (ngModelChange)="loadShippingMethods()" required></label><label>{{t('city')}}<input [(ngModel)]="locality" name="locality" autocomplete="address-level2" placeholder="{{t('city')}}" (ngModelChange)="loadShippingMethods()" required></label></div><label>{{t('billing')}}<input [(ngModel)]="billingAddress" name="billingAddress" autocomplete="billing street-address" placeholder="{{t('billing')}}"></label><label>{{t('country')}}<select [(ngModel)]="shippingCountry" name="shippingCountry" autocomplete="shipping country" (ngModelChange)="shippingCountryChanged()"><option value="CH">{{t('switzerland')}}</option><option value="US">{{t('unitedStates')}}</option><option value="DE">{{t('germany')}}</option><option value="CZ">{{t('czechia')}}</option>@if (!['CH','US','DE','CZ'].includes(shippingCountry)) {
  <option [value]="shippingCountry">{{shippingCountry}}</option>
  }</select></label><label>{{t('parcelWeight')}}<input [(ngModel)]="parcelWeightKg" name="parcelWeightKg" type="number" min="0.001" max="1000" step="0.001" (ngModelChange)="quoteShipping()" required></label><label>{{t('deliveryMethod')}}<select [(ngModel)]="shippingMethodId" name="shippingMethodId" (ngModelChange)="quoteShipping()" required>@for (method of shippingMethods; track method.methodId) {
  <option [value]="method.methodId">{{method.name}}{{method.estimatedDelivery?' · '+method.estimatedDelivery:''}}</option>
}</select></label>@if (paymentMethods.length) {
<label>{{t('paymentMethod')}}<select [(ngModel)]="paymentMethodId" name="paymentMethodId" required>@for (method of paymentMethods; track method.reference) {
  <option [value]="method.reference">{{method.displayName}}</option>
}</select></label>
}@if (paymentMethodsPartialFailure) {
<p class="muted">{{t('paymentMethodsPartial')}}</p>
}@if (shippingMethodsPartialFailure) {
<p class="muted">{{t('shippingMethodsPartial')}}</p>
}@if (shippingQuote) {
<p class="bundle-quote">{{t('delivery')}} {{shippingQuote.amount}} {{shippingQuote.currency}}@if (shippingQuote.estimatedDelivery) {
  <small> · {{shippingQuote.estimatedDelivery}}</small>
}</p>
}@if (!shippingMethods.length) {
<p class="muted">{{t('shippingPrompt')}}</p>
}<label>{{t('giftCard')}}<input [(ngModel)]="giftCardCode" name="giftCardCode" autocomplete="off" maxlength="128" placeholder="{{t('giftCardPlaceholder')}}"></label><button class="button" type="submit" [disabled]="!shippingMethodId || (paymentMethods.length > 1 && !paymentMethodId)">{{t('continuePayment')}} ↗</button></form>@if (error) {
<p class="notice">{{ error }}</p>
}<p class="muted">{{t('priceCheck')}}</p></section>
}
@if (page === 'checkout' && location.search.includes('success')) {
  <section class="section narrow"><p class="eyebrow">{{t('successEyebrow')}}</p><h2>{{paymentReturnConfirmed ? t('thankYou') : t('paymentVerifyingTitle')}}</h2><p>{{paymentCaptureMessage}}</p><a class="button" href="/orders">{{t('viewOrders')}}</a></section>
}
</main><artio-cookie-consent></artio-cookie-consent><footer><a class="brand" href="/">artio<span>®</span></a><p>{{t('footer')}}</p><span>© 2026 Artio Commerce</span></footer>`
})
export class AppComponent {
  private http = inject(HttpClient);
  private titleService = inject(Title); private meta = inject(Meta); private document = inject(DOCUMENT);
  readonly locales=inject(LocaleService);locale:StoreLocale=this.locales.current();
  page = location.pathname.split('/').filter(Boolean)[0] ?? 'home'; location=location; bundleId=location.pathname.split('/').filter(Boolean)[1]; productIdentifier=this.page==='products'?this.bundleId:'';
  token = localStorage.getItem('artio-token');
  private shippingMethodsTimer?: ReturnType<typeof setTimeout>;private catalogRequestVersion=0;private bundleRequestVersion=0;private productRequestVersion=0;private productPriceRequestVersion=0;
  googleOidcEnabled = false;
  products: Product[] = []; private catalogProducts:Product[]=[]; bundles: Bundle[] = []; cartItems: CartItem[] = []; customerHistoryOrder:any=null;customerTimeline:any[]=[];customerReturns:any[]=[];
  focusedProduct?:Product; productLoading=false; productQuote?:ProductQuote;
  query = ''; email = ''; password = ''; mfaCode=''; firstName = ''; lastName = ''; error = ''; cartCount = 0; selectedVariants:Record<string,string>={}; focusedBundle?:Bundle; selectedOptions:Record<string,string[]>={};bundleQuote:any;
  orders:Array<{orderId:string;status:string;total:number;currency:string;trackingNumber?:string}>=[];shippingAddress='';billingAddress='';shippingCountry='CH';recipientName='';postalCode='';locality='';parcelWeightKg=1;shippingMethods:any[]=[];shippingMethodId='';shippingMethodsPartialFailure=false;paymentMethods:any[]=[];paymentMethodId='';paymentMethodsPartialFailure=false;shippingQuote:any;giftCardCode='';paymentCaptureMessage='';paymentReturnConfirmed=false;private checkoutIdempotencyKey=sessionStorage.getItem('artio-checkout-idempotency-key')??'';
  constructor() { const hash=new URLSearchParams(location.hash.replace(/^#/,''));const ssoToken=hash.get('access_token');if(ssoToken){localStorage.setItem('artio-token',ssoToken);this.token=ssoToken;history.replaceState(null,'',location.pathname+location.search);}const params=new URLSearchParams(location.search);if(params.get('locale')){this.locale=this.locales.normalize(params.get('locale')!);this.locales.set(this.locale);}if(params.get('oauth_error'))this.error=this.t('googleSignInError');this.http.get<{googleOidcEnabled:boolean;countryCode:string;currencyCode:string;locale:string}>('/api/v1/public/config').subscribe({next:config=>{this.googleOidcEnabled=config.googleOidcEnabled;if(/^[A-Z]{2}$/.test(config.countryCode))this.shippingCountry=config.countryCode;if(!localStorage.getItem('artio-locale')){this.locale=this.locales.normalize(config.locale);this.locales.set(this.locale);this.loadPageCatalog();this.loadBundles();this.updateSeoMetadata();}if(this.page==='checkout'&&this.token){this.loadShippingMethods();this.loadPaymentMethods()}},error:()=>this.googleOidcEnabled=false});this.loadPageCatalog();this.loadBundles();if(this.token){this.loadCart();this.loadOrders();}if(params.get('success')==='true'){const orderId=params.get('orderId');const providerToken=params.get('token');if(!this.token)this.paymentCaptureMessage=this.t('paymentVerifyUnavailable');else if(orderId&&providerToken)this.captureProviderOrder(orderId,providerToken);else if(orderId)this.verifyProviderOrder(orderId);else this.paymentCaptureMessage=this.t('paymentVerifyUnavailable');}this.updateSeoMetadata(); }
  private loadPageCatalog():void{if(this.page==='products')this.loadProductDetail();else if(this.page==='shop'||this.page==='home')this.loadProducts();}
  loadProductDetail():void{if(!this.productIdentifier)return;const version=++this.productRequestVersion;this.productPriceRequestVersion++;this.productLoading=true;this.focusedProduct=undefined;this.productQuote=undefined;this.updateSeoMetadata();this.http.get<Product>(`/api/v1/public/products/${encodeURIComponent(this.productIdentifier)}?locale=${encodeURIComponent(this.locale)}`).subscribe({next:product=>{if(version!==this.productRequestVersion)return;this.focusedProduct={...product,media:[],variants:[]};this.productLoading=false;this.updateSeoMetadata();this.http.get<Array<{id:string;contentUrl:string;altText:string;position:number}>>(`/api/v1/products/${product.id}/media`).subscribe({next:media=>{if(version===this.productRequestVersion&&this.focusedProduct?.id===product.id){this.focusedProduct.media=[...media].sort((a,b)=>a.position-b.position);this.updateProductStructuredData();}},error:()=>undefined});this.http.get<Variant[]>(`/api/v1/products/${product.id}/variants`).subscribe({next:variants=>{if(version!==this.productRequestVersion||this.focusedProduct?.id!==product.id)return;this.focusedProduct.variants=variants.filter(variant=>variant.active);this.selectedVariants[product.id]=this.focusedProduct.variants[0]?.id??product.id;this.loadProductPrice();},error:()=>{if(version!==this.productRequestVersion)return;this.selectedVariants[product.id]=product.id;this.loadProductPrice();}});},error:()=>{if(version!==this.productRequestVersion)return;this.productLoading=false;this.focusedProduct=undefined;this.updateSeoMetadata();}});}
  private loadProductPrice():void{const product=this.focusedProduct;if(!product)return;const version=++this.productPriceRequestVersion;const sellableId=this.selectedVariants[product.id]??product.id;this.productQuote=undefined;this.http.post<ProductQuote>('/api/v1/pricing/quote',{sellableId,market:this.shippingCountry,channel:'WEB',jurisdiction:this.shippingCountry,quantity:1}).subscribe({next:quote=>{if(version!==this.productPriceRequestVersion||this.focusedProduct?.id!==product.id)return;this.productQuote=quote;this.updateProductStructuredData();},error:()=>{if(version===this.productPriceRequestVersion)this.updateProductStructuredData();}});}
  selectProductVariant(variantId:string):void{if(!this.focusedProduct)return;this.selectedVariants[this.focusedProduct.id]=variantId;this.loadProductPrice();}
  productHref(product:Product):string{return `/products/${encodeURIComponent(product.seoSlug||product.id)}?locale=${encodeURIComponent(this.locale)}`;}
  moneyCurrency(currency:string|{currencyCode:string}):string{return typeof currency==='string'?currency:currency.currencyCode;}
  loadProducts(): void { const version=++this.catalogRequestVersion;this.http.get<Product[]>('/api/v1/products').subscribe({ next: items => { if(version!==this.catalogRequestVersion)return;this.catalogProducts=items;this.products=items;this.updateProductStructuredData(); for (const product of this.catalogProducts) { this.http.get<{name:string;description:string;seoSlug:string|null;seoTitle:string|null;seoDescription:string|null}>(`/api/v1/products/${product.id}/localized?locale=${this.locale}`).subscribe({next:localized=>{if(version!==this.catalogRequestVersion)return;product.name=localized.name;product.description=localized.description;product.seoSlug=localized.seoSlug;product.seoTitle=localized.seoTitle;product.seoDescription=localized.seoDescription;this.products=this.query?this.catalogProducts.filter(item=>item.name.toLowerCase().includes(this.query.toLowerCase())):[...this.catalogProducts];this.updateProductStructuredData();},error:()=>undefined});this.http.get<Array<{id:string;contentUrl:string;altText:string;position:number}>>(`/api/v1/products/${product.id}/media`).subscribe({next:media=>{if(version===this.catalogRequestVersion){product.media=[...media].sort((a,b)=>a.position-b.position);this.updateProductStructuredData();}},error:()=>product.media=[]});this.http.get<Variant[]>(`/api/v1/products/${product.id}/variants`).subscribe({next:variants=>{product.variants=variants.filter(v=>v.active);if(product.variants.length&&!this.selectedVariants[product.id])this.selectedVariants[product.id]=product.variants[0].id;},error:()=>product.variants=[]});} }, error: () => this.error = this.t('catalogUnavailable') }); }
  loadBundles(): void { const version=++this.bundleRequestVersion;this.http.get<Array<Bundle & {localizedName:string;optionGroups:Array<{code:string;name:string;localizedName:string;minimumSelections:number;maximumSelections:number;choices:Array<{sellableId:string;quantity:number}>}>}>>(`/api/v1/bundles/localized?locale=${this.locale}`).subscribe({ next: items => {if(version!==this.bundleRequestVersion)return;this.bundles = items.filter(b => b.active).map(b=>({...b,name:b.localizedName??b.name,optionGroups:b.optionGroups.map(group=>({...group,name:group.localizedName??group.name}))}));this.focusedBundle=this.bundles.find(b=>b.id===this.bundleId);}, error: () => undefined }); }
  loadCart(): void { this.http.get<{items:CartItem[]}>('/api/v1/cart').subscribe({next: x => {this.cartItems=x.items;this.cartCount=x.items.reduce((n:number,i:CartItem)=>n+i.quantity,0);},error:()=>undefined}); }
  loadOrders():void{this.http.get<Array<{orderId:string;status:string;total:number;currency:string;trackingNumber?:string}>>('/api/v1/cart/orders').subscribe({next:r=>this.orders=r,error:()=>undefined});}
  cancelOrder(order:any):void{if(!confirm(this.t('cancelConfirm')))return;this.http.post<any>(`/api/v1/payments/orders/${order.orderId}/cancel`,{}).subscribe({next:()=>this.loadOrders(),error:e=>this.error=this.apiErrorMessage(e, this.t('cancelUnavailable'))})}
  viewOrderHistory(order:any):void{this.customerHistoryOrder=order;this.http.get<any[]>(`/api/v1/orders/${order.orderId}/timeline`).subscribe({next:events=>this.customerTimeline=events,error:e=>this.error=this.apiErrorMessage(e, this.t('orderHistoryUnavailable'))});this.http.get<any[]>(`/api/v1/orders/${order.orderId}/returns`).subscribe({next:returns=>this.customerReturns=returns,error:e=>this.error=this.apiErrorMessage(e, this.t('returnRequestsUnavailable'))});}
  requestReturn(order:any):void{this.http.get<any[]>(`/api/v1/orders/${order.orderId}/items`).subscribe({next:items=>{if(!items.length){this.error=this.t('returnNoItems');return}const returnableItems=items.filter(line=>Number(line.returnableQuantity)>0);if(!returnableItems.length){this.error=this.t('returnNoItems');return}const choices=returnableItems.map((line,index)=>`${index+1}: ${line.variantId}${line.bundleId ? ` (${this.t('returnBundleComponent')})` : ''}, ${this.t('returnableQuantity')} ${line.returnableQuantity}`).join('\n');const selected=Number(prompt(this.t('returnPickItem')+'\n'+choices));if(!Number.isInteger(selected)||selected<1||selected>returnableItems.length)return;const line=returnableItems[selected-1];const quantity=Number(prompt(`${this.t('returnQuantity')} (${this.t('maximum')} ${line.returnableQuantity})`));if(!Number.isInteger(quantity)||quantity<=0||quantity>line.returnableQuantity){this.error=this.t('returnInvalidQuantity');return}const reason=prompt(this.t('returnReason'));if(!reason?.trim())return;const storageKey=`artio-return-key:${order.orderId}:${line.orderItemId}:${quantity}`;let key=sessionStorage.getItem(storageKey);if(!key){key=crypto.randomUUID();sessionStorage.setItem(storageKey,key)}this.http.post<any>(`/api/v1/orders/${order.orderId}/returns`,{reason:reason.trim(),items:[{orderItemId:line.orderItemId,quantity}]},{headers:new HttpHeaders({'Idempotency-Key':key})}).subscribe({next:()=>{sessionStorage.removeItem(storageKey);this.viewOrderHistory(order)},error:e=>this.error=this.apiErrorMessage(e, this.t('returnSubmitUnavailable'))})},error:e=>this.error=this.apiErrorMessage(e, this.t('orderItemsUnavailable'))})}
  shippingCountryChanged():void{this.loadShippingMethods();this.loadPaymentMethods()}
  loadShippingMethods():void{if(!this.token)return;this.shippingQuote=null;if(this.shippingMethodsTimer)clearTimeout(this.shippingMethodsTimer);this.shippingMethodsTimer=setTimeout(()=>{const query=new URLSearchParams({country:this.shippingCountry,recipientName:this.recipientName,postalCode:this.postalCode,locality:this.locality,addressLine1:this.shippingAddress});this.http.get<{methods:any[];partialFailure:boolean}>(`/api/v1/cart/shipping-methods?${query}`).subscribe({next:result=>{const methods=result.methods??[];const previous=this.shippingMethodId;this.shippingMethods=methods;this.shippingMethodsPartialFailure=!!result.partialFailure;if(!methods.some(method=>method.methodId===previous))this.shippingMethodId=methods[0]?.methodId??'';this.quoteShipping()},error:e=>{this.shippingMethods=[];this.shippingMethodsPartialFailure=false;this.shippingQuote=null;this.error=this.apiErrorMessage(e, this.t('shippingServicesUnavailable'))}})},250)}
  loadPaymentMethods():void{if(!this.token)return;const currency=({CH:'CHF',US:'USD',DE:'EUR',CZ:'CZK'} as Record<string,string>)[this.shippingCountry]??'CHF';this.http.post<{methods:any[];partialFailure:boolean}>('/api/v1/payments/methods',{country:this.shippingCountry,currency}).subscribe({next:result=>{const methods=result.methods??[];const previous=this.paymentMethodId;this.paymentMethods=methods;this.paymentMethodsPartialFailure=!!result.partialFailure;this.paymentMethodId=methods.some(method=>method.reference===previous)?previous:methods.length===1?methods[0].reference:''},error:()=>{this.paymentMethods=[];this.paymentMethodId='';this.paymentMethodsPartialFailure=true}})}
  quoteShipping():void{if(!this.token||!this.shippingMethodId)return;this.http.post<any>('/api/v1/cart/shipping-quote',{methodId:this.shippingMethodId,destination:{countryCode:this.shippingCountry,postalCode:this.postalCode,locality:this.locality,addressLine1:this.shippingAddress,recipientName:this.recipientName},parcelWeightKg:Number(this.parcelWeightKg),declaredValue:0}).subscribe({next:quote=>this.shippingQuote=quote,error:e=>{this.shippingQuote=null;this.error=this.apiErrorMessage(e, this.t('shippingPriceUnavailable'))}})}
  checkout():void{this.error='';if(!this.checkoutIdempotencyKey){this.checkoutIdempotencyKey=crypto.randomUUID();sessionStorage.setItem('artio-checkout-idempotency-key',this.checkoutIdempotencyKey);}const headers=new HttpHeaders({'Idempotency-Key':this.checkoutIdempotencyKey});const locale=this.locale;const address=[this.shippingAddress,this.postalCode,this.locality,this.shippingCountry].filter(Boolean).join(', ');this.http.post<{orderId:string;status:string}>('/api/v1/cart/checkout',{shippingAddress:address,billingAddress:this.billingAddress,shippingCountry:this.shippingCountry,locale,shippingMethodId:this.shippingMethodId,parcelWeightKg:Number(this.parcelWeightKg),giftCardCode:this.giftCardCode.trim()||null,shippingDetails:{recipientName:this.recipientName,postalCode:this.postalCode,locality:this.locality,addressLine1:this.shippingAddress}},{headers}).subscribe({next:order=>{if(order.status==='PAID'){sessionStorage.removeItem('artio-checkout-idempotency-key');this.checkoutIdempotencyKey='';this.giftCardCode='';location.assign(`/checkout?success=true&orderId=${encodeURIComponent(order.orderId)}`);}else this.payOrder({orderId:order.orderId,paymentMethodId:this.paymentMethodId||undefined});},error:e=>this.error=this.apiErrorMessage(e, this.t('checkoutUnavailable'))});}
  payOrder(order:{orderId:string;paymentMethodId?:string}):void{const methodReference=order.paymentMethodId??this.paymentMethodId;this.http.post<{checkoutUrl:string}>(`/api/v1/payments/orders/${order.orderId}/checkout-session`,methodReference?{methodReference}:{}).subscribe({next:session=>{sessionStorage.removeItem('artio-checkout-idempotency-key');this.checkoutIdempotencyKey='';location.assign(session.checkoutUrl);},error:e=>{this.error=this.apiErrorMessage(e, this.t('paymentSetupUnavailable'));this.loadOrders();}});}
  private captureProviderOrder(orderId:string,providerToken:string):void{this.paymentCaptureMessage=this.t('paymentVerifying');this.http.post<{status:string}>(`/api/v1/payments/provider/orders/${encodeURIComponent(orderId)}/capture?token=${encodeURIComponent(providerToken)}`,{}).subscribe({next:r=>{this.paymentReturnConfirmed=r.status==='captured';this.paymentCaptureMessage=r.status==='captured'?this.t('payPalCaptured'):r.status==='cancelled'?this.t('payPalCancelled'):this.t('paymentProcessing');this.loadOrders();},error:()=>{this.paymentCaptureMessage=this.t('payPalProcessingError');this.loadOrders();}});}
  private verifyProviderOrder(orderId:string):void{this.paymentCaptureMessage=this.t('paymentVerifying');this.http.get<Array<{orderId:string;status:string}>>('/api/v1/cart/orders').subscribe({next:orders=>{const order=orders.find(candidate=>candidate.orderId===orderId);this.paymentReturnConfirmed=order?.status==='PAID';this.paymentCaptureMessage=this.paymentReturnConfirmed?this.t('payPalCaptured'):order?.status==='CANCELLED'?this.t('paymentNotConfirmed'):this.t('paymentProcessing');},error:()=>{this.paymentCaptureMessage=this.t('paymentVerifyUnavailable');}});}
  submitAuth(): void { const url=this.page==='login'?'/api/v1/auth/login':'/api/v1/auth/register'; const body=this.page==='login'?{email:this.email,password:this.password,mfaCode:this.mfaCode}:{email:this.email,password:this.password,firstName:this.firstName,lastName:this.lastName}; this.http.post<{accessToken?:string}>(url,body).subscribe({next:r=>{if(r.accessToken){localStorage.setItem('artio-token',r.accessToken);location.assign('/shop');}else location.assign('/login');},error:e=>this.error=this.apiErrorMessage(e, this.t('authUnavailable'))}); }
  add(product: Product): void { if(!this.token){location.assign('/login');return;} const variantId=this.selectedVariants[product.id]??product.id; this.http.post('/api/v1/cart/items',{variantId,quantity:1,market:this.shippingCountry,channel:'WEB',jurisdiction:this.shippingCountry}).subscribe({next:()=>this.loadCart(),error:e=>this.error=this.apiErrorMessage(e, this.t('priceUnavailable'))}); }
  remove(id:string):void{this.http.delete(`/api/v1/cart/items/${id}`).subscribe(()=>this.loadCart());}
  configure(bundle:Bundle):void{location.assign(`/bundles/${bundle.id}`);}
  isSelected(group:string,id:string):boolean{return (this.selectedOptions[group]??[]).includes(id);}
  toggleChoice(group:string,id:string):void{const selected=new Set(this.selectedOptions[group]??[]);selected.has(id)?selected.delete(id):selected.add(id);this.selectedOptions[group]=[...selected];this.bundleQuote=undefined;}
  quoteBundle():void{if(!this.focusedBundle)return;const selectedOptions=Object.fromEntries(Object.entries(this.selectedOptions).map(([key,values])=>[key,values]));this.http.post<any>(`/api/v1/bundles/${this.focusedBundle.id}/quote`,{selectedOptions,bundleQuantity:1,market:this.shippingCountry,channel:'WEB',jurisdiction:this.shippingCountry}).subscribe({next:q=>{this.bundleQuote=q;this.error='';},error:e=>this.error=this.apiErrorMessage(e, this.t('bundlePriceUnavailable'))});}
  addBundleToCart():void{if(!this.token){location.assign('/login');return;}if(!this.focusedBundle)return;this.http.post('/api/v1/cart/bundles',{bundleId:this.focusedBundle.id,selectedOptions:this.selectedOptions,bundleQuantity:1,market:this.shippingCountry,channel:'WEB',jurisdiction:this.shippingCountry}).subscribe({next:()=>location.assign('/cart'),error:e=>this.error=this.apiErrorMessage(e, this.t('bundleAddUnavailable'))});}
  logout():void{localStorage.removeItem('artio-token');location.assign('/');}
  t(key:string):string{return this.locales.translate(key,this.locale);}
  apiErrorMessage(error:any,fallback:string):string{const statusCode:Record<number,string>={400:'BAD_REQUEST',401:'UNAUTHORIZED',403:'FORBIDDEN',404:'NOT_FOUND',409:'CONFLICT',422:'BAD_REQUEST',429:'RATE_LIMITED',503:'SERVICE_UNAVAILABLE'};const code=error?.error?.code??statusCode[error?.status]??(error?.status>=500?'SERVER_ERROR':undefined);if(typeof code==='string'){const key=`apiError_${code}`;const translated=this.locales.translate(key,this.locale);if(translated!==key)return translated;}return error?.error?.message??error?.error?.detail??fallback;}
  timelineLabel(type:string):string{const keys:Record<string,string>={ORDER_CREATED:'timelineOrderCreated',ORDER_PAID:'timelinePaymentConfirmed',ORDER_CANCELLED:'timelineOrderCancelled',ORDER_SHIPPED:'timelineOrderShipped',ORDER_SHIPMENT_CREATED:'timelineShipmentCreated',ORDER_DELIVERED:'timelineOrderDelivered',RETURN_REQUESTED:'timelineReturnRequested',PAYMENT_REFUNDED:'timelinePaymentRefunded',TRACKING_UPDATED:'timelineTrackingUpdated'};return keys[type]?this.t(keys[type]):type.replaceAll('_',' ').toLowerCase()}
  timelineDetail(event:any):string{const message=String(event.message??'');if(event.eventType==='RETURN_REQUESTED'){const reason=message.split(':').slice(1).join(':').trim();return reason?this.t('timelineReason')+': '+reason:''}if(event.eventType==='ORDER_SHIPPED'||event.eventType==='ORDER_SHIPMENT_CREATED'){const match=message.match(/Tracking:\\s*(.+)$/i);return match?this.t('tracking')+': '+match[1]:''}if(event.eventType==='TRACKING_UPDATED'){const match=message.match(/Carrier status:\\s*(.+)$/i);return match?this.t('carrierStatus')+': '+this.statusLabel(match[1].trim()):''}return ''}
  statusLabel(status:string):string{const keys:Record<string,string>={PENDING:'statusPending',PAID:'statusPaid',PARTIALLY_SHIPPED:'statusPartiallyShipped',SHIPPED:'statusShipped',DELIVERED:'statusDelivered',CANCELLED:'statusCancelled',RETURNED:'statusReturned',REQUESTED:'statusRequested',APPROVED:'statusApproved',REJECTED:'statusRejected',RECEIVED:'statusReceived'};return keys[status]?this.t(keys[status]):status;}
  changeLocale():void{this.locales.set(this.locale);if(this.page==='products'){const url=new URL(this.location.href);url.searchParams.set('locale',this.locale);history.replaceState(null,'',url.pathname+url.search);this.loadProductDetail();}else if(this.page==='home'||this.page==='shop')this.loadProducts();this.loadBundles();this.updateSeoMetadata();if(this.page==='checkout'&&this.token){this.loadShippingMethods();this.loadPaymentMethods();}}
  private updateSeoMetadata():void{const productPage=this.page==='products'&&!!this.focusedProduct;const publicPage=['home','shop','bundles'].includes(this.page)||productPage;const pageTitle=productPage?(this.focusedProduct!.seoTitle||this.focusedProduct!.name):this.page==='shop'?this.t('shopHeading'):this.page==='bundles'?this.t('bundles'):this.t('heroTitle');const description=productPage?(this.focusedProduct!.seoDescription||this.focusedProduct!.description):this.page==='bundles'?this.t('bundleIntro'):this.page==='shop'?this.t('collectionEmpty'):this.t('heroBody');const title=`${pageTitle} · Artio`;const canonical=new URL(this.location.href);canonical.search='';if(productPage)canonical.searchParams.set('locale',this.locale);canonical.hash='';canonical.pathname=canonical.pathname.replace(/\/+$/,'')||'/';this.titleService.setTitle(title);this.document.documentElement.lang=this.locale;this.meta.updateTag({name:'description',content:description});this.meta.updateTag({name:'robots',content:publicPage?'index,follow':'noindex,nofollow'});this.meta.updateTag({property:'og:title',content:title});this.meta.updateTag({property:'og:description',content:description});this.meta.updateTag({property:'og:url',content:canonical.toString()});this.meta.updateTag({property:'og:type',content:productPage?'product':'website'});let canonicalLink=this.document.head.querySelector<HTMLLinkElement>('link[rel="canonical"]');if(!canonicalLink){canonicalLink=this.document.createElement('link');canonicalLink.rel='canonical';this.document.head.appendChild(canonicalLink);}canonicalLink.href=canonical.toString();let structuredData=this.document.head.querySelector<HTMLScriptElement>('script[data-artio-seo="website"]');if(!structuredData){structuredData=this.document.createElement('script');structuredData.type='application/ld+json';structuredData.dataset['artioSeo']='website';this.document.head.appendChild(structuredData);}structuredData.textContent=JSON.stringify({'@context':'https://schema.org','@type':'WebSite',name:'Artio',url:canonical.origin,description});this.updateProductStructuredData();}
  private updateProductStructuredData():void{let script=this.document.head.querySelector<HTMLScriptElement>('script[data-artio-seo="products"]');if(this.page==='products'&&this.focusedProduct){if(!script){script=this.document.createElement('script');script.type='application/ld+json';script.dataset['artioSeo']='products';this.document.head.appendChild(script);}const product=this.focusedProduct;const url=new URL(this.location.pathname,this.location.origin);url.searchParams.set('locale',this.locale);const schema:any={'@context':'https://schema.org','@type':'Product','@id':url.toString(),name:product.seoTitle||product.name,description:product.seoDescription||product.description,sku:product.sku,url:url.toString()};if(product.media?.[0]?.contentUrl)schema.image=product.media[0].contentUrl;if(this.productQuote)schema.offers={'@type':'Offer',price:this.productQuote.total.amount,priceCurrency:this.moneyCurrency(this.productQuote.total.currency),availability:'https://schema.org/InStock',url:url.toString()};script.textContent=JSON.stringify(schema);return;}if(this.page!=='shop'||!this.catalogProducts.length){script?.remove();return;}if(!script){script=this.document.createElement('script');script.type='application/ld+json';script.dataset['artioSeo']='products';this.document.head.appendChild(script);}const origin=this.location.origin;script.textContent=JSON.stringify({'@context':'https://schema.org','@type':'ItemList',itemListElement:this.catalogProducts.map((product,index)=>{const url=new URL(this.productHref(product),origin).toString();const item:any={'@type':'Product','@id':url,name:product.seoTitle||product.name,description:product.seoDescription||product.description,sku:product.sku,url};if(product.media?.[0]?.contentUrl)item.image=product.media[0].contentUrl;return {'@type':'ListItem',position:index+1,item};}),numberOfItems:this.catalogProducts.length});}
  initials(name:string):string{return name.split(/\s+/).slice(0,2).map(x=>x[0]).join('').toUpperCase();}
}
