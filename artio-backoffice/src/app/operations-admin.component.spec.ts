import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { BackofficeLocale } from './backoffice-locale';
import { OperationsAdminComponent } from './operations-admin.component';

describe('OperationsAdminComponent', () => {
  beforeEach(async () => TestBed.configureTestingModule({ imports: [OperationsAdminComponent] }).compileComponents());
  afterEach(() => localStorage.removeItem('artio-backoffice-language'));

  it.each([
    ['en', 'Outbox queues', 'Pending events', 'Kafka consumer dead letters'],
    ['de', 'Outbox-Warteschlangen', 'Ausstehende Ereignisse', 'Kafka-Fehlernachrichten'],
    ['it', 'Code outbox', 'Eventi in attesa', 'Dead letter del consumer Kafka'],
    ['fr', 'Files outbox', 'Événements en attente', 'Messages Kafka en échec'],
  ] as const)('localizes queue health and dead-letter actions in %s', (locale, title, pending, kafkaDeadLetters) => {
    const fixture = TestBed.createComponent(OperationsAdminComponent);
    fixture.componentInstance.locale = locale as BackofficeLocale;
    fixture.componentInstance.loading = false;
    fixture.componentInstance.diagnostics = { eventPending: 2, eventStaleClaims: 0, eventDead: 1, emailPending: 3, emailStaleClaims: 1, emailDead: 0, webhookUnprocessed: 0, webhookOlderThanRetention: 0 };
    fixture.componentInstance.deadLetterItems = [{ id: 'event-1', eventType: 'ORDER_CREATED', sourceTopic: 'artio.order.events', partition: 0, offset: 9, errorClass: 'HANDLER_ERROR', replayAttempts: 0, status: 'DEAD' }];
    fixture.detectChanges();
    const page = fixture.nativeElement.textContent as string;
    expect(page).toContain(title);
    expect(page).toContain(pending);
    expect(page).toContain(kafkaDeadLetters);
    const actions: string[] = [];
    fixture.componentInstance.action.subscribe(action => actions.push(action.type));
    (fixture.nativeElement.querySelector('button.link-button') as HTMLButtonElement).click();
    expect(actions).toEqual(['refresh']);
  });
});
