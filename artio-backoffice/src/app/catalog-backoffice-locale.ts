import type { BackofficeLocale } from './backoffice-locale';

const CATALOG_COPY = {
  en: {
    structure: 'CATALOG STRUCTURE', categories: 'Categories', refresh: 'Refresh', categoryName: 'Category name',
    edit: 'Edit', parentCategory: 'Parent category', topLevel: 'Top-level category', addCategory: 'Add category',
    childOf: 'Child of {name}', active: 'Active', inactive: 'Inactive', deactivate: 'Deactivate', activate: 'Activate',
    noCategories: 'No categories are configured.', classification: 'PRODUCT CLASSIFICATION', assignData: 'Assign catalog data',
    product: 'Product', productToClassify: 'Product to classify', chooseProduct: 'Choose a product', brand: 'Brand',
    productBrand: 'Product brand', noBrand: 'No brand', categoriesLabel: 'Categories', chooseCategories: 'Choose categories',
    attributes: 'Attributes', required: 'Required', optional: 'Optional', inactiveRetained: 'inactive, retained read-only',
    unset: 'Unset', yes: 'Yes', no: 'No', saveClassification: 'Save product classification',
    inactiveDefinitionsHelp: 'Inactive categories and definitions cannot be newly assigned. Existing inactive attribute values are preserved until the definition is reactivated.',
    manufacturers: 'MANUFACTURERS', brands: 'Brands', brandName: 'Brand name', description: 'Description', addBrand: 'Add brand', noBrands: 'No brands are configured.',
    productData: 'PRODUCT DATA', attributeDefinitions: 'Attribute definitions', code: 'CODE', label: 'Label', text: 'Text', number: 'Number', boolean: 'Boolean', addAttribute: 'Add attribute', rename: 'Rename', noAttributes: 'No attribute definitions are configured.',
    loadCategoriesFailure: 'Could not load categories.', loadBrandsFailure: 'Could not load brands.', loadAttributesFailure: 'Could not load attribute definitions.',
    loadProductsFailure: 'Could not load products for classification.', saveClassificationFailure: 'Could not save product classification.',
    createCategoryFailure: 'Could not create category.', changeCategoryFailure: 'Could not change category status.',
    createBrandFailure: 'Could not create brand.', changeBrandFailure: 'Could not change brand status.',
    createAttributeFailure: 'Could not create attribute definition.', changeAttributeFailure: 'Could not change attribute status.',
    unknownParent: 'Unknown parent'
  },
  de: {
    structure: 'KATALOGSTRUKTUR', categories: 'Kategorien', refresh: 'Aktualisieren', categoryName: 'Kategoriename',
    edit: 'Bearbeiten', parentCategory: 'Übergeordnete Kategorie', topLevel: 'Oberste Ebene', addCategory: 'Kategorie hinzufügen',
    childOf: 'Unterkategorie von {name}', active: 'Aktiv', inactive: 'Inaktiv', deactivate: 'Deaktivieren', activate: 'Aktivieren',
    noCategories: 'Es sind keine Kategorien konfiguriert.', classification: 'PRODUKTKLASSIFIZIERUNG', assignData: 'Katalogdaten zuweisen',
    product: 'Produkt', productToClassify: 'Zu klassifizierendes Produkt', chooseProduct: 'Produkt auswählen', brand: 'Marke',
    productBrand: 'Produktmarke', noBrand: 'Keine Marke', categoriesLabel: 'Kategorien', chooseCategories: 'Kategorien auswählen',
    attributes: 'Attribute', required: 'Erforderlich', optional: 'Optional', inactiveRetained: 'inaktiv, schreibgeschützt beibehalten',
    unset: 'Nicht festgelegt', yes: 'Ja', no: 'Nein', saveClassification: 'Produktklassifizierung speichern',
    inactiveDefinitionsHelp: 'Inaktive Kategorien und Definitionen können nicht neu zugewiesen werden. Vorhandene Werte inaktiver Attribute bleiben erhalten, bis die Definition wieder aktiviert wird.',
    manufacturers: 'HERSTELLER', brands: 'Marken', brandName: 'Markenname', description: 'Beschreibung', addBrand: 'Marke hinzufügen', noBrands: 'Es sind keine Marken konfiguriert.',
    productData: 'PRODUKTDATEN', attributeDefinitions: 'Attributdefinitionen', code: 'CODE', label: 'Bezeichnung', text: 'Text', number: 'Zahl', boolean: 'Wahrheitswert', addAttribute: 'Attribut hinzufügen', rename: 'Umbenennen', noAttributes: 'Es sind keine Attributdefinitionen konfiguriert.',
    loadCategoriesFailure: 'Kategorien konnten nicht geladen werden.', loadBrandsFailure: 'Marken konnten nicht geladen werden.', loadAttributesFailure: 'Attributdefinitionen konnten nicht geladen werden.',
    loadProductsFailure: 'Produkte für die Klassifizierung konnten nicht geladen werden.', saveClassificationFailure: 'Produktklassifizierung konnte nicht gespeichert werden.',
    createCategoryFailure: 'Kategorie konnte nicht erstellt werden.', changeCategoryFailure: 'Kategorienstatus konnte nicht geändert werden.',
    createBrandFailure: 'Marke konnte nicht erstellt werden.', changeBrandFailure: 'Markenstatus konnte nicht geändert werden.',
    createAttributeFailure: 'Attributdefinition konnte nicht erstellt werden.', changeAttributeFailure: 'Attributstatus konnte nicht geändert werden.',
    unknownParent: 'Unbekannte übergeordnete Kategorie'
  },
  it: {
    structure: 'STRUTTURA DEL CATALOGO', categories: 'Categorie', refresh: 'Aggiorna', categoryName: 'Nome categoria',
    edit: 'Modifica', parentCategory: 'Categoria superiore', topLevel: 'Livello principale', addCategory: 'Aggiungi categoria',
    childOf: 'Sottocategoria di {name}', active: 'Attiva', inactive: 'Inattiva', deactivate: 'Disattiva', activate: 'Attiva',
    noCategories: 'Non è configurata alcuna categoria.', classification: 'CLASSIFICAZIONE PRODOTTI', assignData: 'Assegna dati catalogo',
    product: 'Prodotto', productToClassify: 'Prodotto da classificare', chooseProduct: 'Scegli un prodotto', brand: 'Marca',
    productBrand: 'Marca del prodotto', noBrand: 'Nessuna marca', categoriesLabel: 'Categorie', chooseCategories: 'Scegli le categorie',
    attributes: 'Attributi', required: 'Obbligatorio', optional: 'Facoltativo', inactiveRetained: 'inattivo, mantenuto in sola lettura',
    unset: 'Non impostato', yes: 'Sì', no: 'No', saveClassification: 'Salva la classificazione del prodotto',
    inactiveDefinitionsHelp: 'Le categorie e definizioni inattive non possono essere assegnate. I valori esistenti degli attributi inattivi vengono conservati finché la definizione non viene riattivata.',
    manufacturers: 'PRODUTTORI', brands: 'Marche', brandName: 'Nome marca', description: 'Descrizione', addBrand: 'Aggiungi marca', noBrands: 'Non è configurata alcuna marca.',
    productData: 'DATI PRODOTTO', attributeDefinitions: 'Definizioni degli attributi', code: 'CODICE', label: 'Etichetta', text: 'Testo', number: 'Numero', boolean: 'Booleano', addAttribute: 'Aggiungi attributo', rename: 'Rinomina', noAttributes: 'Non è configurata alcuna definizione di attributo.',
    loadCategoriesFailure: 'Impossibile caricare le categorie.', loadBrandsFailure: 'Impossibile caricare le marche.', loadAttributesFailure: 'Impossibile caricare le definizioni degli attributi.',
    loadProductsFailure: 'Impossibile caricare i prodotti da classificare.', saveClassificationFailure: 'Impossibile salvare la classificazione del prodotto.',
    createCategoryFailure: 'Impossibile creare la categoria.', changeCategoryFailure: 'Impossibile modificare lo stato della categoria.',
    createBrandFailure: 'Impossibile creare la marca.', changeBrandFailure: 'Impossibile modificare lo stato della marca.',
    createAttributeFailure: 'Impossibile creare la definizione dell’attributo.', changeAttributeFailure: 'Impossibile modificare lo stato dell’attributo.',
    unknownParent: 'Categoria superiore sconosciuta'
  },
  fr: {
    structure: 'STRUCTURE DU CATALOGUE', categories: 'Catégories', refresh: 'Actualiser', categoryName: 'Nom de la catégorie',
    edit: 'Modifier', parentCategory: 'Catégorie parente', topLevel: 'Niveau supérieur', addCategory: 'Ajouter une catégorie',
    childOf: 'Sous-catégorie de {name}', active: 'Active', inactive: 'Inactive', deactivate: 'Désactiver', activate: 'Activer',
    noCategories: 'Aucune catégorie n’est configurée.', classification: 'CLASSIFICATION DES PRODUITS', assignData: 'Attribuer les données du catalogue',
    product: 'Produit', productToClassify: 'Produit à classer', chooseProduct: 'Choisir un produit', brand: 'Marque',
    productBrand: 'Marque du produit', noBrand: 'Aucune marque', categoriesLabel: 'Catégories', chooseCategories: 'Choisir des catégories',
    attributes: 'Attributs', required: 'Obligatoire', optional: 'Facultatif', inactiveRetained: 'inactif, conservé en lecture seule',
    unset: 'Non défini', yes: 'Oui', no: 'Non', saveClassification: 'Enregistrer la classification du produit',
    inactiveDefinitionsHelp: 'Les catégories et définitions inactives ne peuvent pas être attribuées. Les valeurs existantes des attributs inactifs sont conservées jusqu’à la réactivation de la définition.',
    manufacturers: 'FABRICANTS', brands: 'Marques', brandName: 'Nom de la marque', description: 'Description', addBrand: 'Ajouter une marque', noBrands: 'Aucune marque n’est configurée.',
    productData: 'DONNÉES PRODUIT', attributeDefinitions: 'Définitions des attributs', code: 'CODE', label: 'Libellé', text: 'Texte', number: 'Nombre', boolean: 'Booléen', addAttribute: 'Ajouter un attribut', rename: 'Renommer', noAttributes: 'Aucune définition d’attribut n’est configurée.',
    loadCategoriesFailure: 'Impossible de charger les catégories.', loadBrandsFailure: 'Impossible de charger les marques.', loadAttributesFailure: 'Impossible de charger les définitions des attributs.',
    loadProductsFailure: 'Impossible de charger les produits à classer.', saveClassificationFailure: 'Impossible d’enregistrer la classification du produit.',
    createCategoryFailure: 'Impossible de créer la catégorie.', changeCategoryFailure: 'Impossible de modifier le statut de la catégorie.',
    createBrandFailure: 'Impossible de créer la marque.', changeBrandFailure: 'Impossible de modifier le statut de la marque.',
    createAttributeFailure: 'Impossible de créer la définition de l’attribut.', changeAttributeFailure: 'Impossible de modifier le statut de l’attribut.',
    unknownParent: 'Catégorie parente inconnue'
  }
} as const;

export type CatalogTextKey = keyof typeof CATALOG_COPY.en;

export function catalogText(locale: BackofficeLocale, key: CatalogTextKey): string {
  return CATALOG_COPY[locale][key];
}
