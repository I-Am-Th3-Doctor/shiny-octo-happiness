import { describe, expect, it } from 'vitest';
import { compareVersions } from '../src/index.ts';

describe('compareVersions (last-write-wins)', () => {
  it('later timestamp wins regardless of device', () => {
    expect(compareVersions({ modifiedAt: 2, deviceId: 'a' }, { modifiedAt: 1, deviceId: 'z' })).toBeGreaterThan(0);
    expect(compareVersions({ modifiedAt: 1, deviceId: 'z' }, { modifiedAt: 2, deviceId: 'a' })).toBeLessThan(0);
  });
  it('breaks exact ties by device id deterministically on every replica', () => {
    const a = { modifiedAt: 5, deviceId: 'dev-a' };
    const b = { modifiedAt: 5, deviceId: 'dev-b' };
    expect(compareVersions(b, a)).toBeGreaterThan(0);
    expect(compareVersions(a, b)).toBeLessThan(0);
  });
  it('identical versions compare equal (idempotent retries)', () => {
    expect(compareVersions({ modifiedAt: 5, deviceId: 'x' }, { modifiedAt: 5, deviceId: 'x' })).toBe(0);
  });
  it('is a total order: sorting any permutation yields the same winner', () => {
    const vs = [
      { modifiedAt: 3, deviceId: 'b' },
      { modifiedAt: 3, deviceId: 'a' },
      { modifiedAt: 1, deviceId: 'z' },
      { modifiedAt: 3, deviceId: 'c' },
    ];
    const winner = (xs: typeof vs) => [...xs].sort(compareVersions).at(-1);
    expect(winner(vs)).toEqual({ modifiedAt: 3, deviceId: 'c' });
    expect(winner([...vs].reverse())).toEqual({ modifiedAt: 3, deviceId: 'c' });
  });
});
