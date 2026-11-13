import { describe, expect, it } from 'vitest';
import { storefrontLocales } from '../locale';
import { studioExtensionCopy } from './studioExtensionCopy';
import { personalizationCopy } from './personalizationCopy';

describe('studio extension copy', () => {
  it('provides complete, non-empty copy for each storefront language', () => {
    expect(Object.keys(studioExtensionCopy).sort()).toEqual([...storefrontLocales].sort());
    const keys = Object.keys(studioExtensionCopy.en).sort();
    for (const locale of storefrontLocales) {
      const copy = studioExtensionCopy[locale];
      expect(Object.keys(copy).sort(), locale).toEqual(keys);
      for (const [key, value] of Object.entries(copy)) {
        expect(value.trim(), `${locale}.${key}`).not.toBe('');
        expect(value, `${locale}.${key}`).toBe(value.trim());
        expect(value, `${locale}.${key}`).not.toMatch(/undefined|\{\{.*\}\}/u);
      }
    }
  });

  it('keeps tools, examples and the six built-in font labels distinct', () => {
    for (const locale of storefrontLocales) {
      const copy = studioExtensionCopy[locale];
      expect(new Set([copy.textTab, copy.imageTab, copy.aiTab]).size, locale).toBe(3);
      expect(new Set([copy.patternBotanical, copy.patternForest, copy.patternOrbit]).size, locale).toBe(3);
      expect(new Set([
        copy.fontModern, copy.fontClassic, copy.fontTypewriter,
        copy.fontRounded, copy.fontEditorial, copy.fontHandwritten,
      ]).size, locale).toBe(6);
      expect(copy.loopName, locale).toBe('Loop');
      expect(copy.lampName, locale).toBe('Luma');
    }
  });

  it('distinguishes a replaced picture from a newly added layer in every language', () => {
    for (const locale of storefrontLocales) {
      const copy = studioExtensionCopy[locale];
      expect(copy.imageReplaced.trim(), locale).not.toBe('');
      expect(copy.imageReplaced, locale).not.toBe(copy.imageLoaded);
      if (locale !== 'en') expect(copy.imageReplaced, locale).not.toBe(studioExtensionCopy.en.imageReplaced);
    }
  });

  it('communicates the same accepted formats and file limits in all languages', () => {
    for (const locale of storefrontLocales) {
      const copy = studioExtensionCopy[locale];
      for (const format of ['PNG', 'JPEG', 'WebP']) {
        expect(copy.imageFormats, locale).toContain(format);
        expect(copy.imageType, locale).toContain(format);
      }
      expect(copy.imageFormats, locale).toMatch(/5/u);
      expect(copy.imageFormats, locale).toMatch(/16/u);
      expect(copy.imageTooLarge, locale).toMatch(/5/u);
      expect(copy.imageDimensions, locale).toMatch(/16.*8192/u);
      expect(copy.imageEmptyFile, locale).not.toBe(copy.imageError);
      expect(copy.imageChooseAgain, locale).not.toBe(copy.imageError);
      for (const format of ['TTF', 'OTF', 'WOFF2']) {
        expect(copy.fontFormats, locale).toContain(format);
        expect(copy.fontInvalid, locale).toContain(format);
      }
      expect(copy.fontFormats, locale).toMatch(/2 (?:MB|МБ|Mo)/u);
      expect(copy.fontLimit, locale).toMatch(/2 (?:MB|МБ|Mo)/u);
    }
  });

  it('makes unavailable AI and illustrative manufacturing explicit without an English fallback', () => {
    const en = studioExtensionCopy.en;
    expect(en.aiUnavailable).toContain('not connected');
    expect(en.aiUnavailable).toContain('not sent');
    expect(en.examplePatternsNote).toContain('not AI-generated answers');
    expect(en.appliedBridges).toContain('strength has not been checked');
    expect(en.manufacturingBoundary).toContain('not a cutting file');
    for (const locale of storefrontLocales.filter(locale => locale !== 'en')) {
      const copy = studioExtensionCopy[locale];
      for (const key of ['aiUnavailable', 'examplePatternsNote', 'appliedBridges', 'manufacturingBoundary'] as const) {
        expect(copy[key], `${locale}.${key}`).not.toBe(en[key]);
      }
    }
  });

  it('keeps the primary actions concise while the status describes a preview update', () => {
    for (const locale of storefrontLocales) {
      const copy = studioExtensionCopy[locale], p = personalizationCopy[locale];
      expect(copy.applyArtwork, locale).toBe(p.apply);
      expect(copy.artworkApplied, locale).toBe(p.completed);
      expect(p.apply.length, locale).toBeLessThan(30);
      expect(p.applied.length, locale).toBeLessThan(30);
      expect(copy.artworkApplied.length, locale).toBeLessThan(50);
      expect(copy.lampMaterial, locale).toContain('3 mm'.replace('mm', locale === 'ru' ? 'мм' : 'mm'));
      expect(copy.lampMaterial, locale).not.toContain('·');
      expect(copy.lampMaterial, locale).not.toMatch(/example|Beispiel|пример|przykład|ejemplo|exemple|esempio/iu);
      expect(copy.aiUnavailable.length, locale).toBeLessThan(90);
    }
    expect(studioExtensionCopy.en.artworkApplied).toContain('preview');
    expect(personalizationCopy.ru.applied).toBe('Дизайн применён');
  });

  it('localises the visual preview boundary and both zone-specific limit placeholders', () => {
    const keys = Object.keys(personalizationCopy.en).sort();
    for (const locale of storefrontLocales) {
      const p = personalizationCopy[locale];
      expect(Object.keys(p).sort(), locale).toEqual(keys);
      for (const [key, text] of Object.entries(p)) {
        expect(text.trim(), `${locale}.${key}`).not.toBe('');
        expect(text, `${locale}.${key}`).toBe(text.trim());
      }
      expect(p.textLimited, locale).toContain('{characters}');
      expect(p.textLimited, locale).toContain('{lines}');
      expect(p.disclosureTitle, locale).not.toMatch(/preview|Vorschau|предпросмотр|podgląd|vista previa|aperçu|anteprima/iu);
      if (locale !== 'en') expect(p.disclosureBody, locale).not.toBe(personalizationCopy.en.disclosureBody);
    }
    expect(personalizationCopy.en.disclosureBody).toContain('2D or 3D');
    expect(personalizationCopy.en.disclosureBody).toContain('not a production file');
  });
});
