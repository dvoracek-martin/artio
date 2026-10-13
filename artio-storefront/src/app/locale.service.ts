import { Injectable } from '@angular/core';

type Locale = 'en' | 'cs' | 'de';
const MESSAGES: Record<Locale, Record<string,string>> = {
  en: { shop:'Shop', bundles:'Build a bundle', cart:'Cart', orders:'My orders', signIn:'Sign in', signOut:'Sign out', search:'Search products', consentTitle:'Your privacy matters', consentBody:'Essential storage keeps Artio working. Optional analytics and marketing stay off unless you choose them.', essential:'Essential', analytics:'Analytics', marketing:'Marketing', accept:'Save preferences', reject:'Reject optional', manage:'Privacy choices', mfa:'Authenticator code or backup code' },
  cs: { shop:'Obchod', bundles:'Sestavit balíček', cart:'Košík', orders:'Moje objednávky', signIn:'Přihlásit se', signOut:'Odhlásit se', search:'Hledat produkty', consentTitle:'Vaše soukromí je důležité', consentBody:'Nezbytné úložiště zajišťuje fungování Artio. Analytika a marketing zůstávají vypnuté, dokud je nepovolíte.', essential:'Nezbytné', analytics:'Analytika', marketing:'Marketing', accept:'Uložit nastavení', reject:'Odmítnout volitelné', manage:'Nastavení soukromí', mfa:'Kód z autentizační aplikace nebo záložní kód' },
  de: { shop:'Shop', bundles:'Bundle zusammenstellen', cart:'Warenkorb', orders:'Meine Bestellungen', signIn:'Anmelden', signOut:'Abmelden', search:'Produkte suchen', consentTitle:'Ihre Privatsphäre ist wichtig', consentBody:'Notwendiger Speicher hält Artio funktionsfähig. Analyse und Marketing bleiben deaktiviert, bis Sie zustimmen.', essential:'Notwendig', analytics:'Analyse', marketing:'Marketing', accept:'Einstellungen speichern', reject:'Optionale ablehnen', manage:'Datenschutzeinstellungen', mfa:'Authenticator- oder Ersatzcode' }
};

@Injectable({providedIn:'root'})
export class LocaleService {
  current(): Locale { const saved=typeof localStorage==='undefined'?null:localStorage.getItem('artio-locale'); return saved==='cs'||saved==='de'?saved:'en'; }
  set(locale: Locale): void { if(typeof localStorage!=='undefined') localStorage.setItem('artio-locale',locale); }
  translate(key:string,locale:Locale=this.current()):string { return MESSAGES[locale][key]??MESSAGES.en[key]??key; }
}
