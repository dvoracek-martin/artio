import { defineBackofficeLocaleCopy, type BackofficeLocale } from './backoffice-locale';

const COPY = defineBackofficeLocaleCopy({
  en: {
    paymentReview: 'FINANCIAL REVIEW',
    paymentReviewTitle: 'Payment captures requiring review',
    paymentReviewHelp: 'Verified provider captures differ from the expected amount or currency. This queue is read-only; inspect the order before taking action.',
    paymentReviewLoading: 'Loading payment review cases…',
    paymentReviewFailure: 'Could not load payment reconciliation cases.',
    paymentReviewEmpty: 'No payment cases need review.',
    paymentExpected: 'Expected', paymentCaptured: 'Captured', openReviewOrder: 'Open order', refreshReview: 'Refresh',
    case_UNDERPAYMENT: 'Underpayment', case_OVERPAYMENT: 'Overpayment', case_CURRENCY_MISMATCH: 'Currency mismatch',
    case_LATE_CAPTURE: 'Late capture', case_CHARGEBACK: 'Chargeback', case_PAYMENT_REVERSAL: 'Payment reversal',
  },
  de: {
    paymentReview: 'FINANZIELLE PRÜFUNG',
    paymentReviewTitle: 'Zu prüfende Zahlungseingänge',
    paymentReviewHelp: 'Bestätigte Provider-Eingänge weichen beim Betrag oder bei der Währung ab. Diese Liste ist schreibgeschützt; prüfen Sie die Bestellung vor einer Aktion.',
    paymentReviewLoading: 'Zahlungsfälle werden geladen…',
    paymentReviewFailure: 'Zahlungsabgleichsfälle konnten nicht geladen werden.',
    paymentReviewEmpty: 'Keine Zahlungsfälle müssen geprüft werden.',
    paymentExpected: 'Erwartet', paymentCaptured: 'Eingegangen', openReviewOrder: 'Bestellung öffnen', refreshReview: 'Aktualisieren',
    case_UNDERPAYMENT: 'Unterzahlung', case_OVERPAYMENT: 'Überzahlung', case_CURRENCY_MISMATCH: 'Währungsabweichung',
    case_LATE_CAPTURE: 'Verspäteter Zahlungseingang', case_CHARGEBACK: 'Rückbuchung', case_PAYMENT_REVERSAL: 'Zahlungsstorno',
  },
  it: {
    paymentReview: 'VERIFICA FINANZIARIA',
    paymentReviewTitle: 'Incassi da verificare',
    paymentReviewHelp: 'Gli incassi confermati dal provider differiscono per importo o valuta. Questa coda è in sola lettura; controlla l’ordine prima di agire.',
    paymentReviewLoading: 'Caricamento dei casi di pagamento…',
    paymentReviewFailure: 'Impossibile caricare i casi di riconciliazione dei pagamenti.',
    paymentReviewEmpty: 'Nessun caso di pagamento da verificare.',
    paymentExpected: 'Atteso', paymentCaptured: 'Incassato', openReviewOrder: 'Apri ordine', refreshReview: 'Aggiorna',
    case_UNDERPAYMENT: 'Pagamento insufficiente', case_OVERPAYMENT: 'Pagamento in eccesso', case_CURRENCY_MISMATCH: 'Valuta diversa',
    case_LATE_CAPTURE: 'Incasso tardivo', case_CHARGEBACK: 'Contestazione', case_PAYMENT_REVERSAL: 'Storno del pagamento',
  },
  fr: {
    paymentReview: 'VÉRIFICATION FINANCIÈRE',
    paymentReviewTitle: 'Encaissements à vérifier',
    paymentReviewHelp: 'Les encaissements confirmés par le prestataire diffèrent par le montant ou la devise. Cette liste est en lecture seule ; examinez la commande avant toute action.',
    paymentReviewLoading: 'Chargement des cas de paiement…',
    paymentReviewFailure: 'Impossible de charger les cas de rapprochement des paiements.',
    paymentReviewEmpty: 'Aucun cas de paiement à vérifier.',
    paymentExpected: 'Attendu', paymentCaptured: 'Encaissé', openReviewOrder: 'Ouvrir la commande', refreshReview: 'Actualiser',
    case_UNDERPAYMENT: 'Paiement insuffisant', case_OVERPAYMENT: 'Paiement excédentaire', case_CURRENCY_MISMATCH: 'Devise différente',
    case_LATE_CAPTURE: 'Encaissement tardif', case_CHARGEBACK: 'Rétrofacturation', case_PAYMENT_REVERSAL: 'Annulation du paiement',
  },
});

export type PaymentReconciliationTextKey = keyof typeof COPY.en;
export function paymentReconciliationText(locale: BackofficeLocale, key: PaymentReconciliationTextKey): string {
  return COPY[locale][key];
}
