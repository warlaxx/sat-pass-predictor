import { afterEach, describe, expect, it, vi } from 'vitest';
import { countUsage } from './usage';

describe('countUsage', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('sends an empty beacon naming the action, and nothing else', () => {
    const sendBeacon = vi.fn(() => true);
    vi.stubGlobal('navigator', { sendBeacon });

    countUsage('event-open-pass');

    expect(sendBeacon).toHaveBeenCalledExactlyOnceWith('/api/usage/event-open-pass');
  });

  it('counts nothing where there is no beacon', () => {
    vi.stubGlobal('navigator', {});
    expect(() => countUsage('list-open-event')).not.toThrow();
  });

  it('swallows a beacon that throws', () => {
    vi.stubGlobal('navigator', { sendBeacon: () => { throw new TypeError('blocked'); } });
    expect(() => countUsage('list-show-table')).not.toThrow();
  });
});
