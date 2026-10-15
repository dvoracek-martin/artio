import type { BackofficeLocale } from './backoffice-locale';

const COPY = {
  en: {
    eyebrow: 'CATALOG MEDIA', titleSuffix: 'images', close: 'Close', imageFile: 'Image file', alternativeText: 'Alternative text',
    alternativeTextPlaceholder: 'Describe the image', upload: 'Upload image', uploading: 'Uploading…', noAlternativeText: 'No alternative text',
    replace: 'Replace', earlier: '← Earlier', later: 'Later →', moveEarlier: 'Move image {index} earlier', moveLater: 'Move image {index} later',
    delete: 'Delete', deleteImage: 'Delete image {index}', empty: 'No product images yet.', loading: 'Loading product images…',
    loadFailure: 'Could not load product images.', uploadLimit: 'Image uploads are limited to 10 MiB.', unsupported: 'Choose a JPEG or PNG image.',
    uploadFailure: 'Could not upload the image.', replaceFailure: 'Could not replace the image.', reorderFailure: 'Could not reorder images.'
  },
  de: {
    eyebrow: 'KATALOGMEDIEN', titleSuffix: 'Bilder', close: 'Schließen', imageFile: 'Bilddatei', alternativeText: 'Alternativtext',
    alternativeTextPlaceholder: 'Bild beschreiben', upload: 'Bild hochladen', uploading: 'Wird hochgeladen…', noAlternativeText: 'Kein Alternativtext',
    replace: 'Ersetzen', earlier: '← Weiter nach oben', later: 'Weiter nach unten →', moveEarlier: 'Bild {index} nach oben verschieben', moveLater: 'Bild {index} nach unten verschieben',
    delete: 'Löschen', deleteImage: 'Bild {index} löschen', empty: 'Noch keine Produktbilder vorhanden.', loading: 'Produktbilder werden geladen…',
    loadFailure: 'Produktbilder konnten nicht geladen werden.', uploadLimit: 'Bilder dürfen höchstens 10 MiB groß sein.', unsupported: 'Wählen Sie ein JPEG- oder PNG-Bild aus.',
    uploadFailure: 'Das Bild konnte nicht hochgeladen werden.', replaceFailure: 'Das Bild konnte nicht ersetzt werden.', reorderFailure: 'Die Bildreihenfolge konnte nicht geändert werden.'
  },
  it: {
    eyebrow: 'MEDIA DEL CATALOGO', titleSuffix: 'immagini', close: 'Chiudi', imageFile: 'File immagine', alternativeText: 'Testo alternativo',
    alternativeTextPlaceholder: 'Descrivi l’immagine', upload: 'Carica immagine', uploading: 'Caricamento…', noAlternativeText: 'Nessun testo alternativo',
    replace: 'Sostituisci', earlier: '← Anticipa', later: 'Posticipa →', moveEarlier: 'Anticipa immagine {index}', moveLater: 'Posticipa immagine {index}',
    delete: 'Elimina', deleteImage: 'Elimina immagine {index}', empty: 'Non ci sono ancora immagini del prodotto.', loading: 'Caricamento delle immagini del prodotto…',
    loadFailure: 'Impossibile caricare le immagini del prodotto.', uploadLimit: 'Le immagini non possono superare 10 MiB.', unsupported: 'Scegli un’immagine JPEG o PNG.',
    uploadFailure: 'Impossibile caricare l’immagine.', replaceFailure: 'Impossibile sostituire l’immagine.', reorderFailure: 'Impossibile riordinare le immagini.'
  },
  fr: {
    eyebrow: 'MÉDIAS DU CATALOGUE', titleSuffix: 'images', close: 'Fermer', imageFile: 'Fichier image', alternativeText: 'Texte alternatif',
    alternativeTextPlaceholder: 'Décrivez l’image', upload: 'Téléverser une image', uploading: 'Téléversement…', noAlternativeText: 'Aucun texte alternatif',
    replace: 'Remplacer', earlier: '← Monter', later: 'Descendre →', moveEarlier: 'Monter l’image {index}', moveLater: 'Descendre l’image {index}',
    delete: 'Supprimer', deleteImage: 'Supprimer l’image {index}', empty: 'Aucune image produit pour le moment.', loading: 'Chargement des images produit…',
    loadFailure: 'Impossible de charger les images produit.', uploadLimit: 'Les images ne peuvent pas dépasser 10 Mio.', unsupported: 'Choisissez une image JPEG ou PNG.',
    uploadFailure: 'Impossible de téléverser l’image.', replaceFailure: 'Impossible de remplacer l’image.', reorderFailure: 'Impossible de réorganiser les images.'
  }
} as const;

export type ProductMediaLocaleKey = keyof typeof COPY.en;

export function productMediaText(locale: BackofficeLocale, key: ProductMediaLocaleKey, values: Record<string, string> = {}): string {
  return Object.entries(values).reduce<string>((text, [name, value]) => text.replaceAll(`{${name}}`, value), COPY[locale][key]);
}
