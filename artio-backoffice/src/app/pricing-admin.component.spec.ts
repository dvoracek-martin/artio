import { describe, expect, it } from 'vitest';
import { PricingAdminComponent } from './pricing-admin.component';

describe('PricingAdminComponent', () => {
  it('shows country names for market codes and prepopulates tax editing fields', () => {
    const component = new PricingAdminComponent();
    component.countryOptions = [
      { value: 'CH', label: 'Switzerland' },
      { value: 'DE', label: 'Germany' }
    ];
    expect(component.countryName('CH')).toBe('Switzerland');

    component.beginTaxEdit({
      id: 'tax-1', jurisdiction: 'CH', category: 'REDUCED', percentage: 2.6,
      validFrom: '2024-01-01T00:00:00Z', validUntil: null
    });

    expect(component.taxEditorOpen).toBe(true);
    expect(component.editingTaxRuleId).toBe('tax-1');
    expect(component.taxDraft).toEqual({
      jurisdiction: 'CH', category: 'REDUCED', percentage: 2.6,
      validFrom: '2024-01-01', validUntil: ''
    });
  });

  it('emits tax rule changes with current form values', () => {
    const component = new PricingAdminComponent();
    const actions: unknown[] = [];
    component.action.subscribe(action => actions.push(action));
    component.beginTaxCreate();
    component.taxDraft = {
      jurisdiction: 'CH', category: 'STANDARD', percentage: 8.1,
      validFrom: '2024-01-01', validUntil: ''
    };
    component.saveTaxRule();

    expect(actions).toEqual([{
      type: 'saveTaxRule',
      draft: { jurisdiction: 'CH', category: 'STANDARD', percentage: 8.1, validFrom: '2024-01-01', validUntil: '' }
    }]);
    expect(component.taxEditorOpen).toBe(false);
  });
});
