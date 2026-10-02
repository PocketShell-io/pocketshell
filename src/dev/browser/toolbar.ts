/**
 * Browser dev mode (#3022): a small floating toolbar for the things a phone
 * has and a desktop browser does not — the Android back button, the soft
 * keyboard, the share sheet — plus filling the connect form with the dev
 * host. Hide it with `?devToolbar=0`.
 */
import type { SeedHost } from './liveBridge';

export interface DevToolbarApi {
  mode: 'mock' | 'live';
  back(): number;
  keyboard(visible: boolean | null): void;
  share(text: string): number;
  dropConnections(): number;
  seedHosts: SeedHost[];
}

const EXPANDED_KEY = 'pocketshell.dev-toolbar.expanded';

function setInputValue(input: HTMLInputElement | null, value: string): void {
  if (!input) return;
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

/** Fill the legacy connect form (hostname/port/user/key) from the first dev host. */
export function fillConnectForm(doc: Document, host: SeedHost | undefined): boolean {
  const hostname = doc.querySelector<HTMLInputElement>('[data-testid="ssh-host"]');
  if (!host || !hostname) return false;
  setInputValue(hostname, host.hostname);
  setInputValue(doc.querySelector<HTMLInputElement>('[data-testid="ssh-port"]'), String(host.port));
  setInputValue(doc.querySelector<HTMLInputElement>('[data-testid="ssh-username"]'), host.user);
  const key = doc.querySelector<HTMLSelectElement>('[data-testid="ssh-key-selection"]');
  if (key && [...key.options].some((option) => option.value === host.keyHandleId)) {
    key.value = host.keyHandleId;
    key.dispatchEvent(new Event('change', { bubbles: true }));
  }
  return true;
}

export function createDevToolbar(doc: Document, api: DevToolbarApi): HTMLElement {
  const bar = doc.createElement('div');
  bar.dataset.testid = 'dev-browser-toolbar';
  bar.setAttribute('role', 'toolbar');
  bar.setAttribute('aria-label', 'Browser dev mode');
  bar.style.cssText = [
    // Right edge, a third of the way down: clear of the app header, the
    // terminal key bar and the composer.
    'position:fixed', 'right:0', 'top:33%', 'z-index:2147483647', 'display:flex', 'flex-direction:column',
    'gap:3px', 'align-items:stretch', 'padding:4px 3px', 'border-radius:8px 0 0 8px',
    'background:rgba(40,20,60,.9)', 'color:#f0e6ff', 'font:11px system-ui,sans-serif',
    'box-shadow:0 1px 4px rgba(0,0,0,.4)', 'opacity:.9',
  ].join(';');
  const label = doc.createElement('span');
  label.textContent = 'DEV';
  label.dataset.testid = 'dev-toolbar-toggle';
  label.title = `PocketShell browser dev mode (${api.mode}). SSH keys are held in memory only. Click to show the dev buttons.`;
  label.style.cssText = 'font-weight:700;cursor:pointer;padding:0 2px;text-align:center';
  bar.append(label);
  const buttons = doc.createElement('span');
  const expanded = () => globalThis.sessionStorage?.getItem(EXPANDED_KEY) !== '0';
  buttons.style.cssText = `display:${expanded() ? 'flex' : 'none'};flex-direction:column;gap:3px`;
  bar.append(buttons);
  let keyboardForced = false;
  const button = (text: string, title: string, testid: string, onClick: (target: HTMLButtonElement) => void) => {
    const element = doc.createElement('button');
    element.type = 'button';
    element.textContent = text;
    element.title = title;
    element.dataset.testid = testid;
    element.style.cssText = 'font:inherit;color:inherit;background:rgba(255,255,255,.12);border:0;border-radius:5px;padding:2px 6px;cursor:pointer';
    // Keep focus where it is (the terminal, the composer) like a hardware key.
    element.addEventListener('mousedown', (event) => event.preventDefault());
    element.addEventListener('click', () => onClick(element));
    buttons.append(element);
  };
  button('Back', 'Android back button', 'dev-back', () => api.back());
  button('Kbd', 'Force the simulated soft keyboard up/down (default: follows focus)', 'dev-keyboard', (target) => {
    keyboardForced = !keyboardForced;
    api.keyboard(keyboardForced ? true : null);
    target.style.background = keyboardForced ? 'rgba(160,120,255,.6)' : 'rgba(255,255,255,.12)';
  });
  button('Share', 'Simulate sharing text into the app', 'dev-share', () => {
    const text = globalThis.prompt?.('Text to share into PocketShell', 'Shared from browser dev mode');
    if (text) api.share(text);
  });
  if (api.mode === 'mock') {
    button('Drop', 'Drop the SSH connection as a network loss would', 'dev-drop', () => api.dropConnections());
  }
  if (api.seedHosts.length > 0) {
    button('Host', `Fill the connect form with ${api.seedHosts[0].user}@${api.seedHosts[0].hostname}:${api.seedHosts[0].port}`,
      'dev-fill-host', () => fillConnectForm(doc, api.seedHosts[0]));
  }
  label.addEventListener('click', () => {
    const show = buttons.style.display === 'none';
    buttons.style.display = show ? 'flex' : 'none';
    globalThis.sessionStorage?.setItem(EXPANDED_KEY, show ? '1' : '0');
  });
  doc.body.append(bar);

  // Zero-setup: fill the connect form once, the first time it appears empty.
  if (api.seedHosts.length > 0 && typeof MutationObserver !== 'undefined') {
    const observer = new MutationObserver(() => {
      const hostname = doc.querySelector<HTMLInputElement>('[data-testid="ssh-host"]');
      const key = doc.querySelector<HTMLSelectElement>('[data-testid="ssh-key-selection"]');
      if (!hostname || !key || key.options.length < 2) return;
      observer.disconnect();
      if (!hostname.value) fillConnectForm(doc, api.seedHosts[0]);
    });
    observer.observe(doc.body, { childList: true, subtree: true });
  }
  return bar;
}
