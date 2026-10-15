export type BackofficeLocale = 'de' | 'it' | 'fr' | 'en';

const COPY = {
  en: {
    language: 'Language',
    kafkaConsumerHealth: 'Consumer health', kafkaMessagesBehind: 'messages behind', kafkaPartition: 'PARTITION', kafkaCommitted: 'COMMITTED', kafkaEnd: 'END', kafkaLag: 'LAG', kafkaLagUnavailable: 'Consumer lag unavailable; verify Kafka broker and topic.',
    reportsEyebrow: 'OPERATIONAL REPORTS', reportsTitle: 'Store performance', period: 'Period', periodLast: 'Last {days} days',
    reportActivityHelp: 'Order activity follows the order creation date. Each monetary figure stays in its recorded currency.',
    ordersCreated: 'Orders created', selectedPeriod: 'Selected period', customersActive: 'Customers active', withOrder: 'With an order in this period',
    capturedRefunded: 'Captured and refunded by currency', currency: 'CURRENCY', orders: 'ORDERS', customers: 'CUSTOMERS', captured: 'CAPTURED', refunded: 'REFUNDED', netCaptured: 'NET CAPTURED',
    noOrders: 'No orders were created during this period.', orderStatus: 'Order status', status: 'STATUS', noActivity: 'No order activity to show.',
    enterprise: 'ENTERPRISE', advancedReports: 'Advanced reports', advancedAvailable: 'Available', enterpriseRequired: 'Enterprise license required',
    advancedExplanation: 'Ranked variants cover fully captured orders in the selected period. Customer lifetime values include successful payment history before the end of the period.',
    advancedRequiredText: 'Advanced reports require the ADVANCED_REPORTING entitlement. Standard reports remain available.',
    topVariants: 'Top variants by currency', historicalProduct: 'Historical product', rank: 'RANK', productVariant: 'PRODUCT / VARIANT', sku: 'SKU', units: 'UNITS', recordedLineValue: 'RECORDED LINE VALUE',
    recordedLineValueHelp: 'Recorded line value uses the original stored line prices and quantities. It is not net captured revenue and does not allocate refunds.',
    noVariants: 'No fully captured positive value orders in this period.', lifetimeValue: 'Customer lifetime value by currency', averageNet: 'AVERAGE NET',
    noLifetime: 'No successful payment history is available before the period end.', loadingReports: 'Loading reports…', administratorRequired: 'Administrator access is required to view reports.',
    loadEditionError: 'Could not load edition availability.', loadStandardError: 'Could not load standard reports.', advancedUnavailable: 'Advanced reports are currently unavailable.',
    licenseTitle: 'Artio license', loadingLicense: 'Loading license status…', edition: 'Edition', verification: 'Verification', issuerConfigured: 'Issuer refresh configured', localImportOnly: 'Local import only', installation: 'Installation',
    availableFeatures: 'Available features', noEnterpriseFeatures: 'None · Community capabilities remain available', validUntil: 'Valid until', graceEnds: 'Grace ends', noActiveEntitlement: 'No active Enterprise entitlement is installed.',
    refreshStatus: 'Controlled refresh', refreshResult: 'Refresh result', lastSuccess: 'Last success', lastAttempt: 'Last attempt', refreshIssuer: 'Refresh from issuer', refreshing: 'Refreshing…',
    noIssuerConfigured: 'No issuer URL is configured. Import a signed license document below.', ownerAction: 'OWNER ACTION', installEntitlement: 'Install signed entitlement',
    installExplanation: 'Select the signed JSON envelope provided for this installation. Artio verifies it against the configured public trust keys. The file and its contents are cleared after installation.',
    signedFile: 'Signed entitlement JSON', verifying: 'Verifying…', verifyInstall: 'Verify and install', refreshStatusButton: 'Refresh status',
    licenseLoadError: 'Could not read license status.', fileTooLarge: 'The signed license file exceeds 64 KiB.', fileReadError: 'Could not read the signed license file as text.',
    fileSelectedReadError: 'Could not read the selected license file.', rejectedLicense: 'The signed license was rejected. Existing license status is unchanged.', refreshError: 'Could not request license refresh.',
    communityLicenseHelp: 'Community keeps commerce, plugin management and existing customer obligations available. Loyalty, subscriptions and advanced reports need their matching Enterprise feature.',
    featureLoyalty: 'Loyalty', featureSubscriptions: 'Subscriptions', featureAdvancedReporting: 'Advanced reports'
  },
  de: {
    language: 'Sprache', reportsEyebrow: 'BETRIEBSBERICHTE', reportsTitle: 'Shop-Leistung', period: 'Zeitraum', periodLast: 'Letzte {days} Tage',
    kafkaConsumerHealth: 'Zustand des Consumers', kafkaMessagesBehind: 'Nachrichten Rückstand', kafkaPartition: 'PARTITION', kafkaCommitted: 'BESTÄTIGT', kafkaEnd: 'ENDE', kafkaLag: 'RÜCKSTAND', kafkaLagUnavailable: 'Consumer-Lag nicht verfügbar; Kafka-Broker und Topic prüfen.',
    reportActivityHelp: 'Bestellaktivität richtet sich nach dem Erstellungsdatum. Jeder Geldbetrag bleibt in seiner Währung.', ordersCreated: 'Erstellte Bestellungen', selectedPeriod: 'Gewählter Zeitraum', customersActive: 'Aktive Kunden', withOrder: 'Mit einer Bestellung in diesem Zeitraum',
    capturedRefunded: 'Eingezogen und erstattet nach Währung', currency: 'WÄHRUNG', orders: 'BESTELLUNGEN', customers: 'KUNDEN', captured: 'EINGEZOGEN', refunded: 'ERSTATTET', netCaptured: 'NETTO EINGEZOGEN',
    noOrders: 'In diesem Zeitraum wurden keine Bestellungen erstellt.', orderStatus: 'Bestellstatus', status: 'STATUS', noActivity: 'Keine Bestellaktivität vorhanden.', enterprise: 'ENTERPRISE', advancedReports: 'Erweiterte Berichte', advancedAvailable: 'Verfügbar', enterpriseRequired: 'Enterprise-Lizenz erforderlich',
    advancedExplanation: 'Die Rangliste umfasst vollständig bezahlte Bestellungen des Zeitraums. Kundenlebenswerte umfassen erfolgreiche Zahlungen vor dessen Ende.', advancedRequiredText: 'Erweiterte Berichte benötigen die Berechtigung ADVANCED_REPORTING. Standardberichte bleiben verfügbar.',
    topVariants: 'Top-Varianten nach Währung', historicalProduct: 'Historisches Produkt', rank: 'RANG', productVariant: 'PRODUKT / VARIANTE', sku: 'SKU', units: 'MENGE', recordedLineValue: 'ERFASSTER POSITIONSWERT', recordedLineValueHelp: 'Der erfasste Positionswert basiert auf den gespeicherten ursprünglichen Einzelpreisen und Mengen. Er entspricht weder dem Nettozahlungseingang noch einer Erstattungszuordnung.',
    noVariants: 'Keine vollständig bezahlten Bestellungen mit positivem Wert in diesem Zeitraum.', lifetimeValue: 'Kundenlebenswert nach Währung', averageNet: 'NETTO-DURCHSCHNITT', noLifetime: 'Vor Ende des Zeitraums liegen keine erfolgreichen Zahlungen vor.', loadingReports: 'Berichte werden geladen…', administratorRequired: 'Zum Anzeigen der Berichte ist Administratorzugriff erforderlich.',
    loadEditionError: 'Editionsstatus konnte nicht geladen werden.', loadStandardError: 'Standardberichte konnten nicht geladen werden.', advancedUnavailable: 'Erweiterte Berichte sind derzeit nicht verfügbar.', licenseTitle: 'Artio-Lizenz', loadingLicense: 'Lizenzstatus wird geladen…', edition: 'Edition', verification: 'Prüfung', issuerConfigured: 'Issuer-Aktualisierung konfiguriert', localImportOnly: 'Nur lokaler Import', installation: 'Installation',
    availableFeatures: 'Verfügbare Funktionen', noEnterpriseFeatures: 'Keine · Community-Funktionen bleiben verfügbar', validUntil: 'Gültig bis', graceEnds: 'Kulanzzeit endet', noActiveEntitlement: 'Keine aktive Enterprise-Berechtigung installiert.', refreshStatus: 'Kontrollierte Aktualisierung', refreshResult: 'Aktualisierungsergebnis', lastSuccess: 'Letzter Erfolg', lastAttempt: 'Letzter Versuch', refreshIssuer: 'Beim Issuer aktualisieren', refreshing: 'Wird aktualisiert…',
    noIssuerConfigured: 'Keine Issuer-URL konfiguriert. Importieren Sie unten ein signiertes Lizenzdokument.', ownerAction: 'EIGENTÜMERAKTION', installEntitlement: 'Signierte Berechtigung installieren', installExplanation: 'Wählen Sie das signierte JSON-Dokument für diese Installation. Artio prüft es mit den konfigurierten öffentlichen Vertrauensschlüsseln. Datei und Inhalt werden nach dem Installieren gelöscht.',
    signedFile: 'Signiertes Lizenz-JSON', verifying: 'Wird geprüft…', verifyInstall: 'Prüfen und installieren', refreshStatusButton: 'Status aktualisieren', licenseLoadError: 'Lizenzstatus konnte nicht gelesen werden.', fileTooLarge: 'Die Lizenzdatei ist größer als 64 KiB.', fileReadError: 'Die Lizenzdatei konnte nicht als Text gelesen werden.', fileSelectedReadError: 'Die ausgewählte Lizenzdatei konnte nicht gelesen werden.', rejectedLicense: 'Die signierte Lizenz wurde abgelehnt. Der bisherige Status bleibt unverändert.', refreshError: 'Lizenzaktualisierung konnte nicht angefordert werden.',
    communityLicenseHelp: 'Community hält Handel, Plugin-Verwaltung und bestehende Kundenverpflichtungen verfügbar. Treueprogramm, Abonnements und erweiterte Berichte benötigen die jeweilige Enterprise-Funktion.', featureLoyalty: 'Treueprogramm', featureSubscriptions: 'Abonnements', featureAdvancedReporting: 'Erweiterte Berichte'
  },
  it: {
    language: 'Lingua', reportsEyebrow: 'REPORT OPERATIVI', reportsTitle: 'Andamento del negozio', period: 'Periodo', periodLast: 'Ultimi {days} giorni',
    kafkaConsumerHealth: 'Stato del consumer', kafkaMessagesBehind: 'messaggi in ritardo', kafkaPartition: 'PARTIZIONE', kafkaCommitted: 'CONFERMATO', kafkaEnd: 'FINE', kafkaLag: 'RITARDO', kafkaLagUnavailable: 'Lag del consumer non disponibile; verificare il broker Kafka e il topic.',
    reportActivityHelp: 'L’attività degli ordini segue la data di creazione. Ogni importo resta nella valuta registrata.', ordersCreated: 'Ordini creati', selectedPeriod: 'Periodo selezionato', customersActive: 'Clienti attivi', withOrder: 'Con un ordine nel periodo',
    capturedRefunded: 'Incassato e rimborsato per valuta', currency: 'VALUTA', orders: 'ORDINI', customers: 'CLIENTI', captured: 'INCASSATO', refunded: 'RIMBORSATO', netCaptured: 'INCASSATO NETTO',
    noOrders: 'Nessun ordine creato in questo periodo.', orderStatus: 'Stato degli ordini', status: 'STATO', noActivity: 'Nessuna attività degli ordini.', enterprise: 'ENTERPRISE', advancedReports: 'Report avanzati', advancedAvailable: 'Disponibile', enterpriseRequired: 'È richiesta una licenza Enterprise',
    advancedExplanation: 'La classifica include gli ordini saldati nel periodo. Il valore complessivo dei clienti include i pagamenti riusciti prima della fine del periodo.', advancedRequiredText: 'I report avanzati richiedono l’autorizzazione ADVANCED_REPORTING. I report standard restano disponibili.',
    topVariants: 'Varianti principali per valuta', historicalProduct: 'Prodotto storico', rank: 'POSIZIONE', productVariant: 'PRODOTTO / VARIANTE', sku: 'SKU', units: 'UNITÀ', recordedLineValue: 'VALORE REGISTRATO DELLE RIGHE', recordedLineValueHelp: 'Il valore registrato usa prezzi unitari e quantità originali salvati. Non rappresenta l’incasso netto e non ripartisce i rimborsi.',
    noVariants: 'Nessun ordine completamente pagato con valore positivo nel periodo.', lifetimeValue: 'Valore complessivo dei clienti per valuta', averageNet: 'MEDIA NETTA', noLifetime: 'Nessun pagamento riuscito prima della fine del periodo.', loadingReports: 'Caricamento dei report…', administratorRequired: 'Per consultare i report è necessario l’accesso amministrativo.',
    loadEditionError: 'Impossibile caricare la disponibilità della licenza.', loadStandardError: 'Impossibile caricare i report standard.', advancedUnavailable: 'I report avanzati non sono al momento disponibili.', licenseTitle: 'Licenza Artio', loadingLicense: 'Caricamento dello stato della licenza…', edition: 'Edizione', verification: 'Verifica', issuerConfigured: 'Aggiornamento issuer configurato', localImportOnly: 'Solo importazione locale', installation: 'Installazione',
    availableFeatures: 'Funzionalità disponibili', noEnterpriseFeatures: 'Nessuna · Le funzioni Community restano disponibili', validUntil: 'Valida fino al', graceEnds: 'Il periodo di tolleranza termina il', noActiveEntitlement: 'Nessuna licenza Enterprise attiva installata.', refreshStatus: 'Aggiornamento controllato', refreshResult: 'Risultato aggiornamento', lastSuccess: 'Ultimo successo', lastAttempt: 'Ultimo tentativo', refreshIssuer: 'Aggiorna dall’issuer', refreshing: 'Aggiornamento…',
    noIssuerConfigured: 'URL issuer non configurato. Importa qui sotto un documento di licenza firmato.', ownerAction: 'AZIONE PROPRIETARIO', installEntitlement: 'Installa la licenza firmata', installExplanation: 'Seleziona il documento JSON firmato per questa installazione. Artio lo verifica con le chiavi pubbliche attendibili configurate. Il file e il contenuto vengono cancellati dopo l’installazione.',
    signedFile: 'JSON della licenza firmata', verifying: 'Verifica…', verifyInstall: 'Verifica e installa', refreshStatusButton: 'Aggiorna stato', licenseLoadError: 'Impossibile leggere lo stato della licenza.', fileTooLarge: 'Il file della licenza supera 64 KiB.', fileReadError: 'Impossibile leggere il file della licenza come testo.', fileSelectedReadError: 'Impossibile leggere il file selezionato.', rejectedLicense: 'La licenza firmata è stata rifiutata. Lo stato attuale non è cambiato.', refreshError: 'Impossibile richiedere l’aggiornamento della licenza.',
    communityLicenseHelp: 'Community mantiene disponibili commercio, gestione dei plugin e obblighi verso i clienti esistenti. Loyalty, abbonamenti e report avanzati richiedono la rispettiva funzione Enterprise.', featureLoyalty: 'Loyalty', featureSubscriptions: 'Abbonamenti', featureAdvancedReporting: 'Report avanzati'
  },
  fr: {
    language: 'Langue', reportsEyebrow: 'RAPPORTS OPÉRATIONNELS', reportsTitle: 'Performance de la boutique', period: 'Période', periodLast: '{days} derniers jours',
    kafkaConsumerHealth: 'État du consommateur', kafkaMessagesBehind: 'messages de retard', kafkaPartition: 'PARTITION', kafkaCommitted: 'VALIDÉ', kafkaEnd: 'FIN', kafkaLag: 'RETARD', kafkaLagUnavailable: 'Lag du consommateur indisponible ; vérifier le broker Kafka et le topic.',
    reportActivityHelp: 'L’activité suit la date de création de la commande. Chaque montant conserve sa devise enregistrée.', ordersCreated: 'Commandes créées', selectedPeriod: 'Période choisie', customersActive: 'Clients actifs', withOrder: 'Avec une commande pendant la période',
    capturedRefunded: 'Encaissé et remboursé par devise', currency: 'DEVISE', orders: 'COMMANDES', customers: 'CLIENTS', captured: 'ENCAISSÉ', refunded: 'REMBOURSÉ', netCaptured: 'ENCAISSÉ NET',
    noOrders: 'Aucune commande créée pendant cette période.', orderStatus: 'État des commandes', status: 'ÉTAT', noActivity: 'Aucune activité de commande à afficher.', enterprise: 'ENTERPRISE', advancedReports: 'Rapports avancés', advancedAvailable: 'Disponible', enterpriseRequired: 'Licence Enterprise requise',
    advancedExplanation: 'Le classement porte sur les commandes entièrement payées de la période. La valeur vie client comprend les paiements réussis avant sa fin.', advancedRequiredText: 'Les rapports avancés nécessitent le droit ADVANCED_REPORTING. Les rapports standards restent disponibles.',
    topVariants: 'Meilleures variantes par devise', historicalProduct: 'Produit historique', rank: 'RANG', productVariant: 'PRODUIT / VARIANTE', sku: 'SKU', units: 'UNITÉS', recordedLineValue: 'VALEUR DE LIGNE ENREGISTRÉE', recordedLineValueHelp: 'La valeur de ligne utilise les prix unitaires et quantités d’origine enregistrés. Elle ne représente pas l’encaissement net et ne répartit pas les remboursements.',
    noVariants: 'Aucune commande entièrement payée de valeur positive pendant cette période.', lifetimeValue: 'Valeur vie client par devise', averageNet: 'MOYENNE NETTE', noLifetime: 'Aucun paiement réussi avant la fin de la période.', loadingReports: 'Chargement des rapports…', administratorRequired: 'Un accès administrateur est requis pour consulter les rapports.',
    loadEditionError: 'Impossible de charger la disponibilité de licence.', loadStandardError: 'Impossible de charger les rapports standards.', advancedUnavailable: 'Les rapports avancés sont actuellement indisponibles.', licenseTitle: 'Licence Artio', loadingLicense: 'Chargement du statut de licence…', edition: 'Édition', verification: 'Vérification', issuerConfigured: 'Actualisation issuer configurée', localImportOnly: 'Import local uniquement', installation: 'Installation',
    availableFeatures: 'Fonctionnalités disponibles', noEnterpriseFeatures: 'Aucune · Les fonctionnalités Community restent disponibles', validUntil: 'Valide jusqu’au', graceEnds: 'La période de grâce se termine le', noActiveEntitlement: 'Aucun droit Enterprise actif installé.', refreshStatus: 'Actualisation contrôlée', refreshResult: 'Résultat de l’actualisation', lastSuccess: 'Dernière réussite', lastAttempt: 'Dernière tentative', refreshIssuer: 'Actualiser auprès de l’issuer', refreshing: 'Actualisation…',
    noIssuerConfigured: 'Aucune URL issuer configurée. Importez ci-dessous un document de licence signé.', ownerAction: 'ACTION PROPRIÉTAIRE', installEntitlement: 'Installer un droit signé', installExplanation: 'Sélectionnez le document JSON signé fourni pour cette installation. Artio le vérifie avec les clés publiques de confiance configurées. Le fichier et son contenu sont effacés après l’installation.',
    signedFile: 'JSON de licence signé', verifying: 'Vérification…', verifyInstall: 'Vérifier et installer', refreshStatusButton: 'Actualiser le statut', licenseLoadError: 'Impossible de lire le statut de licence.', fileTooLarge: 'Le fichier de licence dépasse 64 Kio.', fileReadError: 'Impossible de lire le fichier de licence comme texte.', fileSelectedReadError: 'Impossible de lire le fichier sélectionné.', rejectedLicense: 'La licence signée a été refusée. Le statut existant est conservé.', refreshError: 'Impossible de demander l’actualisation de licence.',
    communityLicenseHelp: 'Community conserve le commerce, la gestion des plugins et les obligations client existantes. La fidélité, les abonnements et les rapports avancés nécessitent leur fonctionnalité Enterprise correspondante.', featureLoyalty: 'Fidélité', featureSubscriptions: 'Abonnements', featureAdvancedReporting: 'Rapports avancés'
  }
} as const;

export type BackofficeTextKey = keyof typeof COPY.en;

export function currentBackofficeLocale(): BackofficeLocale {
  try {
    const saved = localStorage.getItem('artio-backoffice-language');
    if (saved === 'de' || saved === 'it' || saved === 'fr' || saved === 'en') return saved;
  } catch { /* Browser storage may be disabled; use the browser's language. */ }
  const languages = typeof navigator === 'undefined' ? [] : [...(navigator.languages ?? []), navigator.language];
  for (const language of languages) {
    const match = language.toLowerCase().split('-')[0];
    if (match === 'de' || match === 'it' || match === 'fr' || match === 'en') return match;
  }
  return 'en';
}

export function setBackofficeLocale(locale: BackofficeLocale): void {
  try { localStorage.setItem('artio-backoffice-language', locale); }
  catch { /* A selected language still applies for this component session. */ }
}

export function backofficeText(locale: BackofficeLocale, key: BackofficeTextKey): string {
  return COPY[locale][key] ?? COPY.en[key];
}
