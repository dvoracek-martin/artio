import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, inject, ChangeDetectionStrategy, ElementRef, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { GiftCardAdminComponent } from './gift-card-admin.component';
import { ReportsAdminComponent } from './reports-admin.component';
import { LicenseAdminComponent } from './license-admin.component';
import { InventoryAdminComponent } from './inventory-admin.component';
import { CatalogMetadataAdminComponent } from './catalog-metadata-admin.component';
import { ProductMediaAdminComponent } from './product-media-admin.component';
import { ProductWizardComponent } from './product-wizard.component';
import { Router, NavigationEnd } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { SetupWizardComponent } from './setup-wizard.component';
import { SearchSelectComponent, SelectOption } from './search-select.component';
import { SellableSelectComponent } from './sellable-select.component';
import { COUNTRY_OPTIONS, CURRENCY_OPTIONS, LANGUAGE_OPTIONS, LOCALE_OPTIONS, TIMEZONE_OPTIONS } from './commerce-options';
import { BackofficeTextKey, backofficeText, currentBackofficeLocale } from './backoffice-locale';
import QRCode from 'qrcode';
type Product={id:string;name:string;description:string;sku:string;active:boolean;archived:boolean;seoSlug:string|null;seoTitle:string|null;seoDescription:string|null;scheduledPublishAt:string|null;variants?:Array<{id:string;name:string;sku:string;active:boolean;attributes?:Record<string,string>}>};
@Component({
    selector: 'artio-admin', imports: [CommonModule, FormsModule, ReportsAdminComponent, LicenseAdminComponent, GiftCardAdminComponent, InventoryAdminComponent, CatalogMetadataAdminComponent, ProductMediaAdminComponent, ProductWizardComponent, SetupWizardComponent, SearchSelectComponent, SellableSelectComponent], changeDetection: ChangeDetectionStrategy.Eager,
 template: `
@if (!token && !setupChecked) {
  <div class="login-page"><div class="login-card"><a class="brand">artio<span> / studio</span></a><p class="muted">Checking store setup…</p></div></div>
}
@if (!token && setupChecked && setupRequired) {
  @defer (when setupChecked && setupRequired) {
  <artio-setup-wizard [developerMode]="developerMode" (completed)="setupFinished($event)"></artio-setup-wizard>
  } @placeholder { <div class="login-page"><div class="login-card"><p class="muted">Preparing store setup…</p></div></div> }
}
@if (!token && setupChecked && !setupRequired) {
  <div class="login-page"><div class="login-card"><a class="brand">artio<span> / studio</span></a><p class="eyebrow">Commerce, thoughtfully run</p><h1>Welcome back.</h1><p class="muted">Sign in to manage your Artio store.</p><form (ngSubmit)="login()"><label>Email<input [(ngModel)]="email" name="email" type="email" autocomplete="username" required></label><label>Password<input [(ngModel)]="password" name="password" type="password" autocomplete="current-password" required></label>@if (!developerMode) {
  <label>Authenticator or backup code<input [(ngModel)]="mfaCode" name="mfaCode" inputmode="numeric" autocomplete="off"></label>
  }@if (developerMode) {
  <small class="muted">Local demo account is ready. Sign in to open your Artio workspace.</small>
  }<button class="primary">Sign in <span>↗</span></button></form>@if (error) {
  <p class="error" role="alert">{{error}}</p>
}</div></div>
}
@if (token && mfaSetupRequired) {
  <div class="login-page"><div class="login-card setup-card mfa-card"><a class="brand">artio<span> / security</span></a><p class="eyebrow">REQUIRED ACCOUNT PROTECTION</p><h1>Secure your account.</h1><p class="muted">Add an authenticator app to protect administrator access. Scan the QR code, then enter the current six-digit code from the app.</p>@if (!mfaEnrollment && !mfaBackupCodes.length) {
  <button class="primary" type="button" (click)="beginMfaEnrollment()">Start setup <span>↗</span></button>
  }@if (mfaEnrollment && !mfaBackupCodes.length) {
  <div class="mfa-enrollment"><div class="mfa-step"><span>1</span><div><strong>Scan this code</strong><p>Open Google Authenticator, Microsoft Authenticator, 1Password, or another TOTP app and scan the QR code.</p></div></div>@if (mfaQrCode) {
  <div class="mfa-qr-frame"><img [src]="mfaQrCode" alt="QR code for adding Artio to your authenticator app"></div>
  }<details class="mfa-manual"><summary>Can't scan? Set up manually</summary><p>Enter this key in your authenticator app:</p><div class="mfa-secret-row"><code>{{mfaEnrollment.base32Secret}}</code><button type="button" class="secondary" (click)="copyMfaSecret()">{{mfaCopied?'Copied':'Copy key'}}</button></div><small>Choose time-based one-time password (TOTP) if the app asks.</small></details><div class="mfa-step mfa-code-step"><span>2</span><div><strong>Confirm it works</strong><p>Enter the current code shown in your authenticator app. Codes refresh every 30 seconds.</p></div></div><form (ngSubmit)="verifyMfaEnrollment()"><label>Six-digit verification code<input [(ngModel)]="mfaCode" name="enrollmentCode" type="text" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" placeholder="000000" aria-describedby="mfa-code-help" required></label><small id="mfa-code-help" class="mfa-code-help">Enter the code from the app. A sample code such as 123456 will not work.</small><button class="primary" [disabled]="mfaCode.length!==6">Verify and enable <span>↗</span></button></form></div>
  }@if (mfaBackupCodes.length) {
  <div class="mfa-backup-panel"><div class="mfa-success-mark">✓</div><h2>Authenticator enabled.</h2><p>Save these one-time backup codes somewhere secure. Each code can be used once if you lose access to your authenticator.</p><ul>@for (code of mfaBackupCodes; track code) {
  <li><code>{{code}}</code></li>
  }</ul><button class="primary" (click)="finishMfaSetup()">Continue to sign in <span>↗</span></button></div>
  }@if (error) {
  <p class="error" role="alert">{{error}}</p>
}</div></div>
}
@if (token && !mfaSetupRequired) {
  <div class="shell" [class.mobile-nav-open]="navOpen"><a class="skip-link" href="#main-content">Skip to main content</a><aside class="sidebar" id="workspace-navigation" aria-label="Workspace navigation"><a class="brand">artio<span> / studio</span></a><button type="button" class="mobile-menu-toggle" [attr.aria-expanded]="navOpen" aria-controls="workspace-navigation" (click)="navOpen=!navOpen">{{navOpen?'Close menu ×':'Menu ☰'}}</button><p class="label">WORKSPACE</p><button [class.selected]="tab==='overview'" (click)="navigateTo('overview')">◫ &nbsp; Overview</button><button [class.selected]="tab==='products' || tab==='productNew'" (click)="navigateTo('products')">▦ &nbsp; Products</button><button [class.selected]="tab==='catalogData'" (click)="navigateTo('catalogData')">⌘ &nbsp; Catalog data</button><button [class.selected]="tab==='bundles'" (click)="navigateTo('bundles')">✳ &nbsp; Bundles</button><button [class.selected]="tab==='extensions'" (click)="navigateTo('extensions')">⬡ &nbsp; Extensions</button><button [class.selected]="tab==='pricing'" (click)="navigateTo('pricing')">◉ &nbsp; Pricing</button><button [class.selected]="tab==='inventory'" (click)="navigateTo('inventory')">▤ &nbsp; Inventory</button><button [class.selected]="tab==='orders'" (click)="navigateTo('orders')">↗ &nbsp; Orders</button><button [class.selected]="tab==='users'" (click)="navigateTo('users')">♙ &nbsp; Customers</button><button [class.selected]="tab==='reports'" (click)="navigateTo('reports')">▥ &nbsp; Reports</button><button [class.selected]="tab==='giftCards'" (click)="navigateTo('giftCards')">▣ &nbsp; Gift cards</button><button [class.selected]="tab==='operations'" (click)="navigateTo('operations')">▣ &nbsp; Operations</button>@if (isSuperadmin) {
  <button [class.selected]="tab==='system'" (click)="navigateTo('system')">⚙ &nbsp; System Settings</button>
  }<div class="sidebar-bottom"><span class="avatar">{{email.charAt(0).toUpperCase()}}</span><div><strong>{{email}}</strong><small>Administrator</small></div><button class="logout" (click)="logout()">↪</button></div></aside>
  <main class="content" id="main-content" tabindex="-1"><header><div><p class="eyebrow">{{today | date:'EEEE, MMMM d'}}</p><h1>{{ title }}</h1></div><div class="header-actions"><a href="http://localhost:4200" target="_blank">View storefront ↗</a><span class="workspace-badge">Artio workspace</span></div></header>
  @if (tab==='reports') {
    @defer (when tab==='reports') {
    <artio-reports-admin></artio-reports-admin>
    } @placeholder { <p class="muted" role="status">Loading reports…</p> }
    }@if (tab==='overview') {
    <section><div class="welcome"><div><p class="eyebrow">YOUR STORE AT A GLANCE</p><h2>Your store. In focus.</h2><p>A calm view of what’s happening across your store today.</p></div><div class="welcome-mark">✳</div></div><div class="metrics"><article><span>Catalog products</span><strong>{{overviewProductTotal}}</strong><small>Across your catalogue</small></article><article><span>Bundles</span><strong>{{bundles.length}}</strong><small>In your collection</small></article><article><span>Orders</span><strong>{{overviewOrderTotal}}</strong><small>Across your store</small></article><article><span>Price entries</span><strong>{{prices.length}}</strong><small>Markets & channels</small></article></div><div class="panel"><div class="panel-heading"><div><p class="eyebrow">RECENT ACTIVITY</p><h2>Products in your catalogue</h2></div><button class="link-button" (click)="navigateTo('products')">View all →</button></div>@for (product of overviewProducts; track product) {
    <div class="row"><span class="dot-icon">{{product.name.charAt(0)}}</span><div class="row-main"><strong>{{product.name}}</strong><small>{{product.sku}}</small></div><span class="pill" [class.inactive]="!product.active">{{product.active?'Active':'Inactive'}}</span></div>
    }@if (!overviewProducts.length) {
    <p class="empty">Your catalogue is ready for its first product.</p>
  }</div></section>
}
@if (tab==='productNew') {
  @defer (when tab==='productNew') {
  <artio-product-wizard (cancel)="navigateTo('products')" (created)="productCreated($event)"></artio-product-wizard>
  } @placeholder { <p class="muted" role="status">Loading product editor…</p> }
}
@if (tab==='products') {
  <section class="catalog-page"><div class="page-intro"><div><p class="eyebrow">YOUR CATALOG</p><h2>Made to stand out.</h2><p>Build, organize and refine your product collection.</p></div><button class="primary" (click)="navigateTo('productNew')">＋ New product</button></div><div class="toolbar catalog-toolbar"><input #productCsvFile type="file" accept=".csv,text/csv" hidden (change)="importProducts($event)"><button class="link-button" (click)="productCsvFile.click()">Import CSV</button><button class="link-button" (click)="exportProducts()">Export CSV</button><label class="search">⌕ <input aria-label="Search products" [(ngModel)]="query" (keyup.enter)="resetCatalogPage()" placeholder="Search products"></label><button class="link-button" (click)="resetCatalogPage()">Search</button><select aria-label="Product status" [(ngModel)]="catalogState" (ngModelChange)="resetCatalogPage()"><option value="ALL">All products</option><option value="ACTIVE">Active</option><option value="INACTIVE">Inactive drafts</option><option value="ARCHIVED">Archived</option></select><artio-search-select [(ngModel)]="catalogBrandId" (ngModelChange)="resetCatalogPage()" [options]="catalogBrandOptions" label="Filter by brand" placeholder="All brands"></artio-search-select><artio-search-select [(ngModel)]="catalogCategoryId" (ngModelChange)="resetCatalogPage()" [options]="catalogCategoryOptions" label="Filter by category" placeholder="All categories"></artio-search-select><select aria-label="Sort products by" [(ngModel)]="catalogSort" (ngModelChange)="resetCatalogPage()"><option value="updatedAt">Recently updated</option><option value="createdAt">Recently created</option><option value="name">Name</option><option value="sku">SKU</option></select><select aria-label="Sort direction" [(ngModel)]="catalogDirection" (ngModelChange)="resetCatalogPage()"><option value="desc">Descending</option><option value="asc">Ascending</option></select><button class="link-button" (click)="rebuildSearchIndex()">Rebuild search index</button></div>@if (searchIndexStatus) {
  <p class="muted">{{searchIndexStatus}}</p>
  }@if (productImportReport) {
  <div class="panel"><div class="panel-heading"><div><p class="eyebrow">PRODUCT CSV IMPORT</p><h2>{{productImportReport.created}} created · {{productImportReport.updated}} updated · {{productImportReport.failed}} failed</h2></div><button class="link-button" (click)="productImportReport=null">Dismiss</button></div><p class="muted">{{productImportReport.rows}} rows processed</p>@for (item of productImportReport.errors; track item) {
  <div class="row"><span class="row-main"><strong>Row {{item.row}} · {{item.sku||'No SKU'}}</strong><small>{{item.reason}}</small></span></div>
}</div>
}<div class="panel table-panel"><table><thead><tr><th>PRODUCT</th><th>SKU / VARIANT</th><th>STATUS</th><th>ACTION</th></tr></thead><tbody>@for (product of filteredProducts; track product) {
<tr><td class="product-cell"><span class="product-monogram" aria-hidden="true">{{product.name.charAt(0).toUpperCase()}}</span><strong>{{product.name}}</strong><small>{{product.description}}</small><small>SEO: {{product.seoSlug||'Not set'}}</small></td><td>{{product.sku}}@for (variant of product.variants; track variant) {
<small>{{variant.name}} · {{variant.sku}} · {{variant.active?'Active':'Inactive'}} · {{variant.attributes|json}} @if (!product.archived) {
  <button class="link-button" (click)="editVariant(variant)">Edit options</button>
  }@if (!product.archived) {
  <button class="link-button" (click)="toggleVariant(variant)">{{variant.active?'Deactivate':'Activate'}}</button>
}</small>
}</td><td><span class="pill" [class.inactive]="!product.active">{{product.archived?'Archived':product.active?'Active':'Inactive'}}</span>@if (product.scheduledPublishAt) {
<small>Publishes {{product.scheduledPublishAt | date:'medium'}}</small>
}</td><td><details class="product-actions"><summary>Manage product <span>⌄</span></summary><div>@if (!product.archived) {
<button class="link-button" (click)="toggle(product)">{{product.active?'Deactivate':'Activate'}}</button>
}@if (!product.archived) {
<button class="link-button" (click)="archiveProduct(product)">Archive</button>
}@if (product.archived) {
<button class="link-button" (click)="restoreProduct(product)">Restore</button>
}@if (!product.archived) {
<button class="link-button" (click)="createVariant(product)">Add variant</button>
}@if (!product.active && !product.archived && !product.scheduledPublishAt) {
<button class="link-button" (click)="schedulePublication(product)">Schedule publish</button>
}@if (product.scheduledPublishAt) {
<button class="link-button" (click)="cancelPublication(product)">Cancel schedule</button>
}<button class="link-button" (click)="editSeo(product)">SEO</button><button class="link-button" (click)="mediaProduct=product">Media</button><button class="link-button" (click)="duplicateProduct(product)">Duplicate</button><button class="link-button" (click)="editTranslations(product)">Translations</button></div></details></td></tr>
}</tbody></table>@if (!filteredProducts.length) {
<p class="empty">No products found.</p>
}<div class="row"><span class="row-main">{{catalogTotal}} products · Page {{catalogPage+1}} of {{catalogPages}}</span><button class="link-button" [disabled]="catalogPage===0" (click)="previousCatalogPage()">Previous</button><button class="link-button" [disabled]="catalogPage+1>=catalogPages" (click)="nextCatalogPage()">Next</button></div></div>@if (translationProduct) {
<div class="panel"><div class="panel-heading"><div><p class="eyebrow">LOCALIZED CATALOG</p><h2>{{translationProduct.name}}</h2></div><button class="link-button" (click)="translationProduct=null">Close</button></div><form class="create-form" (ngSubmit)="saveTranslation()"><select [(ngModel)]="translationLocale" name="translationLocale" (ngModelChange)="loadTranslation()"><option value="de">German · de</option><option value="it">Italian · it</option><option value="fr">French · fr</option><option value="en">English · en</option></select><input [(ngModel)]="translationDraft.name" name="localizedName" placeholder="Translated product name" required><textarea [(ngModel)]="translationDraft.description" name="localizedDescription" placeholder="Translated description" rows="3"></textarea><label>Localized canonical slug<input [(ngModel)]="translationDraft.seoSlug" name="localizedSeoSlug" placeholder="e.g. lampe-de-bureau"></label><input [(ngModel)]="translationDraft.seoTitle" name="localizedSeoTitle" maxlength="180" placeholder="Localized SEO title"><textarea [(ngModel)]="translationDraft.seoDescription" name="localizedSeoDescription" maxlength="320" placeholder="Localized SEO description" rows="2"></textarea><button class="primary">Save translation</button></form></div>
}</section>
}
@if (tab==='bundles') {
  <section><div class="toolbar"><p>Build fixed sets or configurable bundles with validated component and pricing rules.</p><button type="button" class="primary" (click)="showBundle=!showBundle">＋ New bundle</button></div>@if (showBundle) {
  <form class="create-form" (ngSubmit)="createBundle()"><input [(ngModel)]="bundleDraft.name" name="bundleName" placeholder="Bundle name" maxlength="200" required><input [(ngModel)]="bundleDraft.sku" name="bundleSku" placeholder="Bundle SKU" maxlength="64" required><label>Bundle type<select [(ngModel)]="bundleDraft.type" name="bundleType" (ngModelChange)="bundleTypeChanged()"><option value="CONFIGURABLE">Configurable</option><option value="FIXED">Fixed</option></select></label>@if (bundleDraft.type==='FIXED') {
  <artio-sellable-select [(ngModel)]="bundleSellables" name="fixedSellables" label="Fixed bundle product variants" [multiple]="true"></artio-sellable-select>
  }@if (bundleDraft.type==='CONFIGURABLE') {
  @for (group of bundleGroups; track group; let i = $index) {
    <div class="panel"><div class="panel-heading"><div><p class="eyebrow">CONFIGURATOR GROUP {{i+1}}</p><h3>{{group.name||'New choice group'}}</h3></div><button type="button" class="link-button" (click)="removeBundleGroup(i)" [disabled]="bundleGroups.length===1">Remove group</button></div><div class="form-grid"><label>Group code<input [(ngModel)]="group.code" [name]="'bundleGroupCode'+i" pattern="[a-z][a-z0-9_-]{1,63}" maxlength="64" required></label><label>Customer label<input [(ngModel)]="group.name" [name]="'bundleGroupName'+i" maxlength="200" required></label><label>Minimum choices<input [(ngModel)]="group.minimumSelections" [name]="'bundleGroupMin'+i" type="number" min="0" [max]="group.sellables.length" required></label><label>Maximum choices<input [(ngModel)]="group.maximumSelections" [name]="'bundleGroupMax'+i" type="number" min="0" [max]="group.sellables.length" required></label></div><artio-sellable-select [(ngModel)]="group.sellables" [name]="'bundleGroupSellables'+i" [label]="'Choices for '+(group.name||'group '+(i+1))" [multiple]="true"></artio-sellable-select></div>
    }@if (bundleDraft.type==='CONFIGURABLE') {
    <button type="button" class="link-button" (click)="addBundleGroup()">＋ Add choice group</button>
  }
  }<label>Bundle pricing<select [(ngModel)]="bundleDraft.pricingMode" name="bundlePricingMode"><option value="COMPONENT_SUM">Sum of component prices</option><option value="FIXED_PRICE">Fixed bundle price</option><option value="PERCENTAGE_OFF_COMPONENTS">Percentage off components</option></select></label>@if (bundleDraft.pricingMode==='FIXED_PRICE') {
  <div class="form-grid"><label>Fixed price<input [(ngModel)]="bundleDraft.fixedAmount" name="bundleFixedAmount" type="number" min="0.01" step="0.01" required></label><label>Currency<artio-search-select [(ngModel)]="bundleDraft.currency" name="bundleCurrency" label="Fixed bundle currency" [options]="currencyOptions"></artio-search-select></label></div>
  }@if (bundleDraft.pricingMode==='FIXED_PRICE') {
  <div class="panel"><p class="muted">Optional country prices override the default amount and currency.</p>@for (price of bundleMarketPrices; track price; let i = $index) {
  <div class="form-grid"><label>Market<input [(ngModel)]="price.market" [name]="'bundleMarket'+i" pattern="[A-Za-z]{2}" maxlength="2" required></label><label>Amount<input [(ngModel)]="price.amount" [name]="'bundleMarketAmount'+i" type="number" min="0.01" step="0.01" required></label><label>Currency<artio-search-select [(ngModel)]="price.currency" [name]="'bundleMarketCurrency'+i" label="Market price currency" [options]="currencyOptions"></artio-search-select></label><button type="button" class="link-button" (click)="bundleMarketPrices.splice(i,1)">Remove</button></div>
  }<button type="button" class="link-button" (click)="addBundleMarketPrice()">＋ Add market price</button></div>
  }@if (bundleDraft.pricingMode==='PERCENTAGE_OFF_COMPONENTS') {
  <label>Component discount (%)<input [(ngModel)]="bundleDraft.discountPercentage" name="bundleDiscount" type="number" min="0" max="100" step="0.01" required></label>
  }<button class="primary">Create bundle</button></form>
  }<div class="bundle-list">@for (b of bundles; track b) {
  <article><div class="bundle-mark">✳</div><p class="eyebrow">{{b.type}} · {{b.pricingRule.mode}}</p><h2>{{b.name}}</h2><small>{{b.type==='FIXED'?b.components.length+' fixed component(s)':b.optionGroups.length+' choice group(s)'}}</small><span class="pill" [class.inactive]="!b.active">{{b.active?'Active':'Inactive'}}</span><button class="link-button" (click)="activateBundle(b)">{{b.active?'Deactivate':'Activate'}}</button><button class="link-button" (click)="editBundleTranslation(b)">Translations</button></article>
}</div>@if (bundleTranslationBundle) {
<div class="panel"><div class="panel-heading"><div><p class="eyebrow">LOCALIZED CONFIGURATOR</p><h2>{{bundleTranslationBundle.name}}</h2></div><button class="link-button" (click)="bundleTranslationBundle=null">Close</button></div><form class="create-form" (ngSubmit)="saveBundleTranslation()"><select [(ngModel)]="bundleTranslationLocale" name="bundleTranslationLocale" (ngModelChange)="loadBundleTranslation()"><option value="de">German · de</option><option value="it">Italian · it</option><option value="fr">French · fr</option><option value="en">English · en</option></select><input [(ngModel)]="bundleTranslationDraft.name" name="localizedBundleName" placeholder="Translated bundle name" required>@for (group of bundleTranslationBundle.optionGroups; track group) {
<label>{{group.name}}<input [(ngModel)]="bundleTranslationDraft.optionGroupNames[group.code]" [name]="'bundle-translation-'+group.code" placeholder="Translated option-group label" required></label>
}<button class="primary">Save translations</button></form></div>
}</section>
}
@if (tab==='extensions') {
  <section><div class="toolbar"><p>Register trusted extensions and manage schema-defined settings.</p><button class="primary" (click)="showExtensionRegistration=!showExtensionRegistration">＋ Register extension</button></div><div class="panel table-panel"><p class="eyebrow">PLUGIN DISCOVERY</p><h2>Regional catalog</h2><p class="muted">Trusted releases appear here when registered. Recommendations without a published digest remain visible and cannot be installed yet.</p><table><thead><tr><th>PLUGIN</th><th>REGION</th><th>CAPABILITY</th><th>STATUS</th></tr></thead><tbody>@for (item of extensionCatalog; track item) {
  <tr><td><strong>{{item.name}}</strong><small>{{item.pluginId}} · {{item.description}}</small></td><td>{{item.countryCode}} · {{item.optional?'Optional':'Recommended'}}</td><td>{{item.extensionPoints.join(', ')}}</td><td>{{item.status}}@if (item.version) {
  <small>v{{item.version}}</small>
  }@if (item.releaseRegistered && item.lifecycleState==='UNINSTALLED') {
  <button class="link-button" (click)="restoreCatalogPlugin(item)">Restore registration</button>
  }@if (item.releaseRegistered && item.lifecycleState!=='UNINSTALLED') {
  <button class="link-button" (click)="selectCatalogPlugin(item)">Manage release</button>
}</td></tr>
}</tbody></table>@if (!extensionCatalog.length) {
<p class="empty">No plugins are recommended for the packaged distributions.</p>
}</div>@if (error) {
<p class="error" role="alert">{{error}}</p>
}@if (extensionHealth) {
<p class="muted">{{extensionHealth}}</p>
}@if (showExtensionRegistration) {
<form class="create-form" (ngSubmit)="registerExtension()"><textarea [(ngModel)]="extensionManifestJson" name="extensionManifest" rows="10" placeholder="Paste extension manifest JSON" required></textarea><button class="primary">Validate and register</button></form>
}<div class="bundle-list">@for (extension of extensions; track extension) {
<article><div class="bundle-mark">⬡</div><p class="eyebrow">{{extension.id}}</p><h2>{{extension.name}}</h2><span class="pill">{{extension.lifecycleState}}</span><small>v{{extension.version}} · {{extension.configuredSecrets.length}} configured secret(s)</small><small>Availability: {{extension.geographicAvailability?.scope || 'GLOBAL'}}{{extension.geographicAvailability?.countries?.length ? ' · ' + extension.geographicAvailability.countries.join(', ') : ''}}</small>@if (extension.lifecycleState==='CONFIGURED' || extension.lifecycleState==='FAILED') {
<button class="link-button" (click)="extensionAction(extension,'install')">Install</button>
}@if (extension.lifecycleState==='INSTALLED' || extension.lifecycleState==='DISABLED') {
<button class="link-button" (click)="extensionAction(extension,'enable')">Enable</button>
}@if (extension.lifecycleState==='ENABLED') {
<button class="link-button" (click)="extensionAction(extension,'disable')">Disable</button>
}@if (extension.lifecycleState==='ENABLED' || extension.lifecycleState==='DISABLED' || extension.lifecycleState==='INSTALLED') {
<button class="link-button" (click)="rotateExtensionCredentials(extension)">Rotate credential</button>
}@if (extension.lifecycleState==='INSTALLED' || extension.lifecycleState==='DISABLED') {
<button class="link-button" (click)="updateExtension(extension)">Update</button>
}@if (extension.lifecycleState==='INSTALLED' || extension.lifecycleState==='DISABLED') {
<button class="link-button" (click)="rollbackExtension(extension)">Rollback</button>
}<button class="link-button" (click)="checkExtensionHealth(extension)">Health</button>@if (extension.id==='observability.artio' && extension.lifecycleState==='ENABLED') {
<button class="link-button" (click)="openObservability()">Open dashboards</button>
}<button class="link-button" (click)="selectExtension(extension)">Configure</button><button class="link-button" (click)="loadExtensionAudit(extension)">Activity</button>@if (extension.lifecycleState!=='UNINSTALLED') {
<button class="link-button danger" (click)="uninstallExtension(extension)">Uninstall</button>
}</article>
}</div>@if (extensionAuditFor) {
<div class="panel table-panel"><div class="panel-heading"><div><p class="eyebrow">EXTENSION ACTIVITY</p><h2>{{extensionAuditFor}}</h2></div><button class="link-button" (click)="extensionAuditFor=null;extensionAudit=[]">Close</button></div><p class="muted">Recent administrative lifecycle and invocation events. Secret values and request bodies are excluded.</p><table><thead><tr><th>TIME</th><th>ACTOR</th><th>ACTION</th><th>DETAILS</th></tr></thead><tbody>@for (event of extensionAudit; track event) {
<tr><td>{{event.occurredAt | date:"medium"}}</td><td>{{event.actorUserId}}</td><td>{{event.action}}</td><td>{{event.details | json}}</td></tr>
}</tbody></table>@if (extensionAudit.length===0) {
<p class="empty">No activity recorded.</p>
}</div>
}@if (selectedExtension?.lifecycleState==='ENABLED') {
<div class="panel"><p class="eyebrow">LIVE DIAGNOSTIC</p><h2>Invoke extension</h2><p class="muted">This sends a real request to the enabled extension. Use a non-destructive operation and sample payload.</p><form class="create-form" (ngSubmit)="invokeExtension()"><select [(ngModel)]="extensionInvocation.extensionPoint" name="extensionPoint" required>@for (point of selectedExtension.manifest.extensionPoints; track point) {
<option [value]="point">{{point}}</option>
}</select><input [(ngModel)]="extensionInvocation.operation" name="operation" placeholder="Operation" required><textarea [(ngModel)]="extensionInvocation.payloadJson" name="payload" rows="5" required></textarea><button class="primary">Send diagnostic request</button></form>@if (extensionInvocationResult) {
<pre>{{extensionInvocationResult | json}}</pre>
}</div>
}@if (selectedExtension) {
<div class="panel"><div class="panel-heading"><div><p class="eyebrow">EXTENSION SETTINGS</p><h2>{{selectedExtension.manifest.name}}</h2></div><button class="link-button" (click)="selectedExtension=null">Close</button></div><form class="create-form" (ngSubmit)="saveExtensionConfiguration()">@for (field of extensionFields(); track field) {
<div class="create-field"><span>{{field.value.label || field.key}}</span>@if (field.value.description) {
<small>{{field.value.description}}</small>
}@if (field.value.type==='enum') {
<select [(ngModel)]="extensionConfig[field.key]" [name]="'extension-'+field.key">@for (option of field.value.options; track option) {
  <option [value]="option">{{option}}</option>
}</select>
}@if (field.value.type==='secret') {
<input type="password" autocomplete="new-password" [(ngModel)]="extensionConfig[field.key]" [name]="'extension-'+field.key" [placeholder]="selectedExtension.configuredSecrets.includes(field.key) ? 'Configured — leave blank to keep existing value' : 'Secret value'" [required]="field.value.required && !selectedExtension.configuredSecrets.includes(field.key)">
}@if (field.value.type==='text' || field.value.type==='number' || field.value.type==='url') {
<input [type]="field.value.type==='number'?'number':'text'" [(ngModel)]="extensionConfig[field.key]" [name]="'extension-'+field.key" [required]="field.value.required">
}@if (field.value.type==='json') {
<textarea [(ngModel)]="extensionConfig[field.key]" [name]="'extension-'+field.key" rows="4" [required]="field.value.required"></textarea>
}@if (field.value.type==='boolean') {
<label><input type="checkbox" [(ngModel)]="extensionConfig[field.key]" [name]="'extension-'+field.key"> Enabled</label>
}</div>
}<button class="primary">Save configuration</button></form></div>
}</section>
}
@if (tab==='system' && isSuperadmin) {
  <section><artio-license-admin></artio-license-admin><div class="panel"><p class="eyebrow">SYSTEM SETTINGS</p><h2>Installation configuration</h2><p class="muted">Changes are restricted to the system owner and recorded in the audit history.</p><form class="create-form" (ngSubmit)="saveSystemSettings()"><label>Store name<input [(ngModel)]="systemSettings.storeName" name="storeName" maxlength="120" required></label><div class="form-grid"><label>Country<artio-search-select [(ngModel)]="systemSettings.countryCode" name="countryCode" label="Store country" [options]="countryOptions"></artio-search-select></label><label>Currency<artio-search-select [(ngModel)]="systemSettings.currencyCode" name="currencyCode" label="Store currency" [options]="currencyOptions"></artio-search-select></label></div><div class="form-grid"><label>Default locale<artio-search-select [(ngModel)]="systemSettings.locale" name="locale" label="Store default locale" [options]="localeOptions"></artio-search-select></label><label>Time zone<artio-search-select [(ngModel)]="systemSettings.timezone" name="timezone" label="Store time zone" [options]="timezoneOptions"></artio-search-select></label></div><label>Supported languages<artio-search-select [(ngModel)]="systemSelectedLanguages" name="systemLanguages" label="Store content languages" [options]="storeLanguageOptions" [multiple]="true" placeholder="Choose languages"></artio-search-select><small>Comma-separated BCP 47 language tags.</small></label><label>External store URL<input [(ngModel)]="systemSettings.externalBaseUrl" name="externalBaseUrl" type="url" placeholder="https://shop.example.com"><small>HTTPS is required outside local development.</small></label><p class="muted">Distribution: {{systemSettings.distributionId}}</p><button class="primary">Save installation settings</button></form><button class="link-button" (click)="navigateTo('extensions')">Open Plugin Manager</button></div><div class="panel table-panel"><p class="eyebrow">REGIONAL PLUGIN PROVISIONING</p><h2>Recommended integrations</h2><p class="muted">Optional plugins are installed disabled. Enable them only after reviewing their settings. Missing releases stay unavailable until a trusted digest-pinned manifest is registered.</p><table><thead><tr><th>PLUGIN</th><th>STATUS</th><th>RECOMMENDATION</th></tr></thead><tbody>@for (item of distributionPlugins; track item) {
  <tr><td>{{item.pluginId}}</td><td>{{item.status}}</td><td>{{item.reason}}</td></tr>
}</tbody></table>@if (!distributionPlugins.length) {
<p class="empty">No regional plugin recommendations are configured.</p>
}<button class="link-button" (click)="refreshDistributionPlugins()">Refresh provisioning status</button></div><div class="panel table-panel"><p class="eyebrow">SYSTEM SETTINGS AUDIT</p><h2>Recent changes</h2><table><thead><tr><th>TIME</th><th>ACTOR</th><th>METHOD</th><th>STATUS</th></tr></thead><tbody>@for (event of systemSettingsAudit; track event) {
<tr><td>{{event.occurredAt | date:"medium"}}</td><td>{{event.userId}}</td><td>{{event.action}} {{event.path}}</td><td>{{event.status}}</td></tr>
}</tbody></table>@if (!systemSettingsAudit.length) {
<p class="empty">No settings changes recorded.</p>
}</div></section>
}@if (tab==='pricing') {
<section><div class="toolbar"><p>Manage selling prices by market and channel.</p><button class="primary" (click)="showPrice=!showPrice">＋ Add price</button></div>@if (showPrice) {
<form class="create-form" (ngSubmit)="createPrice()"><artio-sellable-select [(ngModel)]="priceDraft.sellableId" name="sellable" label="Priced product variant"></artio-sellable-select><input [(ngModel)]="priceDraft.amount" name="amount" type="number" placeholder="Amount" required><artio-search-select [(ngModel)]="priceDraft.currency" name="currency" label="Price currency" [options]="currencyOptions"></artio-search-select><input [(ngModel)]="priceDraft.market" name="market" placeholder="US" required><input [(ngModel)]="priceDraft.channel" name="channel" placeholder="WEB" required><label><input type="checkbox" [(ngModel)]="priceDraft.pricesIncludeTax" name="pricesIncludeTax"> Price includes tax</label><button class="primary">Save price</button></form>
}<div class="panel table-panel"><table><thead><tr><th>SELLABLE</th><th>MARKET / CHANNEL</th><th>PRICE</th><th>TAX MODE</th><th>STATUS</th></tr></thead><tbody>@for (p of prices; track p) {
<tr><td>{{p.sellableId}}</td><td>{{p.market}} / {{p.channel}}</td><td>{{p.amount}} {{p.currency}}</td><td>{{p.pricesIncludeTax?'Tax inclusive':'Tax exclusive'}}</td><td>{{p.active?'Active':'Inactive'}}</td></tr>
}</tbody></table></div></section>
}
@if (tab==='pricing') {
  <section><div class="panel"><div class="panel-heading"><div><p class="eyebrow">TAX POLICY</p><h2>Sellable tax categories</h2></div></div><p class="muted">Assign categories using your merchant tax policy. Classification is not inferred from product text.</p><form class="create-form" (ngSubmit)="assignTaxCategory()"><artio-sellable-select [(ngModel)]="taxAssignment.sellableId" name="taxSellableId" label="Tax category product variant"></artio-sellable-select><select [(ngModel)]="taxAssignment.category" name="taxCategory"><option value="STANDARD">Standard</option><option value="REDUCED">Reduced</option><option value="ACCOMMODATION">Accommodation</option></select><button class="primary">Assign category</button></form>@if (taxRules.length) {
  <div class="table-panel"><table><thead><tr><th>JURISDICTION</th><th>CATEGORY</th><th>RATE</th><th>VALID FROM</th><th>STATUS</th></tr></thead><tbody>@for (rule of taxRules; track rule) {
  <tr><td>{{rule.jurisdiction}}</td><td>{{rule.category}}</td><td>{{rule.percentage}}%</td><td>{{rule.validFrom}}</td><td>{{rule.active?'Active':'Inactive'}}</td></tr>
}</tbody></table></div>
}@if (sellableTaxCategories.length) {
<div class="table-panel"><table><thead><tr><th>SELLABLE ID</th><th>ASSIGNED CATEGORY</th></tr></thead><tbody>@for (assignment of sellableTaxCategories; track assignment) {
<tr><td>{{assignment.sellableId}}</td><td>{{assignment.category}}</td></tr>
}</tbody></table></div>
}</div></section>
}
@if (tab==='inventory' || tab==='orders') {
  <section><div class="panel"><p class="eyebrow">{{tab==='inventory'?'STOCK OPERATIONS':'FULFILMENT'}}</p><h2>{{tab==='inventory'?'Inventory overview':'Orders'}}</h2><p class="muted">{{tab==='inventory'?'Warehouse and stock balances are managed through the inventory API.':'Search, review and fulfil customer orders.'}}</p>@if (tab==='orders') {
  <form class="toolbar" (ngSubmit)="searchOrders()"><input [(ngModel)]="ordersQuery" name="orderQuery" maxlength="200" placeholder="Order ID, customer email or tracking"><select [(ngModel)]="ordersStatus" name="orderStatus"><option value="">All statuses</option>@for (status of orderStatuses; track status) {
  <option [value]="status">{{status}}</option>
  }</select><button class="primary">Search</button><button type="button" class="link-button" (click)="exportOrders()">Export CSV</button></form>
  }@if (tab==='orders' && ordersLoading) {
  <p class="muted" role="status">Loading orders…</p>
  }@if (tab==='orders' && ordersError) {
  <p class="error" role="alert">{{ordersError}}</p>
  }@if (tab==='orders' && !ordersLoading && !ordersError) {
  <p class="muted">{{orderTotal}} orders · page {{orderPage+1}} of {{orderPages||1}}</p>
  }@for (order of orders; track order) {
  <div class="row"><span class="row-main"><strong>{{order.id}}</strong><small>{{order.status}} · {{order.createdAt | date:'medium'}} · {{order.customerEmail}} {{order.trackingNumber?'· '+order.trackingNumber:''}}</small></span><b>{{order.total}} {{order.currency}}</b>@if (tab==='orders') {
  <button class="link-button" (click)="viewOrder(order)">Details</button>
  }@if (tab==='orders' && (order.status==='PAID' || order.status==='CONFIRMED')) {
  <button class="link-button" (click)="shipOrder(order)">{{order.shippingMethodId?'Create label & ship':'Ship'}}</button>
  }@if (tab==='orders' && order.shippingLabelAvailable) {
  <button class="link-button" (click)="openShippingLabel(order)">Shipping label ↗</button>
  }@if (tab==='orders' && order.status==='SHIPPED') {
  <button class="link-button" (click)="deliverOrder(order)">Mark delivered</button>
  }@if (tab==='orders' && order.trackingNumber && order.status==='SHIPPED') {
  <button class="link-button" (click)="refreshOrderTracking(order)">Refresh tracking</button>
}</div>
}@if (tab==='orders' && !ordersLoading && !ordersError && !orders.length) {
<p class="empty">No orders match these filters.</p>
}@if (tab==='orders') {
<div class="toolbar"><button class="link-button" [disabled]="orderPage===0" (click)="loadOrders(orderPage-1)">← Previous</button><button class="link-button" [disabled]="orderPage+1>=orderPages" (click)="loadOrders(orderPage+1)">Next →</button></div>
}@if (tab==='inventory') {
<a class="link-button" href="/api/v1/warehouses" target="_blank">Open warehouse API ↗</a>
}</div>@if (orderDetailLoading) {
<p class="muted" role="status">Loading order details…</p>
}@if (selectedOrder) {
<div class="panel"><div class="panel-heading"><div><p class="eyebrow">ORDER DETAIL</p><h2>{{selectedOrder.id}}</h2></div><button class="link-button" (click)="selectedOrder=null">Close</button></div><p><strong>{{selectedOrder.status}}</strong> · {{selectedOrder.customerEmail}} · {{selectedOrder.createdAt | date:'medium'}}</p><p>Subtotal {{selectedOrder.subtotal}} + tax {{selectedOrder.tax}} + shipping {{selectedOrder.shipping}} = <strong>{{selectedOrder.total}} {{selectedOrder.currency}}</strong></p><p>Ship to: {{selectedOrder.shippingAddress}}</p><p>Bill to: {{selectedOrder.billingAddress}}</p>@if (selectedOrder.trackingNumber) {
<p>Tracking: {{selectedOrder.trackingNumber}} · {{selectedOrder.shipmentReference}}</p>
}<h3>Items</h3>@for (line of selectedOrder.lines; track line) {
<div class="row"><span>{{line.variantId}} {{line.bundleId?'· bundle '+line.bundleId:''}} · quantity {{line.quantity}}</span><b>{{line.unitPrice}} {{line.currency}}</b></div>
}<h3>Shipments</h3>@for (shipment of selectedShipments; track shipment) {
<div class="row"><span>{{shipment.trackingNumber}} · {{shipment.items.length}} line(s)</span><small>{{shipment.orderStatus}}</small></div>
}@if (selectedOrder.status==='PAID' || selectedOrder.status==='CONFIRMED' || selectedOrder.status==='PARTIALLY_SHIPPED') {
<form class="create-form" (ngSubmit)="createShipment()"><label>Tracking number<input [(ngModel)]="shipmentTrackingNumber" name="shipmentTracking" maxlength="255" required></label>@for (line of selectedOrder.lines; track line) {
<div class="row"><span class="row-main">{{line.variantId}} · {{line.shippedQuantity}} of {{line.quantity}} shipped</span><label>Ship now<input type="number" min="0" step="0.0001" [max]="line.quantity-line.shippedQuantity" [(ngModel)]="line.shipQuantity" [name]="'ship-'+line.id"></label></div>
}<button class="primary" [disabled]="!shipmentTrackingNumber.trim()">Create shipment</button></form>
}<h3>Payments</h3>@for (payment of selectedOrder.payments; track payment) {
<div class="row"><span>{{payment.method}} · {{payment.status}} · {{payment.providerReference}} · refunded {{payment.refundedAmount}} {{payment.currency}}</span><b>{{payment.amount}} {{payment.currency}}</b>@if (payment.status==='CAPTURED' || payment.status==='PARTIALLY_REFUNDED') {
<button type="button" class="link-button" (click)="refundPayment(payment)">{{t('refundOpen')}}</button>
}</div>
}<h3>Returns</h3>@for (item of selectedReturns; track item) {
<div class="row"><span class="row-main"><strong>{{item.status}}</strong><small>{{item.reason}} · {{item.createdAt | date:'medium'}} · @for (line of item.items; track line) {
<span>{{line.quantity}} × {{line.orderItemId}} </span>
}</small>@if (item.staffNote) {
<small>Staff note: {{item.staffNote}}</small>
}</span>@if (item.status==='REQUESTED') {
<button type="button" class="link-button" (click)="openReturnAction(item,'review',true)">Approve</button>
}@if (item.status==='REQUESTED') {
<button type="button" class="link-button" (click)="openReturnAction(item,'review',false)">Reject</button>
}@if (item.status==='APPROVED') {
@if (orderWarehouses.length) {
  <label>Restock warehouse<artio-search-select [(ngModel)]="item.receiveWarehouseId" [name]="'return-warehouse-'+item.id" [options]="warehouseOptions" label="Restocking warehouse" placeholder="No restocking"></artio-search-select></label>
  }@if (orderWarehouses.length) {
  <button class="link-button" [disabled]="!item.receiveWarehouseId" (click)="openReturnAction(item,'receive',true)">Receive &amp; restock</button>
  }<button class="link-button" (click)="openReturnAction(item,'receive',false)">Receive without restocking</button>
}</div>
}@if (!selectedReturns.length) {
<p class="muted">No return requests for this order.</p>
}<pre>{{selectedOrder.taxSnapshot | json}}</pre><h3>Timeline & staff notes</h3>@for (event of selectedTimeline; track event) {
<div class="row"><span class="row-main"><strong>{{event.eventType}}</strong><small>{{event.message}} · {{event.occurredAt | date:'medium'}}</small></span><span class="muted">{{event.customerVisible?'Customer visible':'Internal'}}</span></div>
}<form class="toolbar" (ngSubmit)="addOrderNote()"><input [(ngModel)]="orderNote" name="orderNote" maxlength="2000" placeholder="Internal staff note"><button class="primary" [disabled]="!orderNote.trim()">Add note</button></form></div>
}</section>
}
@if (mediaProduct) {
  @defer (when mediaProduct !== null) { <artio-product-media-admin [productId]="mediaProduct.id" [productName]="mediaProduct.name" (close)="mediaProduct=null"></artio-product-media-admin> } @placeholder { <p class="muted" role="status">Loading product media…</p> }
  }@if (tab==='catalogData') {
  @defer (when tab==='catalogData') { <artio-catalog-metadata-admin></artio-catalog-metadata-admin> } @placeholder { <p class="muted" role="status">Loading catalog data…</p> }
  }@if (tab==='inventory') {
  @defer (when tab==='inventory') { <artio-inventory-admin></artio-inventory-admin> } @placeholder { <p class="muted" role="status">Loading inventory…</p> }
  }@if (tab==='giftCards') {
  @defer (when tab==='giftCards') { <artio-gift-card-admin></artio-gift-card-admin> } @placeholder { <p class="muted" role="status">Loading gift cards…</p> }
  }@if (tab==='operations') {
  <section><div class="panel"><div class="panel-heading"><div><p class="eyebrow">DELIVERY HEALTH</p><h2>Outbox queues</h2></div><button class="link-button" (click)="loadOutbox()">Refresh</button></div>@if (outboxDiagnostics) {
  <div class="metrics"><article><span>Pending events</span><strong>{{outboxDiagnostics.eventPending}}</strong><small>{{outboxDiagnostics.eventStaleClaims}} stale claims</small></article><article><span>Dead events</span><strong>{{outboxDiagnostics.eventDead}}</strong><small>Review and replay below</small></article><article><span>Pending email</span><strong>{{outboxDiagnostics.emailPending}}</strong><small>{{outboxDiagnostics.emailStaleClaims}} stale claims</small></article><article><span>Dead email</span><strong>{{outboxDiagnostics.emailDead}}</strong><small>Requires operator review</small></article><article><span>Webhook receipts</span><strong>{{outboxDiagnostics.webhookUnprocessed}}</strong><small>{{outboxDiagnostics.webhookOlderThanRetention}} past retention</small></article></div>
  }@if (outboxDeadEvents.length) {
  <div class="panel table-panel"><h3>Dead-letter events</h3><table><thead><tr><th>TYPE</th><th>AGGREGATE</th><th>ATTEMPTS</th><th>ERROR CLASS</th><th>ACTION</th></tr></thead><tbody>@for (event of outboxDeadEvents; track event) {
  <tr><td>{{event.eventType}}</td><td>{{event.aggregateId}}</td><td>{{event.attempts}}</td><td>{{event.errorClass}}</td><td><button class="link-button" (click)="replayOutboxEvent(event)">Replay</button></td></tr>
}</tbody></table></div>
}<div class="panel"><div class="panel-heading"><div><p class="eyebrow">KAFKA</p><h2>{{t('kafkaConsumerHealth')}}</h2></div></div>@if (kafkaConsumerLag) {
<p>{{kafkaConsumerLag.group}} · {{kafkaConsumerLag.topic}} · {{kafkaConsumerLag.totalLag}} {{t('kafkaMessagesBehind')}}</p>
}@if (kafkaConsumerLag.partitions.length) {
<table><thead><tr><th>{{t('kafkaPartition')}}</th><th>{{t('kafkaCommitted')}}</th><th>{{t('kafkaEnd')}}</th><th>{{t('kafkaLag')}}</th></tr></thead><tbody>@for (partition of kafkaConsumerLag.partitions; track partition) {
<tr><td>{{partition.partition}}</td><td>{{partition.committedOffset}}</td><td>{{partition.endOffset}}</td><td>{{partition.lag}}</td></tr>
}</tbody></table>
}@if (!kafkaConsumerLag) {
<p class="empty">{{t('kafkaLagUnavailable')}}</p>
}<div class="metrics"><article><span>Dead letters</span><strong>{{kafkaDeadLetters.dead}}</strong></article><article><span>Replay pending</span><strong>{{kafkaDeadLetters.replayPending}}</strong></article><article><span>Replayed</span><strong>{{kafkaDeadLetters.replayed}}</strong></article></div>@if (kafkaDeadLetterItems.length) {
<div class="panel table-panel"><h3>Kafka consumer dead letters</h3><table><thead><tr><th>EVENT</th><th>SOURCE</th><th>OFFSET</th><th>FAILURE</th><th>ATTEMPTS</th><th>ACTION</th></tr></thead><tbody>@for (event of kafkaDeadLetterItems; track event) {
<tr><td>{{event.eventType||event.eventId||'Unknown event'}}</td><td>{{event.sourceTopic}} / {{event.partition}}</td><td>{{event.offset}}</td><td>{{event.errorClass}}</td><td>{{event.replayAttempts}}</td><td>@if (event.status==='DEAD') {
<button class="link-button" (click)="replayKafkaDeadLetter(event)">Replay</button>
}@if (event.status!=='DEAD') {
<span>{{event.status}}</span>
}</td></tr>
}</tbody></table></div>
}</div>@if (!outboxDiagnostics) {
<p class="empty">Loading delivery diagnostics…</p>
}</div></section>
}@if (tab==='users') {
<section><div class="toolbar"><label class="search">⌕ <input [(ngModel)]="usersQuery" (ngModelChange)="loadUsers()" placeholder="Search name or email"></label><select [(ngModel)]="usersActivity" (ngModelChange)="loadUsers()"><option value="ALL">All activity</option><option value="RECENT">Signed in within 30 days</option><option value="STALE">No sign-in in 30+ days</option><option value="NEVER">Never signed in</option></select></div><div class="panel table-panel"><table><thead><tr><th>CUSTOMER</th><th>ROLE</th><th>VERIFIED</th><th>STATUS</th><th>LAST SIGN-IN</th><th>ACTION</th></tr></thead><tbody>@for (user of users; track user) {
<tr><td><strong>{{user.firstName}} {{user.lastName}}</strong><small>{{user.email}}</small></td><td><strong>{{user.role}}</strong>@if (user.role==='ADMIN') {
<small>{{user.permissions?.length ? user.permissions.join(', ') : 'No assigned permissions'}}</small>
}</td><td>{{user.emailVerified?'Yes':'No'}}</td><td>{{user.active?'Active':'Disabled'}}</td><td>{{user.lastLogin?(user.lastLogin | date:'medium'):'Never'}}</td><td><button class="link-button" (click)="setUserActive(user)">{{user.active?'Disable':'Enable'}}</button>@if (!user.emailVerified) {
<button class="link-button" (click)="verifyUser(user)">Verify email</button>
}@if (isSuperadmin) {
<button class="link-button" (click)="editUserAccess(user)">Manage access</button>
}<button class="link-button" (click)="viewLoyalty(user)">Loyalty</button></td></tr>
}</tbody></table></div>@if (accessUser) {
<div class="panel"><div class="panel-heading"><div><p class="eyebrow">ACCOUNT ACCESS</p><h2>{{accessUser.email}}</h2></div><button type="button" class="link-button" (click)="accessUser=null">Close</button></div><div class="form-grid"><label>Role<select [(ngModel)]="accessRole" name="accessRole"><option value="CUSTOMER">Customer</option><option value="ADMIN">Administrator</option><option value="SUPERADMIN">Superadministrator</option></select></label><p class="muted">Changing an account away from Administrator clears its stored permissions.</p></div><fieldset class="permission-list" [disabled]="accessRole!=='ADMIN'"><legend>Administrator permissions</legend>@for (permission of permissionOptions; track permission) {
<label><input type="checkbox" [checked]="accessPermissions.includes(permission)" (change)="setAccessPermission($event,permission)">{{permission}}</label>
}</fieldset><button class="primary" type="button" (click)="saveUserAccess()">Save access</button></div>
}@if (selectedLoyalty) {
<div class="panel"><div class="panel-heading"><div><p class="eyebrow">LOYALTY ACCOUNT</p><h2>{{selectedLoyalty.user.firstName}} {{selectedLoyalty.user.lastName}}</h2></div><button class="link-button" (click)="selectedLoyalty=null">Close</button></div><p>{{selectedLoyalty.account.balance.points}} points · {{selectedLoyalty.account.balance.tier}}</p><button class="primary" (click)="adjustLoyalty()" [disabled]="!enterpriseFeatures.includes('LOYALTY')" [title]="enterpriseFeatures.includes('LOYALTY')?'':'A LOYALTY Enterprise license is required for new adjustments.'">Adjust points</button>@if (!enterpriseFeatures.includes('LOYALTY')) {
<small class="muted">Existing balances and refunds remain available. A LOYALTY Enterprise license is required for new adjustments.</small>
}@for (transaction of selectedLoyalty.account.transactions; track transaction) {
<div class="row"><span>{{transaction.kind}} · {{transaction.orderId||transaction.refundId||'manual'}}</span><b>{{transaction.points}}</b></div>
}</div>
}</section>
}@if (error) {
<p class="error" role="alert">{{error}}</p>
}@if (success) {
<p class="notice" role="status">{{success}}</p>
}@if (refundDialogPayment) {
<dialog #refundDialog class="refund-dialog" aria-labelledby="refund-dialog-title" aria-describedby="refund-dialog-description" (cancel)="cancelRefundDialog($event)" (close)="refundDialogClosed()">
  <form class="refund-dialog-content" (ngSubmit)="submitRefund()">
    <p class="eyebrow">{{t('refundEyebrow')}}</p>
    <h2 id="refund-dialog-title">{{t('refundTitle')}}</h2>
    <p id="refund-dialog-description" class="muted">{{t('refundDescription')}}</p>
    <p class="refund-payment-summary"><strong>{{refundDialogPayment.amount}} {{refundDialogPayment.currency}}</strong><span>{{t('refundAlready')}}: {{refundDialogPayment.refundedAmount}} {{refundDialogPayment.currency}}</span></p>
    <label for="refund-amount">{{t('refundAmount')}} ({{refundDialogPayment.currency}})</label>
    <input id="refund-amount" name="refundAmount" type="number" min="0.01" step="any" [(ngModel)]="refundDialogAmount" [placeholder]="t('refundRemainingPlaceholder')" [disabled]="refundSubmitting" autofocus>
    <small class="muted">{{t('refundAmountHelp')}}</small>
    @if (refundDialogError) { <p class="error" role="alert">{{refundDialogError}}</p> }
    @if (refundSubmitting) { <p class="muted" role="status">{{t('refundSubmitting')}}</p> }
    <div class="refund-dialog-actions">
      <button type="button" class="secondary" [disabled]="refundSubmitting" (click)="closeRefundDialog()">{{t('refundCancel')}}</button>
      <button type="submit" class="primary" [disabled]="refundSubmitting">{{t('refundSubmit')}}</button>
    </div>
  </form>
</dialog>
}@if (returnDialogAction) {
<dialog #returnDialog class="refund-dialog" aria-labelledby="return-dialog-title" (cancel)="cancelReturnDialog($event)" (close)="returnDialogClosed()">
  <form class="refund-dialog-content" (ngSubmit)="submitReturnAction()">
    <p class="eyebrow">{{t('returnDialogEyebrow')}}</p>
    @if (returnDialogAction.kind==='review') {
      <h2 id="return-dialog-title">{{returnDialogAction.approve?t('returnApproveTitle'):t('returnRejectTitle')}}</h2>
    } @else {
      <h2 id="return-dialog-title">{{returnDialogAction.restock?t('returnRestockTitle'):t('returnReceiveTitle')}}</h2>
    }
    @if (returnDialogAction.item.reason) { <p class="refund-payment-summary"><strong>{{t('returnCustomerReason')}}:</strong> <span>{{returnDialogAction.item.reason}}</span></p> }
    @if (returnDialogAction.kind==='receive' && returnDialogAction.restock) {
      <p class="refund-payment-summary"><strong>{{t('returnDialogWarehouse')}}:</strong> <span>{{warehouseName(returnDialogAction.item.receiveWarehouseId)}}</span></p>
    }
    <label for="return-staff-note">{{t('returnDialogNote')}}</label>
    <textarea id="return-staff-note" name="returnStaffNote" rows="4" maxlength="2000" [(ngModel)]="returnDialogNote" [disabled]="returnDialogBusy" autofocus></textarea>
    @if (returnDialogError) { <p class="error" role="alert">{{returnDialogError}}</p> }
    @if (returnDialogBusy) { <p class="muted" role="status">{{t('returnDialogBusy')}}</p> }
    <div class="refund-dialog-actions">
      <button type="button" class="secondary" [disabled]="returnDialogBusy" (click)="closeReturnDialog()">{{t('refundCancel')}}</button>
      <button type="submit" class="primary" [disabled]="returnDialogBusy || (returnDialogAction.restock && !returnDialogAction.item.receiveWarehouseId)">{{t('returnDialogConfirm')}}</button>
    </div>
  </form>
</dialog>
}@if (shippingDialogOrder) {
<dialog #shippingDialog class="refund-dialog" aria-labelledby="shipping-dialog-title" aria-describedby="shipping-dialog-description" (cancel)="cancelShippingDialog($event)" (close)="shippingDialogClosed()">
  <form class="refund-dialog-content" (ngSubmit)="submitShipping()">
    <p class="eyebrow">{{t('shippingDialogEyebrow')}}</p>
    <h2 id="shipping-dialog-title">{{t('shippingDialogTitle')}}</h2>
    <p id="shipping-dialog-description" class="muted">{{shippingDialogOrder.shippingMethodId?t('shippingLabelDescription'):t('shippingManualDescription')}}</p>
    <p class="refund-payment-summary"><strong>{{shippingDialogOrder.id}}</strong><span>{{shippingDialogOrder.customerEmail}}</span></p>
    @if (!shippingDialogOrder.shippingMethodId) {
      <label for="shipping-tracking-number">{{t('shippingTrackingNumber')}}</label>
      <input id="shipping-tracking-number" name="shippingTrackingNumber" [(ngModel)]="shippingDialogTrackingNumber" maxlength="200" required [disabled]="shippingDialogBusy" autofocus>
    }
    @if (shippingDialogError) { <p class="error" role="alert">{{shippingDialogError}}</p> }
    @if (shippingDialogBusy) { <p class="muted" role="status">{{t('shippingDialogBusy')}}</p> }
    <div class="refund-dialog-actions">
      <button type="button" class="secondary" [disabled]="shippingDialogBusy" (click)="closeShippingDialog()">{{t('refundCancel')}}</button>
      <button type="submit" class="primary" [disabled]="shippingDialogBusy || (!shippingDialogOrder.shippingMethodId && !shippingDialogTrackingNumber.trim())">{{t('shippingDialogConfirm')}}</button>
    </div>
  </form>
</dialog>
}@if (replayDialog) {
<dialog #replayDialogElement class="refund-dialog" aria-labelledby="replay-dialog-title" (cancel)="cancelReplayDialog($event)" (close)="replayDialogClosed()">
  <form class="refund-dialog-content" (ngSubmit)="submitReplay()">
    <p class="eyebrow">{{t('replayDialogEyebrow')}}</p>
    <h2 id="replay-dialog-title">{{t('replayDialogTitle')}}</h2>
    <p class="muted">{{replayDialog.kind==='kafka'?t('replayKafkaDescription'):t('replayOutboxDescription')}}</p>
    <p class="refund-payment-summary"><strong>{{replayDialog.event.eventId??replayDialog.event.id}}</strong></p>
    @if (replayDialogError) { <p class="error" role="alert">{{replayDialogError}}</p> }
    @if (replayDialogBusy) { <p class="muted" role="status">{{t('replayDialogBusy')}}</p> }
    <div class="refund-dialog-actions">
      <button type="button" class="secondary" [disabled]="replayDialogBusy" (click)="closeReplayDialog()">{{t('refundCancel')}}</button>
      <button type="submit" class="primary" [disabled]="replayDialogBusy">{{t('replayDialogConfirm')}}</button>
    </div>
  </form>
</dialog>
}</main></div>
}`
})
export class AdminComponent {
 @ViewChild('refundDialog') private refundDialogRef?:ElementRef<HTMLDialogElement>;
 @ViewChild('returnDialog') private returnDialogRef?:ElementRef<HTMLDialogElement>;
 @ViewChild('shippingDialog') private shippingDialogRef?:ElementRef<HTMLDialogElement>;
 @ViewChild('replayDialogElement') private replayDialogRef?:ElementRef<HTMLDialogElement>;
 distributionPlugins:any[]=[];extensionCatalog:any[]=[];
 systemSettings:any={storeName:"Artio Store",countryCode:"CH",currencyCode:"CHF",locale:"de-CH",timezone:"Europe/Zurich",externalBaseUrl:"",distributionId:"switzerland-v1"};systemLanguages="de,fr,it,en";systemSelectedLanguages=["de","fr","it","en"];storeLanguageOptions=LANGUAGE_OPTIONS;countryOptions=COUNTRY_OPTIONS;currencyOptions=CURRENCY_OPTIONS;localeOptions=LOCALE_OPTIONS;timezoneOptions=TIMEZONE_OPTIONS;systemSettingsAudit:any[]=[];outboxDiagnostics:any=null;outboxDeadEvents:any[]=[];kafkaDeadLetters:any={dead:0,replayPending:0,replayed:0};kafkaDeadLetterItems:any[]=[];kafkaConsumerLag:any=null;setupChecked=false;setupRequired=false;developerMode=false;
 bundleTranslationBundle:any=null;bundleTranslationLocale='de';bundleTranslationDraft:any={name:'',optionGroupNames:{}};bundleGroups:any[]=[{code:'choices',name:'Choose your items',minimumSelections:1,maximumSelections:1,sellables:[]}];
 productImportReport:any=null;mediaProduct:Product|null=null;private ordersRequestVersion=0;private router=inject(Router);today=new Date();success='';navOpen=false;overviewProducts:Product[]=[];overviewProductTotal=0;overviewOrderTotal=0;private http=inject(HttpClient); token=localStorage.getItem('artio-admin-token'); email=localStorage.getItem('artio-admin-email')??'';password='';error='';extensionHealth='';searchIndexStatus='';tab='overview';query='';products:Product[]=[];ordersLoading=false;orderDetailLoading=false;ordersError='';catalogPage=0;catalogSize=25;catalogTotal=0;catalogState='ALL';catalogSort='updatedAt';catalogDirection='desc';catalogBrandId='';catalogCategoryId='';catalogBrands:any[]=[];catalogCategories:any[]=[];bundles:any[]=[];extensions:any[]=[];extensionAudit:any[]=[];extensionAuditFor:string|null=null;extensionInvocation={extensionPoint:'',operation:'health',payloadJson:'{}'};extensionInvocationResult:any=null;selectedExtension:any=null;extensionConfig:Record<string,any>={};extensionManifestJson='';prices:any[]=[];taxRules:any[]=[];sellableTaxCategories:any[]=[];taxAssignment={sellableId:'',category:'STANDARD'};orders:any[]=[];orderPage=0;orderSize=25;orderTotal=0;orderPages=0;ordersQuery='';ordersStatus='';orderStatuses=['PENDING','CONFIRMED','PAID','PARTIALLY_SHIPPED','SHIPPED','DELIVERED','CANCELLED','RETURNED'];selectedOrder:any=null;selectedReturns:any[]=[];orderWarehouses:any[]=[];selectedTimeline:any[]=[];orderNote='';selectedLoyalty:any=null;enterpriseFeatures:string[]=[];users:any[]=[];accessUser:any=null;accessRole='CUSTOMER';accessPermissions:string[]=[];permissionOptions=['PRODUCT_CREATE','PRODUCT_EDIT','PRODUCT_DELETE','CATEGORY_MANAGE','PRICING_MANAGE','INVENTORY_MANAGE','ORDER_FULFILL','USER_MANAGE','REPORTS_VIEW','ANALYTICS_VIEW'];usersQuery='';usersActivity='ALL';showBundle=false;showExtensionRegistration=false;showPrice=false;translationProduct:Product|null=null;translationLocale='de';translationDraft={name:'',description:'',seoSlug:'',seoTitle:'',seoDescription:''};bundleDraft:any={name:'',sku:'',type:'CONFIGURABLE',pricingMode:'COMPONENT_SUM',fixedAmount:0,currency:'CHF',discountPercentage:10};bundleMarketPrices:any[]=[];priceDraft:any={sellableId:'',amount:0,currency:'USD',market:'US',channel:'WEB',pricesIncludeTax:false};
  selectedShipments:any[]=[];shipmentTrackingNumber='';replayDialog:any=null;replayDialogBusy=false;replayDialogError='';shippingDialogOrder:any=null;shippingDialogTrackingNumber='';shippingDialogError='';shippingDialogBusy=false;refundDialogPayment:any=null;refundDialogAmount='';refundDialogError='';refundSubmitting=false;returnDialogAction:any=null;returnDialogNote='';returnDialogError='';returnDialogBusy=false;mfaCode='';mfaSetupRequired=false;mfaEnrollment:any=null;mfaQrCode='';mfaCopied=false;mfaBackupCodes:string[]=[];
 get isSuperadmin():boolean{try{const payload=JSON.parse(atob((this.token??"").split(".")[1].replace(/-/g,"+").replace(/_/g,"/")));return payload.role==="SUPERADMIN"}catch{return false}} get title():string{return ({system:"System Settings",reports:'Reports',overview:'Overview',products:'Products',productNew:'New product',catalogData:'Catalog data',bundles:'Bundles',extensions:'Extensions',pricing:'Pricing',inventory:'Inventory',orders:'Orders',users:'Customers',giftCards:'Gift cards',operations:'Operations'} as Record<string,string>)[this.tab]??'Overview'}
 navigateTo(tab:string):void{this.navOpen=false;this.router.navigateByUrl(tab==='productNew'?'/products/new':'/'+tab)}
 private syncRoute():void{const path=this.router.url.split('?')[0];const tab=path==='/products/new'?'productNew':path.slice(1);if(['overview','products','productNew','catalogData','bundles','extensions','pricing','inventory','orders','users','giftCards','operations','system','reports'].includes(tab))this.tab=tab;this.error='';this.success='';if(this.token&&!this.mfaSetupRequired){switch(this.tab){case 'overview':this.loadDashboard();break;case 'products':this.loadProducts();break;case 'bundles':this.loadBundles();break;case 'extensions':this.loadExtensions();break;case 'pricing':this.loadPrices();break;case 'orders':this.loadOrders();break;case 'users':this.loadUsers();break;case 'operations':this.loadOutbox();break;case 'system':if(this.isSuperadmin)this.loadSystemSettings();break;case 'reports':break;}}}
 productCreated(id:string):void{this.loadCatalogFilters();this.catalogPage=0;this.catalogState='ALL';this.query='';this.catalogBrandId='';this.catalogCategoryId='';this.router.navigateByUrl('/products').then(()=>this.success='Product created successfully. Add pricing and stock when you are ready to sell.')}
 get filteredProducts():Product[]{return this.products} get catalogPages():number{return Math.max(1,Math.ceil(this.catalogTotal/this.catalogSize))}
 resetCatalogPage():void{this.catalogPage=0;this.loadProducts()} previousCatalogPage():void{if(this.catalogPage>0){this.catalogPage--;this.loadProducts()}} nextCatalogPage():void{if(this.catalogPage+1<this.catalogPages){this.catalogPage++;this.loadProducts()}}
 extensionFields():Array<{key:string;value:any}>{return Object.entries(this.selectedExtension?.manifest.configuration??{}).map(([key,value])=>({key,value}))}
 t(key:BackofficeTextKey):string{return backofficeText(currentBackofficeLocale(),key)}
 constructor(){this.syncRoute();this.router.events.pipe(takeUntilDestroyed()).subscribe(event=>{if(event instanceof NavigationEnd)this.syncRoute()});this.loadSetupStatus();if(this.token&&!this.isMfaSetupOnlyToken()){this.loadFeatureAvailability();this.loadCatalogFilters();this.loadDashboard();this.loadProducts();this.loadBundles();this.loadOrders();this.loadPrices();}}
 private isMfaSetupOnlyToken():boolean{try{if(!this.token)return false;const payload=this.token.split('.')[1].replace(/-/g,'+').replace(/_/g,'/');return JSON.parse(atob(payload)).mfaSetupOnly===true}catch{return false}}
 loadSetupStatus():void{this.http.get<any>('/api/v1/setup/status').subscribe({next:r=>{this.setupChecked=true;this.setupRequired=!!r.required;this.developerMode=!!r.developerMode;if(this.developerMode){if(this.isMfaSetupOnlyToken()){localStorage.removeItem('artio-admin-token');this.token=null;this.mfaSetupRequired=false;this.mfaEnrollment=null;this.mfaQrCode=''}if(!this.token){this.email='admin@example.test';this.password='ArtioLocalAdmin!2026';this.mfaCode=''}}},error:()=>{this.setupChecked=true;this.error='Could not check first-run setup status.'}})}
 setupFinished(result:{email:string;demoCredentials:boolean}):void{this.setupRequired=false;this.email=result.email;this.password=result.demoCredentials?'ArtioLocalAdmin!2026':'';this.mfaCode='';this.error=''}
 login():void{this.http.post<any>('/api/v1/auth/login',{email:this.email,password:this.password,mfaCode:this.mfaCode||null}).subscribe({next:r=>{if(r.role==='CUSTOMER'){this.error='Administrator access is required.';return;}this.token=r.accessToken;localStorage.setItem('artio-admin-token',r.accessToken);localStorage.setItem('artio-admin-email',this.email);if(r.mfaEnrollmentRequired){this.mfaSetupRequired=true;this.beginMfaEnrollment();return;}this.mfaSetupRequired=false;this.loadFeatureAvailability();this.loadCatalogFilters();this.loadDashboard();this.loadProducts();this.loadBundles();this.loadOrders();this.loadPrices();},error:e=>this.error=e.error?.message??'Unable to sign in. Check your credentials and administrator role.'})}
 beginMfaEnrollment():void{this.mfaCode='';this.mfaCopied=false;this.mfaQrCode='';this.http.post<any>('/api/v1/auth/mfa/totp/enrollment',{}).subscribe({next:r=>{this.mfaEnrollment=r;this.error='';QRCode.toDataURL(r.otpauthUri,{errorCorrectionLevel:'M',margin:2,width:224,color:{dark:'#0F172A',light:'#FFFFFF'}}).then(uri=>this.mfaQrCode=uri).catch(()=>this.error='Could not generate the setup QR code. Use the manual setup key below.')},error:e=>this.error=e.error?.message??'Could not start authenticator enrollment.'})}
 async copyMfaSecret():Promise<void>{try{await navigator.clipboard.writeText(this.mfaEnrollment.base32Secret);this.mfaCopied=true}catch{this.error='Clipboard access is unavailable. Select and copy the setup key manually.'}}
 verifyMfaEnrollment():void{this.http.post<any>('/api/v1/auth/mfa/totp/verify',{code:this.mfaCode}).subscribe({next:r=>{this.mfaBackupCodes=r.backupCodes??[];this.mfaCode='';this.error=''},error:e=>{const message=e.error?.message??'';this.error=message.toLowerCase().includes('totp')?'That code is invalid or expired. Enter the newest six-digit code from your authenticator app.':message||'Enter a current authenticator code.'}})}
 finishMfaSetup():void{localStorage.removeItem('artio-admin-token');this.token=null;this.mfaSetupRequired=false;this.mfaEnrollment=null;this.mfaBackupCodes=[];this.password='';this.mfaCode='';this.error='Authenticator protection is active. Sign in again with a current code.'}
 logout():void{localStorage.removeItem('artio-admin-token');localStorage.removeItem('artio-admin-email');this.token=null}
 get catalogBrandOptions():SelectOption[]{return [{value:'',label:'All brands'},...this.catalogBrands.map(x=>({value:x.id,label:x.name}))]}
 get catalogCategoryOptions():SelectOption[]{return [{value:'',label:'All categories'},...this.catalogCategories.map(x=>({value:x.id,label:x.name}))]}
 bundleSellables:string[]=[];
 get warehouseOptions():SelectOption[]{return [{value:'',label:'No restocking'},...this.orderWarehouses.map(x=>({value:x.id,label:x.name,detail:x.code}))]}
 loadDashboard():void{this.http.get<{items:Product[];total:number}>('/api/v1/admin/catalog/products?page=0&size=5&state=ALL&sort=updatedAt&direction=desc').subscribe({next:r=>{this.overviewProducts=r.items;this.overviewProductTotal=r.total},error:e=>this.error=e.error?.message??'Could not load catalog summary.'});this.http.get<{totalElements:number}>('/api/v1/admin/orders?page=0&size=1&sort=createdAt&direction=desc').subscribe({next:r=>this.overviewOrderTotal=r.totalElements,error:()=>undefined})}
 loadCatalogFilters():void{this.http.get<any[]>('/api/v1/brands').subscribe({next:rows=>this.catalogBrands=rows,error:()=>undefined});this.http.get<any[]>('/api/v1/categories').subscribe({next:rows=>this.catalogCategories=rows,error:()=>undefined})}
 loadProducts():void{const params=new URLSearchParams({page:String(this.catalogPage),size:String(this.catalogSize),state:this.catalogState,sort:this.catalogSort,direction:this.catalogDirection});if(this.query.trim())params.set('q',this.query.trim());if(this.catalogBrandId)params.set('brandId',this.catalogBrandId);if(this.catalogCategoryId)params.set('categoryId',this.catalogCategoryId);this.http.get<{items:Product[];total:number}>(`/api/v1/admin/catalog/products?${params}`).subscribe({next:r=>{this.products=r.items;this.catalogTotal=r.total;for(const p of this.products)this.http.get<Array<{id:string;name:string;sku:string;active:boolean}>>(`/api/v1/products/${p.id}/variants`).subscribe({next:v=>p.variants=v,error:()=>p.variants=[]})},error:e=>this.error=e.error?.message??e.error?.detail??'Could not load products.'})}
 loadBundles():void{this.http.get<any[]>('/api/v1/bundles').subscribe({next:r=>this.bundles=r,error:()=>undefined})}
 loadExtensions():void{this.http.get<any[]>('/api/v1/admin/extensions').subscribe({next:r=>this.extensions=r,error:e=>this.error=e.error?.message??e.error?.detail??'Could not load extensions.'});this.http.get<any[]>('/api/v1/admin/extensions/catalog').subscribe({next:r=>this.extensionCatalog=r,error:e=>this.error=e.error?.message??e.error?.detail??'Could not load plugin catalog.'})}
 selectCatalogPlugin(item:any):void{const plugin=this.extensions.find(candidate=>candidate.id===item.pluginId);if(plugin)this.selectExtension(plugin)}
 restoreCatalogPlugin(item:any):void{this.http.post(`/api/v1/admin/extensions/${item.pluginId}/restore`,{}).subscribe({next:()=>{this.error='';this.loadExtensions()},error:e=>this.error=e.error?.message??'Could not restore the plugin registration.'})}
 invokeExtension():void{if(!this.selectedExtension)return;let payload:any;try{payload=JSON.parse(this.extensionInvocation.payloadJson)}catch{this.error='Diagnostic payload must be valid JSON.';return}const body={...this.extensionInvocation,payload,idempotencyKey:crypto.randomUUID(),deadlineMillis:5000};this.http.post<any>('/api/v1/admin/extensions/'+this.selectedExtension.manifest.id+'/invoke',body).subscribe({next:r=>{this.extensionInvocationResult=r;this.error=''},error:e=>{this.extensionInvocationResult=null;this.error=e.error?.message??e.error?.detail??e.error?.message??'Extension diagnostic request failed.'}})}
 loadExtensionAudit(extension:any):void{this.extensionAuditFor=extension.id;this.http.get<any[]>('/api/v1/admin/extensions/'+extension.id+'/audit').subscribe({next:r=>this.extensionAudit=r,error:e=>{this.extensionAudit=[];this.error=e.error?.message??e.error?.detail??'Could not load extension activity.'}})}
 rebuildSearchIndex():void{this.searchIndexStatus='Rebuilding search documents…';this.http.post<{productsIndexed:number;translationsIndexed:number}>('/api/v1/admin/catalog/search-index/rebuild',{}).subscribe({next:r=>this.searchIndexStatus=`Indexed ${r.productsIndexed} products and ${r.translationsIndexed} translations.`,error:e=>this.searchIndexStatus=e.error?.message??e.error?.detail??'Search index rebuild failed.'})}
 loadPrices():void{this.http.get<any[]>('/api/v1/prices').subscribe({next:r=>this.prices=r,error:()=>undefined});this.http.get<any[]>('/api/v1/tax-rules').subscribe({next:r=>this.taxRules=r,error:()=>undefined});this.http.get<any[]>('/api/v1/tax-rules/sellables/categories').subscribe({next:r=>this.sellableTaxCategories=r,error:()=>undefined})}
 loadOrders(page=this.orderPage):void{const requestVersion=++this.ordersRequestVersion;this.orderPage=page;this.ordersLoading=true;this.ordersError='';this.orders=[];this.orderTotal=0;this.orderPages=0;const params=new URLSearchParams({page:String(page),size:String(this.orderSize),sort:'createdAt',direction:'desc'});if(this.ordersStatus)params.set('status',this.ordersStatus);if(this.ordersQuery.trim())params.set('q',this.ordersQuery.trim());this.http.get<any>('/api/v1/admin/orders?'+params.toString()).subscribe({next:r=>{if(requestVersion!==this.ordersRequestVersion)return;this.orders=r.items;this.orderTotal=r.totalElements;this.orderPages=r.totalPages;this.ordersLoading=false},error:e=>{if(requestVersion!==this.ordersRequestVersion)return;this.ordersLoading=false;this.ordersError=e.error?.message??e.error?.detail??'Could not load orders.'}})}
 searchOrders():void{this.loadOrders(0)}
 viewOrder(order:any):void{this.selectedOrder=null;this.selectedReturns=[];this.selectedShipments=[];this.selectedTimeline=[];this.orderDetailLoading=true;this.error='';this.http.get<any>('/api/v1/admin/orders/'+order.id).subscribe({next:r=>{this.selectedOrder=r;this.orderDetailLoading=false;for(const line of r.lines??[])line.shipQuantity=0;this.shipmentTrackingNumber='';this.http.get<any[]>('/api/v1/admin/orders/'+order.id+'/shipments').subscribe({next:shipments=>this.selectedShipments=shipments,error:e=>this.error=e.error?.message??e.error?.detail??'Could not load shipments.'});this.http.get<any[]>('/api/v1/admin/orders/'+order.id+'/returns').subscribe({next:returns=>this.selectedReturns=returns,error:e=>this.error=e.error?.message??e.error?.detail??'Could not load order returns.'});this.http.get<any[]>('/api/v1/warehouses').subscribe({next:warehouses=>{this.orderWarehouses=warehouses.filter(w=>w.active)},error:e=>this.error=e.error?.message??e.error?.detail??'Could not load warehouses.'});this.loadOrderTimeline(order.id)},error:e=>{this.orderDetailLoading=false;this.error=e.error?.message??e.error?.detail??'Could not load order details.'}})}
 createShipment():void{if(!this.selectedOrder)return;const items=(this.selectedOrder.lines??[]).filter((line:any)=>Number(line.shipQuantity)>0).map((line:any)=>({orderItemId:line.id,quantity:Number(line.shipQuantity)}));if(!this.shipmentTrackingNumber.trim()||items.length===0){this.error='Enter a tracking number and a quantity for at least one order line.';return}this.http.post<any>(`/api/v1/admin/orders/${this.selectedOrder.id}/shipments`,{trackingNumber:this.shipmentTrackingNumber.trim(),items}).subscribe({next:()=>{this.error='';this.viewOrder({id:this.selectedOrder.id});this.loadOrders()},error:e=>this.error=e.error?.message??e.error?.detail??e.error?.message??'Could not create shipment.'})}
 loadOrderTimeline(orderId:string):void{this.http.get<any[]>('/api/v1/admin/orders/'+orderId+'/timeline').subscribe({next:events=>this.selectedTimeline=events,error:e=>this.error=e.error?.message??e.error?.detail??'Could not load order timeline.'})}
 addOrderNote():void{if(!this.selectedOrder||!this.orderNote.trim())return;this.http.post<any>(`/api/v1/admin/orders/${this.selectedOrder.id}/notes`,{message:this.orderNote.trim()}).subscribe({next:()=>{this.orderNote='';this.loadOrderTimeline(this.selectedOrder.id)},error:e=>this.error=e.error?.message??e.error?.detail??'Could not add staff note.'})}
 openReturnAction(item:any,kind:'review'|'receive',value:boolean):void{this.returnDialogAction={item,kind,approve:kind==='review'?value:false,restock:kind==='receive'?value:false};this.returnDialogNote='';this.returnDialogError='';this.returnDialogBusy=false;setTimeout(()=>{const dialog=this.returnDialogRef?.nativeElement;if(this.returnDialogAction?.item?.id===item.id&&dialog&&!dialog.open)dialog.showModal()},0)}
 cancelReturnDialog(event:Event):void{if(this.returnDialogBusy)event.preventDefault()}
 returnDialogClosed():void{if(this.returnDialogBusy)return;this.returnDialogAction=null;this.returnDialogNote='';this.returnDialogError=''}
 closeReturnDialog():void{if(!this.returnDialogBusy)this.returnDialogRef?.nativeElement.close()}
 warehouseName(id:string|null|undefined):string{return this.orderWarehouses.find(warehouse=>warehouse.id===id)?.name??'—'}
 submitReturnAction():void{const action=this.returnDialogAction;if(!action||this.returnDialogBusy)return;const item=action.item;const note=this.returnDialogNote.trim()||null;const receiving=action.kind==='receive';const warehouseId=action.restock?item.receiveWarehouseId:null;if(receiving&&action.restock&&!warehouseId){this.returnDialogError=this.t('returnDialogWarehouseRequired');return}const path=receiving?`/api/v1/admin/orders/${item.orderId}/returns/${item.id}/receive`:`/api/v1/admin/orders/${item.orderId}/returns/${item.id}/review`;const body=receiving?{restock:action.restock,warehouseId,note}:{approve:action.approve,note};this.returnDialogBusy=true;this.returnDialogError='';this.http.post<any>(path,body).subscribe({next:()=>{const orderId=item.orderId;this.returnDialogBusy=false;this.returnDialogRef?.nativeElement.close();this.viewOrder({id:orderId})},error:e=>{this.returnDialogBusy=false;this.returnDialogError=e.error?.message??e.error?.detail??this.t('returnDialogFailure')}})}
 refundPayment(payment:any):void{this.refundDialogPayment=payment;this.refundDialogAmount='';this.refundDialogError='';this.refundSubmitting=false;setTimeout(()=>{const dialog=this.refundDialogRef?.nativeElement;if(this.refundDialogPayment?.id===payment.id&&dialog&&!dialog.open)dialog.showModal()},0)}
 cancelRefundDialog(event:Event):void{if(this.refundSubmitting)event.preventDefault()}
 refundDialogClosed():void{if(this.refundSubmitting)return;this.refundDialogPayment=null;this.refundDialogAmount='';this.refundDialogError=''}
 closeRefundDialog():void{if(!this.refundSubmitting)this.refundDialogRef?.nativeElement.close()}
 submitRefund():void{const payment=this.refundDialogPayment;if(!payment||this.refundSubmitting)return;const normalized=this.refundDialogAmount.trim();const numeric=normalized?Number(normalized):null;if(numeric!==null&&(!Number.isFinite(numeric)||numeric<=0)){this.refundDialogError=this.t('refundInvalidAmount');return}const storageKey='artio-refund-key:'+payment.id+':'+(normalized||'remaining');let idempotencyKey=sessionStorage.getItem(storageKey);if(!idempotencyKey){idempotencyKey=crypto.randomUUID();sessionStorage.setItem(storageKey,idempotencyKey)}this.refundSubmitting=true;this.refundDialogError='';this.http.post<any>('/api/v1/payments/'+payment.id+'/refund',{amount:numeric},{headers:{'Idempotency-Key':idempotencyKey}}).subscribe({next:()=>{sessionStorage.removeItem(storageKey);const orderId=this.selectedOrder?.id;this.refundSubmitting=false;this.refundDialogRef?.nativeElement.close();if(orderId)this.viewOrder({id:orderId})},error:e=>{this.refundSubmitting=false;this.refundDialogError=e.error?.message??e.error?.detail??this.t('refundFailure')}})}
 exportOrders():void{const params=new URLSearchParams();if(this.ordersStatus)params.set('status',this.ordersStatus);if(this.ordersQuery.trim())params.set('q',this.ordersQuery.trim());this.http.get('/api/v1/admin/orders/export.csv?'+params.toString(),{responseType:'blob'}).subscribe({next:blob=>{const url=URL.createObjectURL(blob);const link=document.createElement('a');link.href=url;link.download='artio-orders.csv';link.click();URL.revokeObjectURL(url)},error:e=>this.error=e.error?.message??e.error?.detail??'Could not export orders.'})}
 loadOutbox():void{this.http.get<any>('/api/v1/admin/operations/outbox').subscribe({next:r=>this.outboxDiagnostics=r,error:e=>this.error=e.error?.message??e.error?.detail??'Could not load delivery diagnostics.'});this.http.get<any[]>('/api/v1/admin/operations/outbox/events/dead').subscribe({next:r=>this.outboxDeadEvents=r,error:e=>this.error=e.error?.message??e.error?.detail??'Could not load dead-letter events.'});this.loadKafkaOperations()}
 loadKafkaOperations():void{this.http.get<any>('/api/v1/admin/operations/kafka/dead-letters').subscribe({next:r=>this.kafkaDeadLetters=r,error:()=>undefined});this.http.get<any[]>('/api/v1/admin/operations/kafka/dead-letters/items').subscribe({next:r=>this.kafkaDeadLetterItems=r,error:()=>undefined});this.http.get<any>('/api/v1/admin/operations/kafka/consumer-lag').subscribe({next:r=>this.kafkaConsumerLag=r,error:()=>this.kafkaConsumerLag=null})}
 openReplayDialog(kind:'kafka'|'outbox',event:any):void{this.replayDialog={kind,event};this.replayDialogBusy=false;this.replayDialogError='';setTimeout(()=>{const dialog=this.replayDialogRef?.nativeElement;if(this.replayDialog?.event?.id===event.id&&dialog&&!dialog.open)dialog.showModal()},0)}
 cancelReplayDialog(event:Event):void{if(this.replayDialogBusy)event.preventDefault()}
 replayDialogClosed():void{if(this.replayDialogBusy)return;this.replayDialog=null;this.replayDialogError=''}
 closeReplayDialog():void{if(!this.replayDialogBusy)this.replayDialogRef?.nativeElement.close()}
 submitReplay():void{const action=this.replayDialog;if(!action||this.replayDialogBusy)return;const event=action.event;const path=action.kind==='kafka'?`/api/v1/admin/operations/kafka/dead-letters/${event.id}/replay`:`/api/v1/admin/operations/outbox/events/${event.id}/replay`;this.replayDialogBusy=true;this.replayDialogError='';this.http.post(path,{}).subscribe({next:()=>{this.replayDialogBusy=false;this.replayDialogRef?.nativeElement.close();if(action.kind==='kafka')this.loadKafkaOperations();else this.loadOutbox()},error:e=>{this.replayDialogBusy=false;this.replayDialogError=e.error?.message??e.error?.detail??this.t('replayDialogFailure')}})}
 replayKafkaDeadLetter(event:any):void{this.openReplayDialog('kafka',event)}
 replayOutboxEvent(event:any):void{this.openReplayDialog('outbox',event)}
 loadSystemSettings():void{this.http.get<any>('/api/v1/admin/system/settings').subscribe({next:r=>{this.systemSettings=r;this.systemLanguages=(r.availableLanguages??[]).join(',');this.systemSelectedLanguages=[...(r.availableLanguages??[])]},error:e=>this.error=e.error?.message??e.error?.detail??'Could not load system settings.'});this.http.get<any[]>('/api/v1/admin/system/settings/audit').subscribe({next:r=>this.systemSettingsAudit=r,error:e=>this.error=e.error?.message??e.error?.detail??'Could not load system settings audit.'});this.loadDistributionPlugins()}
 loadDistributionPlugins():void{this.http.get<any[]>('/api/v1/admin/system/distribution/plugins').subscribe({next:r=>this.distributionPlugins=r,error:e=>this.error=e.error?.message??e.error?.detail??'Could not load regional plugin status.'})}
 refreshDistributionPlugins():void{this.http.post<any[]>('/api/v1/admin/system/distribution/plugins/refresh',{}).subscribe({next:r=>this.distributionPlugins=r,error:e=>this.error=e.error?.message??e.error?.detail??'Could not refresh regional plugin status.'})}
 saveSystemSettings():void{if(!this.systemSelectedLanguages.length){this.error='Choose at least one content language.';return;}const request={...this.systemSettings,availableLanguages:this.systemSelectedLanguages};this.http.put<any>('/api/v1/admin/system/settings',request).subscribe({next:r=>{this.systemSettings=r;this.systemLanguages=r.availableLanguages.join(',');this.error='';this.loadSystemSettings()},error:e=>this.error=e.error?.message??e.error?.detail??'Could not save system settings.'})}
 loadUsers():void{const params=new URLSearchParams({limit:'100',offset:'0',activity:this.usersActivity});if(this.usersQuery.trim())params.set('q',this.usersQuery.trim());this.http.get<any[]>('/api/v1/admin/users?'+params.toString()).subscribe({next:r=>this.users=r,error:e=>this.error=e.error?.message??e.error?.detail??'Could not load customers.'})}
  editUserAccess(user:any):void{this.accessUser=user;this.accessRole=user.role;this.accessPermissions=[...(user.permissions??[])];this.error=''}
  setAccessPermission(event:Event,permission:string):void{const checked=(event.target as HTMLInputElement).checked;this.accessPermissions=checked?[...new Set([...this.accessPermissions,permission])]:this.accessPermissions.filter(value=>value!==permission)}
  saveUserAccess():void{if(!this.accessUser||!this.isSuperadmin)return;const user=this.accessUser;const persistPermissions=()=>{if(this.accessRole!=='ADMIN'){this.accessUser=null;this.loadUsers();return}this.http.put('/api/v1/admin/users/'+user.id+'/permissions',{permissions:this.accessPermissions}).subscribe({next:()=>{this.accessUser=null;this.loadUsers();this.error=''},error:e=>this.error=e.error?.message??e.error?.detail??'Could not save administrator permissions.'})};if(this.accessRole!==user.role){this.http.put('/api/v1/admin/users/'+user.id+'/role',{role:this.accessRole}).subscribe({next:()=>persistPermissions(),error:e=>this.error=e.error?.message??e.error?.detail??'Could not change account role.'});return}persistPermissions()}
 openShippingDialog(order:any):void{this.shippingDialogOrder=order;this.shippingDialogTrackingNumber='';this.shippingDialogError='';this.shippingDialogBusy=false;setTimeout(()=>{const dialog=this.shippingDialogRef?.nativeElement;if(this.shippingDialogOrder?.id===order.id&&dialog&&!dialog.open)dialog.showModal()},0)}
 cancelShippingDialog(event:Event):void{if(this.shippingDialogBusy)event.preventDefault()}
 shippingDialogClosed():void{if(this.shippingDialogBusy)return;this.shippingDialogOrder=null;this.shippingDialogTrackingNumber='';this.shippingDialogError=''}
 closeShippingDialog():void{if(!this.shippingDialogBusy)this.shippingDialogRef?.nativeElement.close()}
 submitShipping():void{const order=this.shippingDialogOrder;if(!order||this.shippingDialogBusy)return;const trackingNumber=order.shippingMethodId?null:this.shippingDialogTrackingNumber.trim();if(!order.shippingMethodId&&!trackingNumber){this.shippingDialogError=this.t('shippingTrackingRequired');return}this.shippingDialogBusy=true;this.shippingDialogError='';this.http.post(`/api/v1/admin/orders/${order.id}/ship`,{trackingNumber}).subscribe({next:()=>{this.shippingDialogBusy=false;this.shippingDialogRef?.nativeElement.close();this.loadOrders()},error:e=>{this.shippingDialogBusy=false;this.shippingDialogError=e.error?.message??e.error?.detail??this.t('shippingDialogFailure')}})}
 shipOrder(order:any):void{this.openShippingDialog(order)}
 openShippingLabel(order:any):void{const popup=window.open('about:blank','_blank');if(!popup){this.error='Allow pop-ups to open the shipping label.';return}popup.opener=null;this.http.get(`/api/v1/admin/orders/${order.id}/label`,{responseType:'blob'}).subscribe({next:blob=>{const url=URL.createObjectURL(blob);popup.location.href=url;setTimeout(()=>URL.revokeObjectURL(url),60_000)},error:e=>{popup.close();this.error=e.error?.message??e.error?.detail??'Could not load the shipping label.'}})}
 deliverOrder(order:any):void{this.http.post(`/api/v1/admin/orders/${order.id}/deliver`,{}).subscribe({next:()=>this.loadOrders(),error:e=>this.error=e.error?.message??e.error?.detail??'Could not update this order.'})}
 refreshOrderTracking(order:any):void{this.http.post<any>(`/api/v1/admin/orders/${order.id}/tracking/refresh`,{}).subscribe({next:r=>{this.error='Carrier update: '+r.status+(r.lastLocation?' · '+r.lastLocation:'');this.loadOrders();if(this.selectedOrder?.id===order.id)this.viewOrder(order)},error:e=>this.error=e.error?.message??e.error?.detail??'Carrier tracking is not available for this shipping provider.'})}
 setUserActive(user:any):void{this.http.put('/api/v1/admin/users/'+user.id+'/active',{active:!user.active}).subscribe(()=>this.loadUsers())}
 verifyUser(user:any):void{this.http.put('/api/v1/admin/users/'+user.id+'/email-verification',{verified:true}).subscribe(()=>this.loadUsers())}
 loadFeatureAvailability():void{this.http.get<{features:string[]}>('/api/v1/admin/license/availability').subscribe({next:r=>this.enterpriseFeatures=r.features??[],error:()=>this.enterpriseFeatures=[]})}
 viewLoyalty(user:any):void{this.http.get<any>(`/api/v1/admin/loyalty/${user.id}`).subscribe({next:account=>this.selectedLoyalty={user,account},error:e=>this.error=e.error?.message??e.error?.detail??'Could not load loyalty account.'})}
 adjustLoyalty():void{if(!this.selectedLoyalty)return;const points=Number(prompt('Points adjustment. Use a negative number to remove points.'));if(!Number.isInteger(points)||points===0||Math.abs(points)>1000000){this.error='Enter a whole point adjustment between -1,000,000 and 1,000,000.';return}const reason=prompt('Reason for this audited adjustment');if(!reason?.trim())return;if(!window.confirm(`Apply ${points>0?'+':''}${points} points?`))return;const user=this.selectedLoyalty.user;const storageKey=`artio-loyalty-adjust:${user.id}:${points}`;let key=sessionStorage.getItem(storageKey);if(!key){key=crypto.randomUUID();sessionStorage.setItem(storageKey,key)}this.http.post<any>(`/api/v1/admin/loyalty/${user.id}/adjust`,{points,reason:reason.trim()},{headers:{'Idempotency-Key':key}}).subscribe({next:account=>{sessionStorage.removeItem(storageKey);this.selectedLoyalty={user,account}},error:e=>this.error=e.error?.message??e.error?.detail??'Could not adjust loyalty points.'})}
 exportProducts():void{this.http.get('/api/v1/admin/catalog/products/export',{responseType:'blob'}).subscribe({next:blob=>{const url=URL.createObjectURL(blob);const anchor=document.createElement('a');anchor.href=url;anchor.download='artio-products.csv';anchor.click();URL.revokeObjectURL(url);this.error=''},error:e=>this.error=e.error?.message??e.error?.detail??'Could not export products.'})}
 importProducts(event:Event):void{const input=event.target as HTMLInputElement;const file=input.files?.[0];input.value='';if(!file)return;if(!file.name.toLowerCase().endsWith('.csv')){this.error='Choose a .csv file.';return}const form=new FormData();form.append('file',file,file.name);this.http.post<any>('/api/v1/admin/catalog/products/import',form).subscribe({next:report=>{this.productImportReport=report;this.error='';this.loadProducts()},error:e=>this.error=e.error?.message??e.error?.detail??'Could not import products.'})}

