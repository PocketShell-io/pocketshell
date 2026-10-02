/** Small byte helpers shared by the browser dev-mode plugins (#3022). */

export function bytesToBase64(bytes: Uint8Array): string {
  let binary = '';
  const chunk = 0x8000;
  for (let index = 0; index < bytes.length; index += chunk) {
    binary += String.fromCharCode(...bytes.subarray(index, index + chunk));
  }
  return btoa(binary);
}

export function base64ToBytes(base64: string): Uint8Array {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

const encoder = new TextEncoder();
const decoder = new TextDecoder();

export function textToBase64(text: string): string {
  return bytesToBase64(encoder.encode(text));
}

export function base64ToText(base64: string): string {
  return decoder.decode(base64ToBytes(base64));
}

export function randomId(): string {
  return globalThis.crypto.randomUUID();
}
