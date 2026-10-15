import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { setBackofficeLocale, type BackofficeLocale } from './backoffice-locale';
import { PricingAdminComponent } from './pricing-admin.component';

describe('PricingAdminComponent',()=>{
 beforeEach(async()=>{await TestBed.configureTestingModule({imports:[PricingAdminComponent]}).compileComponents()});
 afterEach(()=>localStorage.removeItem('artio-backoffice-language'));
 it.each([
  ['en','Manage selling prices by market and channel.','Sellable tax categories','No selling prices have been configured.'],
  ['de','Verwalten Sie Verkaufspreise nach Markt und Vertriebskanal.','Steuerkategorien der Verkaufsartikel','Es wurden noch keine Verkaufspreise eingerichtet.'],
  ['it','Gestisci i prezzi di vendita per mercato e canale.','Categorie fiscali degli articoli','Non è ancora stato configurato alcun prezzo di vendita.'],
  ['fr','Gérez les prix de vente par marché et canal.','Catégories fiscales des articles','Aucun prix de vente n’a encore été configuré.']
 ] as const)('renders pricing and tax states in %s',(locale,intro,taxTitle,empty)=>{
  setBackofficeLocale(locale as BackofficeLocale);const fixture=TestBed.createComponent(PricingAdminComponent);fixture.detectChanges();const page=fixture.nativeElement.textContent as string;
  expect(page).toContain(intro);expect(page).toContain(taxTitle);expect(page).toContain(empty);
 });
 it('emits price and category actions to the owning page',()=>{
  const fixture=TestBed.createComponent(PricingAdminComponent);const actions:string[]=[];fixture.componentInstance.action.subscribe(action=>actions.push(action.type));fixture.detectChanges();
  const add=[...fixture.nativeElement.querySelectorAll('button')].find((button:HTMLButtonElement)=>button.textContent?.includes('Add price')) as HTMLButtonElement;add.click();
  expect(actions).toEqual(['togglePrice']);
 });
});
