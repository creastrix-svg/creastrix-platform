import { render, screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import ProductPrice from './ProductPrice';
import { sampleDiscount } from './catalog';
import { productCopy } from './copy';
import { storefrontLocales } from './locale';

it.each(['de', 'en'] as const)('formats four digits and two decimals with a derived comparison in %s', language => {
  const { container } = render(<ProductPrice cents={123_456} previousCents={135_789} language={language} t={productCopy[language]} />);
  expect(container.querySelector('.product-price')).toHaveTextContent(language === 'de' ? '1.234,56 €' : '€1,234.56');
  expect(container.querySelector('del')).toHaveTextContent(language === 'de' ? '1.357,89 €' : '€1,357.89');
  expect(container.querySelector('.product-price-badge')).toHaveTextContent('−9%');
});
it.each([undefined, 0, -2400, 2300, 2400, NaN, Infinity, 2400.5, Number.MAX_SAFE_INTEGER + 1, '3000'])('does not invent a comparison price or sale badge (%s)', previousCents => {
  const { container } = render(<ProductPrice cents={2400} previousCents={previousCents as number} language="en" t={productCopy.en} />);
  expect(container.querySelector('del')).toBeNull();
  expect(container.querySelector('.product-price-badge')).toBeNull();
});
it.each([-1, NaN, Infinity, 1990.5, Number.MAX_SAFE_INTEGER + 1])('rejects malformed current cents without advertising a discount (%s)', cents => {
  const { container } = render(<ProductPrice cents={cents} previousCents={2400} language="en" t={productCopy.en} />);
  expect(screen.getByText(productCopy.en.priceUnavailable)).toBeVisible();
  expect(container.querySelector('del')).toBeNull(); expect(container.querySelector('.product-price-badge')).toBeNull();
});
it.each([[1990, 2400, 17], [750, 1000, 25], [995, 1000, 1], [996, 1000, null], [0, 2400, 100]])('rounds a checked comparison %i/%i to %s', (cents, previous, expected) => {
  expect(sampleDiscount(cents, previous)).toBe(expected);
});
it.each(storefrontLocales)('announces current, previous and sample discount in %s without relying only on red', locale => {
  const t = productCopy[locale], { container } = render(<ProductPrice cents={1990} previousCents={2400} language={locale} t={t} />);
  expect(screen.getByText(t.currentPrice + ':')).toBeInTheDocument();
  expect(screen.getByText(t.previousPrice + ':')).toBeInTheDocument();
  expect(screen.getByText(t.sampleDiscount + ':')).toBeInTheDocument();
  expect(container.querySelector('.product-price')).toHaveClass('product-price-sale');
  expect(container.querySelector('.product-price-badge')).toHaveTextContent('−17%');
});
it('removes comparison markup on sale-to-normal change but keeps its reserved row', () => {
  const { container, rerender } = render(<ProductPrice cents={1990} previousCents={2400} language="en" t={productCopy.en} />);
  rerender(<ProductPrice cents={2600} language="en" t={productCopy.en} />);
  expect(container.querySelector('.product-price')).not.toHaveClass('product-price-sale');
  expect(container.querySelector('del')).toBeNull(); expect(container.querySelector('.product-price-badge')).toBeNull();
  expect(container.querySelector('.product-price-offer')).toBeEmptyDOMElement();
});
