import * as oc from '@omnisync/crypto';
import { ApiClient, type Fetch } from './api.ts';

export interface AccountConfig {
  serverUrl: string;
  /** Needed locally to re-derive keys on unlock (it's the Argon2id salt input). Never sent to the server. */
  username: string;
  loginId: string;
  token: string;
  deviceId: string;
  wrappedVaultKey: string;
  kdf: oc.KdfParams;
}

export interface Credentials {
  serverUrl: string;
  username: string;
  password: string;
  deviceName: string;
}

export interface AuthOptions {
  fetch?: Fetch;
  /** Tests only. Production always uses KDF_PARAMS_V1. */
  kdf?: oc.KdfParams;
  /** Keep the device id across re-logins on the same browser. */
  previousDeviceId?: string;
}

function derive(c: Credentials, kdf: oc.KdfParams) {
  return oc.deriveMasterKeys(c.password, c.username, kdf, { unsafeAllowWeakParams: kdf !== oc.KDF_PARAMS_V1 });
}

export async function signIn(c: Credentials, opts: AuthOptions = {}): Promise<{ config: AccountConfig; vaultKey: Uint8Array }> {
  await oc.ready();
  const kdf = opts.kdf ?? oc.KDF_PARAMS_V1;
  const api = new ApiClient(c.serverUrl, null, opts.fetch);
  const loginId = oc.deriveLoginId(c.username);
  const keys = derive(c, kdf);
  try {
    const session = await api.login({ loginId, authKey: oc.toB64(keys.authKey) });
    // Unwrapping proves the password client-side too: a malicious server can't hand us a key of its choosing.
    const vaultKey = oc.unwrapVaultKey(session.wrappedVaultKey, keys.kek);
    api.setToken(session.token);
    const nameCiphertext = oc.encryptRecord(vaultKey, { collection: 'device', id: 'name', modifiedAt: 0, deviceId: 'self' }, c.deviceName);
    const { deviceId } = await api.registerDevice({ nameCiphertext, ...(opts.previousDeviceId && { deviceId: opts.previousDeviceId }) });
    return {
      vaultKey,
      config: {
        serverUrl: c.serverUrl,
        username: oc.normalizeUsername(c.username),
        loginId,
        token: session.token,
        deviceId,
        wrappedVaultKey: session.wrappedVaultKey,
        kdf,
      },
    };
  } finally {
    oc.wipe(keys.authKey);
    oc.wipe(keys.kek);
  }
}

export async function signUp(c: Credentials, opts: AuthOptions = {}) {
  await oc.ready();
  const kdf = opts.kdf ?? oc.KDF_PARAMS_V1;
  const api = new ApiClient(c.serverUrl, null, opts.fetch);
  const keys = derive(c, kdf);
  const vaultKey = oc.generateVaultKey();
  try {
    await api.register({
      loginId: oc.deriveLoginId(c.username),
      authKey: oc.toB64(keys.authKey),
      kdf: oc.KDF_PARAMS_V1,
      wrappedVaultKey: oc.wrapVaultKey(vaultKey, keys.kek),
    });
  } finally {
    oc.wipe(keys.authKey);
    oc.wipe(keys.kek);
    oc.wipe(vaultKey);
  }
  return signIn(c, opts);
}

/** Offline unlock after a browser restart: re-derive the KEK and unwrap the stored vault key. */
export async function unlock(config: AccountConfig, password: string): Promise<Uint8Array> {
  await oc.ready();
  const keys = oc.deriveMasterKeys(password, config.username, config.kdf, {
    unsafeAllowWeakParams: config.kdf.memLimitBytes < oc.KDF_PARAMS_V1.memLimitBytes,
  });
  try {
    return oc.unwrapVaultKey(config.wrappedVaultKey, keys.kek);
  } catch {
    throw new Error('Wrong master password');
  } finally {
    oc.wipe(keys.authKey);
    oc.wipe(keys.kek);
  }
}
