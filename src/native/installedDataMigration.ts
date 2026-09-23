import { registerPlugin } from '@capacitor/core';

export interface NativeLegacyAsset {
  assetId: string;
  category: string;
  relativePath: string;
  byteLength: number;
  sha256: string;
}

export interface NativeLegacySnapshot {
  schemaVersion: 1;
  environment: { applicationId: string; displayDensity: number };
  database: {
    present: boolean;
    version?: number;
    identityHash?: string;
    tables: Record<string, unknown[]>;
  };
  preferences: Record<string, {
    present: boolean;
    entries: Record<string, { type: string; value: unknown }>;
  }>;
  encryptedPreferences: Record<string, {
    present: boolean;
    status: 'absent' | 'decrypted-native-retained' | 'unavailable';
    keys: string[];
    error?: string;
  }>;
  assets: NativeLegacyAsset[];
  nativeFiles: Array<{
    category: string;
    relativePath: string;
    byteLength: number;
    lastModified: number;
    keyId?: number;
    sha256?: string;
  }>;
}

export interface NativeAssetChunk {
  assetId: string;
  offset: number;
  byteLength: number;
  base64: string;
}

export interface NativeInstalledDataMigrationPlugin {
  readLegacyInstalledData(): Promise<NativeLegacySnapshot>;
  readAssetChunk(options: {
    assetId: string;
    offset: number;
    maxBytes: number;
  }): Promise<NativeAssetChunk>;
}

export const installedDataMigrationNative = registerPlugin<NativeInstalledDataMigrationPlugin>(
  'InstalledDataMigration',
);