 duplicateProduct(product:Product):void{const name=prompt('Name for the unpublished product copy',product.name+' copy');if(!name?.trim())return;const sku=prompt('New unique SKU',product.sku+'-COPY');if(!sku?.trim())return;this.http.post<Product>(`/api/v1/products/${product.id}/duplicate`,{name:name.trim(),description:product.description,sku:sku.trim()}).subscribe({next:()=>{this.error='Product copy created as an unpublished draft.';this.loadProducts()},error:e=>this.error=e.error?.message??e.error?.detail??'Could not duplicate product.'})}
 toggle(p:Product):void{this.http.put(`/api/v1/products/${p.id}/active`,{active:!p.active}).subscribe(()=>this.loadProducts())}
 archiveProduct(p:Product):void{if(!window.confirm(`Archive ${p.name}? Its SKU and order history will be retained.`))return;this.http.put(`/api/v1/products/${p.id}/archive`,{}).subscribe({next:()=>this.loadProducts(),error:e=>this.error=e.error?.message??e.error?.detail??'Could not archive product.'})}
 restoreProduct(p:Product):void{this.http.put(`/api/v1/products/${p.id}/restore`,{}).subscribe({next:()=>this.loadProducts(),error:e=>this.error=e.error?.message??e.error?.detail??'Could not restore product.'})}
 createVariant(product:Product):void{const name=prompt('Variant name');if(!name?.trim())return;const sku=prompt('Unique variant SKU',`${product.sku}-${name.trim().toUpperCase().replace(/[^A-Z0-9]+/g,'-')}`);if(!sku?.trim())return;const raw=prompt('Variant options as JSON using active attribute codes (for example {\"color\":\"navy\",\"size\":\"M\"})','{}');if(raw===null)return;const attributes=this.parseVariantAttributes(raw);if(attributes===null)return;this.http.post(`/api/v1/products/${product.id}/variants`,{name:name.trim(),sku:sku.trim(),attributes}).subscribe({next:()=>this.loadProducts(),error:e=>this.error=e.error?.message??e.error?.detail??'Could not create the variant.'})}
 editVariant(variant:{id:string;name:string;sku:string;attributes?:Record<string,string>}):void{const name=prompt('Variant name',variant.name);if(!name?.trim())return;const sku=prompt('Unique variant SKU',variant.sku);if(!sku?.trim())return;const raw=prompt('Variant options JSON',JSON.stringify(variant.attributes??{}));if(raw===null)return;const attributes=this.parseVariantAttributes(raw);if(attributes===null)return;this.http.put(`/api/v1/variants/${variant.id}`,{name:name.trim(),sku:sku.trim(),attributes}).subscribe({next:()=>this.loadProducts(),error:e=>this.error=e.error?.message??e.error?.detail??'Could not update the variant.'})}
 parseVariantAttributes(raw:string):Record<string,string>|null{try{const value=JSON.parse(raw);if(!value||Array.isArray(value)||typeof value!=='object'||Object.values(value).some(item=>typeof item!=='string'))throw new Error();return value as Record<string,string>}catch{this.error='Variant options must be a JSON object with string values.';return null}}
 toggleVariant(variant:{id:string;active:boolean}):void{this.http.put(`/api/v1/variants/${variant.id}/active`,{active:!variant.active}).subscribe({next:()=>this.loadProducts(),error:e=>this.error=e.error?.message??e.error?.detail??'Could not update variant status.'})}
 schedulePublication(product:Product):void{const localTime=prompt('Publish at local date/time (YYYY-MM-DDTHH:mm)',new Date(Date.now()+3600000-Date.now()%60000).toLocaleString('sv-SE').replace(' ','T').slice(0,16));if(!localTime)return;const publishAt=new Date(localTime);if(Number.isNaN(publishAt.getTime())||publishAt.getTime()<=Date.now()){this.error='Choose a valid future publication time.';return}this.http.put(`/api/v1/products/${product.id}/publication-schedule`,{publishAt:publishAt.toISOString()}).subscribe({next:()=>this.loadProducts(),error:e=>this.error=e.error?.message??e.error?.detail??'Could not schedule publication.'})}
 cancelPublication(product:Product):void{if(!confirm('Cancel scheduled publication?'))return;this.http.delete(`/api/v1/products/${product.id}/publication-schedule`).subscribe({next:()=>this.loadProducts(),error:e=>this.error=e.error?.message??e.error?.detail??'Could not cancel publication.'})}
 editSeo(product:Product):void{const slug=prompt('Canonical URL slug (lowercase words separated by hyphens)',product.seoSlug??product.name.normalize('NFKD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,''));if(!slug?.trim())return;const title=prompt('SEO title (up to 180 characters)',product.seoTitle??product.name);if(title===null)return;const description=prompt('SEO description (up to 320 characters)',product.seoDescription??product.description);if(description===null)return;this.http.put(`/api/v1/products/${product.id}/seo`,{slug:slug.trim(),title:title.trim()||null,description:description.trim()||null}).subscribe({next:()=>this.loadProducts(),error:e=>this.error=e.error?.message??e.error?.detail??'Could not save SEO metadata.'})}
 editTranslations(product:Product):void{this.translationProduct=product;this.translationLocale='de';this.loadTranslation()}
 loadTranslation():void{if(!this.translationProduct)return;this.translationDraft={name:'',description:'',seoSlug:'',seoTitle:'',seoDescription:''};this.http.get<Array<{locale:string;name:string;description:string;seoSlug:string|null;seoTitle:string|null;seoDescription:string|null}>>(`/api/v1/products/${this.translationProduct.id}/translations`).subscribe({next:items=>{const found=items.find(item=>item.locale===this.translationLocale);if(found)this.translationDraft={name:found.name,description:found.description,seoSlug:found.seoSlug??'',seoTitle:found.seoTitle??'',seoDescription:found.seoDescription??''}},error:()=>this.error='Could not load product translations.'})}
 saveTranslation():void{if(!this.translationProduct)return;this.http.put(`/api/v1/products/${this.translationProduct.id}/translations/${this.translationLocale}`,this.translationDraft).subscribe({next:()=>this.error='',error:e=>this.error=e.error?.message??e.error?.detail??'Could not save product translation.'})}
 registerExtension():void{try{const manifest=JSON.parse(this.extensionManifestJson);this.http.post('/api/v1/admin/extensions/register',manifest).subscribe({next:()=>{this.showExtensionRegistration=false;this.extensionManifestJson='';this.error='';this.loadExtensions()},error:e=>this.error=e.error?.message??e.error?.detail??'Manifest validation or registration failed.'})}catch{this.error='Extension manifest must be valid JSON.'}}
 selectExtension(extension:any):void{this.http.get<any>(`/api/v1/admin/extensions/${extension.id}`).subscribe({next:plugin=>{this.selectedExtension=plugin;this.extensionConfig={...plugin.configuration};for(const key of Object.keys(plugin.manifest.configuration??{})){if(plugin.manifest.configuration[key].type==='secret')this.extensionConfig[key]=''}},error:e=>this.error=e.error?.message??e.error?.detail??'Could not load extension settings.'})}
 saveExtensionConfiguration():void{if(!this.selectedExtension)return;const payload={...this.extensionConfig};for(const key of this.selectedExtension.configuredSecrets){if(!payload[key])delete payload[key]}this.http.put(`/api/v1/admin/extensions/${this.selectedExtension.manifest.id}/configuration`,payload).subscribe({next:()=>{this.error='';this.selectedExtension=null;this.loadExtensions()},error:e=>this.error=e.error?.message??e.error?.detail??'Extension configuration is invalid.'})}
 openObservability():void{const popup=window.open('about:blank','_blank');if(!popup){this.error='Allow pop-ups to open observability dashboards.';return}popup.opener=null;this.http.get('/api/v1/admin/extensions/observability.artio/ui-session').subscribe({next:()=>{popup.location.href='/plugins/observability/'},error:e=>{popup.close();this.error=e.error?.message??e.error?.detail??'Could not open observability dashboards.'}})}
 extensionAction(extension:any,action:'install'|'enable'|'disable'):void{this.http.post(`/api/v1/admin/extensions/${extension.id}/${action}`,{}).subscribe({next:()=>{this.error='';this.extensionHealth='';this.loadExtensions()},error:e=>this.error=e.error?.message??e.error?.detail??e.error?.message??`Could not ${action} extension.`})}
 updateExtension(extension:any):void{const manifestJson=window.prompt(`Paste the next manifest JSON for ${extension.id}`);if(!manifestJson)return;try{const manifest=JSON.parse(manifestJson);this.http.post(`/api/v1/admin/extensions/${extension.id}/update`,manifest).subscribe({next:()=>{this.error='';this.loadExtensions()},error:e=>this.error=e.error?.message??e.error?.detail??e.error?.message??'Could not update extension.'})}catch{this.error='Manifest must be valid JSON.'}}
 rollbackExtension(extension:any):void{if(!window.confirm(`Roll back ${extension.id} to its previous release?`))return;this.http.post(`/api/v1/admin/extensions/${extension.id}/rollback`,{}).subscribe({next:()=>{this.error='';this.loadExtensions()},error:e=>this.error=e.error?.message??e.error?.detail??e.error?.message??'Could not roll back extension.'})} uninstallExtension(extension:any):void{if(!window.confirm(`Remove ${extension.name}? This stops and removes its managed container and purges stored configuration.`))return;this.http.delete(`/api/v1/admin/extensions/${extension.id}`).subscribe({next:()=>{this.error='';this.loadExtensions()},error:e=>this.error=e.error?.message??e.error?.detail??e.error?.message??'Could not uninstall extension.'})} rotateExtensionCredentials(extension:any):void{this.http.post(`/api/v1/admin/extensions/${extension.id}/credentials/rotate`,{}).subscribe({next:()=>{this.error='';this.extensionHealth=`${extension.id}: credential rotated`;this.loadExtensions()},error:e=>this.error=e.error?.message??e.error?.detail??e.error?.message??'Could not rotate extension credential.'})}
 checkExtensionHealth(extension:any):void{this.http.get<any>(`/api/v1/admin/extensions/${extension.id}/health`).subscribe({next:r=>{this.extensionHealth=`${extension.id}: ${r.healthy?'healthy':r.status}`;this.error=''},error:e=>this.error=e.error?.message??e.error?.detail??'Could not read extension health.'})}
 bundleTypeChanged():void{this.bundleSellables=[];this.bundleGroups=[{code:'choices',name:'Choose your items',minimumSelections:1,maximumSelections:1,sellables:[]}];}
 addBundleGroup():void{const index=this.bundleGroups.length+1;this.bundleGroups.push({code:`choices-${index}`,name:`Choice group ${index}`,minimumSelections:0,maximumSelections:1,sellables:[]});}
 removeBundleGroup(index:number):void{if(this.bundleGroups.length>1)this.bundleGroups.splice(index,1);}
 addBundleMarketPrice():void{this.bundleMarketPrices.push({market:"",amount:0,currency:this.bundleDraft.currency})}
 createBundle():void{const fixed=this.bundleDraft.type==='FIXED';const hasComponents=fixed?this.bundleSellables.length>0:this.bundleGroups.every((group:any)=>group.sellables.length>0);if(!hasComponents){this.error=fixed?'Choose at least one variant for this bundle.':'Choose at least one variant in every choice group.';return;}if(!fixed&&this.bundleGroups.some((group:any)=>group.minimumSelections>group.maximumSelections||group.maximumSelections>group.sellables.length)){this.error='Each choice group must have valid minimum and maximum selections.';return;}const marketPrices=Object.fromEntries(this.bundleMarketPrices.map((price:any)=>[String(price.market).trim().toUpperCase(),{amount:Number(price.amount),currency:price.currency}]));if(Object.keys(marketPrices).length!==this.bundleMarketPrices.length){this.error='Market price country codes must be unique.';return;}const pricingRule=this.bundleDraft.pricingMode==='FIXED_PRICE'?{mode:'FIXED_PRICE',fixedAmount:Number(this.bundleDraft.fixedAmount),currency:this.bundleDraft.currency,marketPrices}:this.bundleDraft.pricingMode==='PERCENTAGE_OFF_COMPONENTS'?{mode:'PERCENTAGE_OFF_COMPONENTS',discountPercentage:Number(this.bundleDraft.discountPercentage)}:{mode:'COMPONENT_SUM'};const body=fixed?{name:this.bundleDraft.name,sku:this.bundleDraft.sku,type:'FIXED',pricingRule,components:this.bundleSellables.map((sellableId:string)=>({sellableId,quantity:1})),optionGroups:[]}:{name:this.bundleDraft.name,sku:this.bundleDraft.sku,type:'CONFIGURABLE',pricingRule,components:[],optionGroups:this.bundleGroups.map((group:any)=>({code:group.code,name:group.name,minimumSelections:Number(group.minimumSelections),maximumSelections:Number(group.maximumSelections),choices:group.sellables.map((sellableId:string)=>({sellableId,quantity:1}))}))};this.http.post('/api/v1/bundles',body).subscribe({next:()=>{this.showBundle=false;this.bundleMarketPrices=[];this.bundleDraft={name:'',sku:'',type:'CONFIGURABLE',pricingMode:'COMPONENT_SUM',fixedAmount:0,currency:'CHF',discountPercentage:10};this.bundleTypeChanged();this.error='';this.loadBundles()},error:e=>this.error=e.error?.message??e.error?.detail??'Could not create bundle.'})}
 editBundleTranslation(bundle:any):void{this.bundleTranslationBundle=bundle;this.bundleTranslationLocale='de';this.loadBundleTranslation()}
 loadBundleTranslation():void{if(!this.bundleTranslationBundle)return;this.bundleTranslationDraft={name:'',optionGroupNames:{}};this.http.get<any>(`/api/v1/bundles/${this.bundleTranslationBundle.id}/translations/${this.bundleTranslationLocale}`).subscribe({next:r=>this.bundleTranslationDraft={name:r.name??'',optionGroupNames:{...(r.optionGroupNames??{})}},error:e=>this.error=e.error?.message??e.error?.detail??'Could not load bundle translations.'})}
 saveBundleTranslation():void{if(!this.bundleTranslationBundle)return;this.http.put(`/api/v1/bundles/${this.bundleTranslationBundle.id}/translations/${this.bundleTranslationLocale}`,this.bundleTranslationDraft).subscribe({next:()=>this.error='',error:e=>this.error=e.error?.message??e.error?.detail??'Could not save bundle translations.'})} activateBundle(b:any):void{this.http.put(`/api/v1/bundles/${b.id}/active`,{active:!b.active}).subscribe(()=>this.loadBundles())}
 createPrice():void{if(!this.priceDraft.sellableId){this.error='Choose a product variant.';return;}this.http.post('/api/v1/prices',{...this.priceDraft,sellableId:this.priceDraft.sellableId,amount:Number(this.priceDraft.amount)}).subscribe({next:()=>{this.showPrice=false;this.loadPrices()},error:e=>this.error=e.error?.message??e.error?.detail??'Could not create price.'})}
 assignTaxCategory():void{if(!this.taxAssignment.sellableId){this.error='Choose a product variant.';return;}this.http.put(`/api/v1/tax-rules/sellables/${this.taxAssignment.sellableId}/category`,{category:this.taxAssignment.category}).subscribe({next:()=>{this.error='';this.taxAssignment={sellableId:'',category:'STANDARD'};this.loadPrices()},error:e=>this.error=e.error?.message??e.error?.detail??'Could not assign tax category.'})}
}
