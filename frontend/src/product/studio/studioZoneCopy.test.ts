import { describe, expect, it } from 'vitest';
import { storefrontLocales } from '../locale';
import { studioProducts } from './studioProducts';
import { studioZoneCopy } from './studioZoneCopy';

describe('zone-based studio copy', () => {
  it('covers all seven languages without missing or empty labels', () => {
    expect(Object.keys(studioZoneCopy).sort()).toEqual([...storefrontLocales].sort());
    const keys = Object.keys(studioZoneCopy.en).sort();
    for (const locale of storefrontLocales) {
      const copy = studioZoneCopy[locale];
      expect(Object.keys(copy).sort(), locale).toEqual(keys);
      const labels = Object.entries(copy).flatMap(([key, value]) => typeof value === 'string'
        ? [[key, value]] : Object.entries(value).map(([nested, label]) => [`${key}.${nested}`, label]));
      for (const [key, label] of labels) {
        expect(label.trim(), `${locale}.${key}`).not.toBe('');
        expect(label, `${locale}.${key}`).toBe(label.trim());
        expect(label, `${locale}.${key}`).not.toMatch(/undefined|\{\{.*\}\}/u);
      }
    }
  });

  it('names every configured area and operation distinctly', () => {
    for (const locale of storefrontLocales) {
      const copy = studioZoneCopy[locale];
      for (const product of Object.values(studioProducts)) {
        for (const zone of product.zones) {
          expect(copy.zoneNames[zone.id], locale).toBeTruthy();
          expect(copy.zoneHints[zone.id], locale).toBeTruthy();
          expect(copy.operations[zone.operation], locale).toBeTruthy();
        }
        expect(new Set(product.zones.map(zone => copy.zoneNames[zone.id])).size, locale).toBe(product.zones.length);
      }
      expect(new Set(Object.values(copy.operations)).size, locale).toBe(3);
      expect(copy.modelView, locale).not.toBe(copy.flatView);
    }
  });

  it('keeps missing capability and useful cut constraints explicit in every language', () => {
    expect(studioZoneCopy.en.unavailable).toContain('not available');
    expect(studioZoneCopy.en.panelRestriction).toContain('prepared patterns');
    expect(studioZoneCopy.en.cutTextHint).toContain('reserved band');
    expect(studioZoneCopy.en.cutFontHint).toContain('product author');
    for (const locale of storefrontLocales.filter(locale => locale !== 'en')) {
      expect(studioZoneCopy[locale].unavailable, locale).not.toBe(studioZoneCopy.en.unavailable);
      expect(studioZoneCopy[locale].panelRestriction, locale).not.toBe(studioZoneCopy.en.panelRestriction);
    }
    for (const locale of storefrontLocales) {
      const copy = studioZoneCopy[locale];
      expect(copy.cutUnsupported, locale).toContain('A–Z');
      for (const key of ['panelRestriction', 'cutTextHint', 'cutFontHint', 'cutUnsupported'] as const) {
        expect(copy[key].length, `${locale}.${key}`).toBeLessThan(100);
        expect(copy[key], `${locale}.${key}`).not.toMatch(/example|Beispiel|пример|przykład|ejemplo|exemple|esempio/iu);
      }
    }
  });
});
