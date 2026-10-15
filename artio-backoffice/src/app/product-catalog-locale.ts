import type { BackofficeLocale } from './backoffice-locale';

const COPY = {
  en: {
    eyebrow: 'YOUR CATALOG', title: 'Made to stand out.', intro: 'Build, organize and refine your product collection.',
    newProduct: 'New product', importCsv: 'Import CSV', exportCsv: 'Export CSV', searchLabel: 'Search products', search: 'Search',
    status: 'Product status', allProducts: 'All products', active: 'Active', inactive: 'Inactive drafts', archived: 'Archived',
    brandFilter: 'Filter by brand', allBrands: 'All brands', categoryFilter: 'Filter by category', allCategories: 'All categories',
    sortBy: 'Sort products by', updated: 'Recently updated', created: 'Recently created', sortName: 'Name', sortSku: 'SKU',
    sortDirection: 'Sort direction', ascending: 'Ascending', descending: 'Descending', rebuildIndex: 'Rebuild search index',
    rebuildingIndex: 'Rebuilding search documents…', indexSuccess: 'Indexed {products} products and {translations} translations.', indexFailure: 'Search index rebuild failed.',
    importReport: 'PRODUCT CSV IMPORT', importSummary: '{created} created · {updated} updated · {failed} failed', rowsProcessed: '{rows} rows processed',
    row: 'Row', noSku: 'No SKU', dismiss: 'Dismiss', productHeader: 'PRODUCT', skuVariantHeader: 'SKU / VARIANT', statusHeader: 'STATUS', actionHeader: 'ACTION',
    seo: 'SEO', notSet: 'Not set', activeVariant: 'Active', inactiveVariant: 'Inactive', editOptions: 'Edit options',
    activate: 'Activate', deactivate: 'Deactivate', publishes: 'Publishes {date}', manage: 'Manage product', archive: 'Archive', restore: 'Restore',
    addVariant: 'Add variant', schedule: 'Schedule publish', cancelSchedule: 'Cancel schedule', media: 'Media', duplicate: 'Duplicate', translations: 'Translations',
    empty: 'No products found.', productCount: '{count} products · Page {page} of {pages}', previous: 'Previous', next: 'Next',
    translationEyebrow: 'LOCALIZED CATALOG', close: 'Close', english: 'English', german: 'German', italian: 'Italian', french: 'French',
    translatedName: 'Translated product name', translatedDescription: 'Translated description', canonicalSlug: 'Localized canonical slug',
    slugExample: 'e.g. lampe-de-bureau', seoTitle: 'Localized SEO title', seoDescription: 'Localized SEO description', saveTranslation: 'Save translation',
    noDescription: 'No description', exportFailure: 'Could not export products.', invalidCsv: 'Choose a .csv file.', importFailure: 'Could not import products.',
    loadFailure: 'Could not load products.', loadTranslationFailure: 'Could not load product translations.', saveTranslationFailure: 'Could not save product translation.'
  },
  de: {
    eyebrow: 'IHR KATALOG', title: 'Produkte, die auffallen.', intro: 'Erstellen, ordnen und pflegen Sie Ihre Produktsammlung.',
    newProduct: 'Neues Produkt', importCsv: 'CSV importieren', exportCsv: 'CSV exportieren', searchLabel: 'Produkte suchen', search: 'Suchen',
    status: 'Produktstatus', allProducts: 'Alle Produkte', active: 'Aktiv', inactive: 'Inaktive Entwürfe', archived: 'Archiviert',
    brandFilter: 'Nach Marke filtern', allBrands: 'Alle Marken', categoryFilter: 'Nach Kategorie filtern', allCategories: 'Alle Kategorien',
    sortBy: 'Produkte sortieren nach', updated: 'Zuletzt aktualisiert', created: 'Zuletzt erstellt', sortName: 'Name', sortSku: 'SKU',
    sortDirection: 'Sortierreihenfolge', ascending: 'Aufsteigend', descending: 'Absteigend', rebuildIndex: 'Suchindex neu aufbauen',
    rebuildingIndex: 'Suchdokumente werden neu aufgebaut…', indexSuccess: '{products} Produkte und {translations} Übersetzungen indexiert.', indexFailure: 'Suchindex konnte nicht neu aufgebaut werden.',
    importReport: 'PRODUKT-CSV-IMPORT', importSummary: '{created} erstellt · {updated} aktualisiert · {failed} fehlgeschlagen', rowsProcessed: '{rows} Zeilen verarbeitet',
    row: 'Zeile', noSku: 'Keine SKU', dismiss: 'Schließen', productHeader: 'PRODUKT', skuVariantHeader: 'SKU / VARIANTE', statusHeader: 'STATUS', actionHeader: 'AKTION',
    seo: 'SEO', notSet: 'Nicht festgelegt', activeVariant: 'Aktiv', inactiveVariant: 'Inaktiv', editOptions: 'Optionen bearbeiten',
    activate: 'Aktivieren', deactivate: 'Deaktivieren', publishes: 'Veröffentlichung am {date}', manage: 'Produkt verwalten', archive: 'Archivieren', restore: 'Wiederherstellen',
    addVariant: 'Variante hinzufügen', schedule: 'Veröffentlichung planen', cancelSchedule: 'Planung abbrechen', media: 'Medien', duplicate: 'Duplizieren', translations: 'Übersetzungen',
    empty: 'Keine Produkte gefunden.', productCount: '{count} Produkte · Seite {page} von {pages}', previous: 'Zurück', next: 'Weiter',
    translationEyebrow: 'LOKALISIERTER KATALOG', close: 'Schließen', english: 'Englisch', german: 'Deutsch', italian: 'Italienisch', french: 'Französisch',
    translatedName: 'Übersetzter Produktname', translatedDescription: 'Übersetzte Beschreibung', canonicalSlug: 'Lokalisierter kanonischer Slug',
    slugExample: 'z. B. schreibtischlampe', seoTitle: 'Lokalisierter SEO-Titel', seoDescription: 'Lokalisierte SEO-Beschreibung', saveTranslation: 'Übersetzung speichern',
    noDescription: 'Keine Beschreibung', exportFailure: 'Produkte konnten nicht exportiert werden.', invalidCsv: 'Wählen Sie eine CSV-Datei aus.', importFailure: 'Produkte konnten nicht importiert werden.',
    loadFailure: 'Produkte konnten nicht geladen werden.', loadTranslationFailure: 'Produktübersetzungen konnten nicht geladen werden.', saveTranslationFailure: 'Produktübersetzung konnte nicht gespeichert werden.'
  },
  it: {
    eyebrow: 'IL TUO CATALOGO', title: 'Prodotti che si distinguono.', intro: 'Crea, organizza e aggiorna la tua raccolta di prodotti.',
    newProduct: 'Nuovo prodotto', importCsv: 'Importa CSV', exportCsv: 'Esporta CSV', searchLabel: 'Cerca prodotti', search: 'Cerca',
    status: 'Stato prodotto', allProducts: 'Tutti i prodotti', active: 'Attivo', inactive: 'Bozze inattive', archived: 'Archiviato',
    brandFilter: 'Filtra per marca', allBrands: 'Tutte le marche', categoryFilter: 'Filtra per categoria', allCategories: 'Tutte le categorie',
    sortBy: 'Ordina i prodotti per', updated: 'Modificati di recente', created: 'Creati di recente', sortName: 'Nome', sortSku: 'SKU',
    sortDirection: 'Direzione di ordinamento', ascending: 'Crescente', descending: 'Decrescente', rebuildIndex: 'Ricrea indice di ricerca',
    rebuildingIndex: 'Ricreazione dei documenti di ricerca…', indexSuccess: 'Indicizzati {products} prodotti e {translations} traduzioni.', indexFailure: 'Impossibile ricreare l’indice di ricerca.',
    importReport: 'IMPORTAZIONE CSV PRODOTTI', importSummary: '{created} creati · {updated} aggiornati · {failed} non riusciti', rowsProcessed: '{rows} righe elaborate',
    row: 'Riga', noSku: 'SKU assente', dismiss: 'Chiudi', productHeader: 'PRODOTTO', skuVariantHeader: 'SKU / VARIANTE', statusHeader: 'STATO', actionHeader: 'AZIONE',
    seo: 'SEO', notSet: 'Non impostato', activeVariant: 'Attiva', inactiveVariant: 'Inattiva', editOptions: 'Modifica opzioni',
    activate: 'Attiva', deactivate: 'Disattiva', publishes: 'Pubblicazione {date}', manage: 'Gestisci prodotto', archive: 'Archivia', restore: 'Ripristina',
    addVariant: 'Aggiungi variante', schedule: 'Programma pubblicazione', cancelSchedule: 'Annulla programmazione', media: 'Media', duplicate: 'Duplica', translations: 'Traduzioni',
    empty: 'Nessun prodotto trovato.', productCount: '{count} prodotti · Pagina {page} di {pages}', previous: 'Precedente', next: 'Successiva',
    translationEyebrow: 'CATALOGO LOCALIZZATO', close: 'Chiudi', english: 'Inglese', german: 'Tedesco', italian: 'Italiano', french: 'Francese',
    translatedName: 'Nome del prodotto tradotto', translatedDescription: 'Descrizione tradotta', canonicalSlug: 'Slug canonico localizzato',
    slugExample: 'es. lampada-da-scrivania', seoTitle: 'Titolo SEO localizzato', seoDescription: 'Descrizione SEO localizzata', saveTranslation: 'Salva traduzione',
    noDescription: 'Nessuna descrizione', exportFailure: 'Impossibile esportare i prodotti.', invalidCsv: 'Scegli un file CSV.', importFailure: 'Impossibile importare i prodotti.',
    loadFailure: 'Impossibile caricare i prodotti.', loadTranslationFailure: 'Impossibile caricare le traduzioni del prodotto.', saveTranslationFailure: 'Impossibile salvare la traduzione del prodotto.'
  },
  fr: {
    eyebrow: 'VOTRE CATALOGUE', title: 'Des produits qui se démarquent.', intro: 'Créez, organisez et gérez votre collection de produits.',
    newProduct: 'Nouveau produit', importCsv: 'Importer un CSV', exportCsv: 'Exporter un CSV', searchLabel: 'Rechercher des produits', search: 'Rechercher',
    status: 'État du produit', allProducts: 'Tous les produits', active: 'Actifs', inactive: 'Brouillons inactifs', archived: 'Archivés',
    brandFilter: 'Filtrer par marque', allBrands: 'Toutes les marques', categoryFilter: 'Filtrer par catégorie', allCategories: 'Toutes les catégories',
    sortBy: 'Trier les produits par', updated: 'Modifiés récemment', created: 'Créés récemment', sortName: 'Nom', sortSku: 'SKU',
    sortDirection: 'Sens du tri', ascending: 'Croissant', descending: 'Décroissant', rebuildIndex: 'Reconstruire l’index de recherche',
    rebuildingIndex: 'Reconstruction des documents de recherche…', indexSuccess: '{products} produits et {translations} traductions indexés.', indexFailure: 'Impossible de reconstruire l’index de recherche.',
    importReport: 'IMPORT CSV DES PRODUITS', importSummary: '{created} créés · {updated} mis à jour · {failed} en échec', rowsProcessed: '{rows} lignes traitées',
    row: 'Ligne', noSku: 'Sans SKU', dismiss: 'Fermer', productHeader: 'PRODUIT', skuVariantHeader: 'SKU / VARIANTE', statusHeader: 'ÉTAT', actionHeader: 'ACTION',
    seo: 'SEO', notSet: 'Non défini', activeVariant: 'Active', inactiveVariant: 'Inactive', editOptions: 'Modifier les options',
    activate: 'Activer', deactivate: 'Désactiver', publishes: 'Publication le {date}', manage: 'Gérer le produit', archive: 'Archiver', restore: 'Restaurer',
    addVariant: 'Ajouter une variante', schedule: 'Planifier la publication', cancelSchedule: 'Annuler la planification', media: 'Médias', duplicate: 'Dupliquer', translations: 'Traductions',
    empty: 'Aucun produit trouvé.', productCount: '{count} produits · Page {page} sur {pages}', previous: 'Précédent', next: 'Suivant',
    translationEyebrow: 'CATALOGUE LOCALISÉ', close: 'Fermer', english: 'Anglais', german: 'Allemand', italian: 'Italien', french: 'Français',
    translatedName: 'Nom du produit traduit', translatedDescription: 'Description traduite', canonicalSlug: 'Slug canonique localisé',
    slugExample: 'ex. lampe-de-bureau', seoTitle: 'Titre SEO localisé', seoDescription: 'Description SEO localisée', saveTranslation: 'Enregistrer la traduction',
    noDescription: 'Aucune description', exportFailure: 'Impossible d’exporter les produits.', invalidCsv: 'Choisissez un fichier CSV.', importFailure: 'Impossible d’importer les produits.',
    loadFailure: 'Impossible de charger les produits.', loadTranslationFailure: 'Impossible de charger les traductions du produit.', saveTranslationFailure: 'Impossible d’enregistrer la traduction du produit.'
  }
} as const satisfies Record<BackofficeLocale, Record<string, string>>;

export type ProductCatalogTextKey = keyof typeof COPY.en;

export function productCatalogText(locale: BackofficeLocale, key: ProductCatalogTextKey): string {
  return COPY[locale][key];
}
