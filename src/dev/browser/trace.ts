/**
 * Browser dev mode (#3022): `?devTrace=1` logs each SshCapability call to the
 * browser console — method, exec/PTY command and outcome code. Never PTY
 * bytes, file contents or credentials.
 */
import type { DevPlugin } from './nativeBridge';

export function traceSshPlugin(plugin: DevPlugin, log: (line: string) => void = (line) => console.debug(line)): DevPlugin {
  return {
    methods: Object.fromEntries(Object.entries(plugin.methods).map(([name, method]) => [name, async (options: Record<string, unknown>) => {
      if (name === 'readPty' || name === 'writePty') return method(options);
      const detail = typeof options.command === 'string' ? ` ${options.command}` : typeof options.path === 'string' ? ` ${options.path}` : '';
      const started = Date.now();
      try {
        const result = await method(options);
        const exit = name === 'exec' ? ` exit=${String((result as { exitCode?: unknown }).exitCode)}` : '';
        log(`[dev ssh] ${name}${detail} ok${exit} ${Date.now() - started}ms`);
        return result;
      } catch (error) {
        log(`[dev ssh] ${name}${detail} failed ${String((error as { code?: unknown }).code ?? 'ERROR')}`);
        throw error;
      }
    }])),
  };
}
