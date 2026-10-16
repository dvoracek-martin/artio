import { describe, expect, it } from 'vitest';
import { DataTransferAdminComponent } from './data-transfer-admin.component';
import { dataTransferText } from './data-transfer-admin-locale';

describe('DataTransferAdminComponent', () => {
  it('offers product transfer and explains the current CSV scope', () => {
    expect(dataTransferText('en', 'import')).toBe('Import products');
    expect(dataTransferText('en', 'export')).toBe('Export products');
    expect(dataTransferText('en', 'noOtherTypes')).toContain('products only');
  });

  it('emits export and dismiss actions', () => {
    const component = new DataTransferAdminComponent();
    const actions: string[] = [];
    component.action.subscribe(action => actions.push(action.type));
    component.emit({ type: 'export' });
    component.emit({ type: 'dismissReport' });

    expect(actions).toEqual(['export', 'dismissReport']);
  });
});
