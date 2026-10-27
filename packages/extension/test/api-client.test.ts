import { describe, expect, it } from 'vitest';
import { signUp } from '../src/account.ts';
import { ApiClient, ApiError } from '../src/api.ts';

const unreachable = async () => {
  throw new TypeError('Failed to fetch');
};

describe('ApiClient when the server is unreachable', () => {
  it('explains the problem instead of "Failed to fetch"', async () => {
    const err = await new ApiClient('http://127.0.0.1:8787', null, unreachable).health().catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.message).toMatch(/Can't reach the OmniSync server at http:\/\/127\.0\.0\.1:8787/);
  });

  it('sign-up fails fast, before deriving keys', async () => {
    const t0 = Date.now();
    await expect(
      signUp({ serverUrl: 'http://127.0.0.1:8787', username: 'me@example.com', password: 'a long master password', deviceName: 'x' }, { fetch: unreachable }),
    ).rejects.toThrow(/Can't reach the OmniSync server/);
    expect(Date.now() - t0).toBeLessThan(300); // production Argon2id would take ~1 s
  });

  it('passes other errors (e.g. a long-poll timeout abort) through unchanged', async () => {
    const abort = async () => {
      throw new DOMException('timed out', 'TimeoutError');
    };
    await expect(new ApiClient('http://x', null, abort).health()).rejects.toThrow('timed out');
  });
});
