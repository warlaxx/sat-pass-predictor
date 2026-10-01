import { describe, it, expect } from 'vitest';
import { feedbackHref } from './feedback';

describe('feedbackHref', () => {
  const repository = 'https://github.com/warlaxx/sat-pass-predictor';

  it('opens an e-mail naming the page once the operator address is published', () => {
    const href = feedbackHref('contact@example.org', repository, 'https://example.org/starlink');
    expect(href.startsWith('mailto:contact@example.org?subject=NextPass%20feedback&body=')).toBe(true);
    expect(decodeURIComponent(href.split('body=')[1])).toBe('Page: https://example.org/starlink\n\n');
  });

  it('falls back to the issues of the repository while no address is published', () => {
    expect(feedbackHref('', repository, '/')).toBe(`${repository}/issues`);
  });
});
