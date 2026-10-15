import { expect, it } from 'vitest';
import { searchExamples } from './search';

it('ranks exact/prefix names first and returns at most five deterministic suggestions', () => {
  expect(searchExamples('  LOOP MINI ', 'en')[0].id).toBe('loop-mini');
  const results = searchExamples('Loop', 'en');
  expect(results).toHaveLength(5);
  expect(results[0].id).toBe('loop');
  expect(searchExamples('Loop S', 'en')[0].id).toBe('loop-stand');
});
it('matches the current DE/EN descriptions, including diacritics, not only product names', () => {
  expect(searchExamples('plant pot', 'en').map(item => item.id)).toContain('loop');
  expect(searchExamples('Pflanztopf', 'de').map(item => item.id)).toContain('loop');
  expect(searchExamples('pflanzenstander', 'de')[0].id).toBe('loop-stand');
  expect(searchExamples('Untersetzer', 'de')[0].id).toBe('loop-tray');
  expect(searchExamples('Untersetzer', 'en')).toHaveLength(0);
});
it('has explicit empty/no-result outcomes and bounds even a caller-supplied limit', () => {
  expect(searchExamples('nothing-matches', 'en')).toHaveLength(0);
  expect(searchExamples('', 'en', 1000)).toHaveLength(7);
  expect(searchExamples('Loop', 'en', 0)).toHaveLength(0);
});
