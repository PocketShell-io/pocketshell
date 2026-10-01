import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * The phone UI's design-token guard (issue #2938). It replaces the deleted
 * Compose-era scripts/check-design-tokens.sh and check-component-drift.sh:
 * Android's own CSS and Vue files must take colours from the shared token set
 * and may only read tokens that exist, because an undefined `var(--x)` falls
 * back silently instead of failing.
 */
const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

/** Tokens Android sets at runtime from Capacitor/IME insets (see App.vue). */
const RUNTIME_TOKENS = new Set([
  '--safe-area-inset-top',
  '--safe-area-inset-right',
  '--safe-area-inset-bottom',
  '--safe-area-inset-left',
  // App.vue writes the font-size-derived minimum terminal grid height onto
  // the document root with style.setProperty (#2884).
  '--terminal-min-grid-height',
]);

const COLOUR_LITERAL = /#[0-9a-fA-F]{3,8}\b|\b(?:rgba?|hsla?|hwb|lab|lch|oklab|oklch)\(/;

function sharedUiSourceDir(): string {
  const tsconfig = JSON.parse(readFileSync(path.join(repoRoot, 'tsconfig.json'), 'utf8')) as {
    compilerOptions: { paths: Record<string, string[]> };
  };
  // The shared UI package rides inside the core pin (issue #2935); `@ui` is
  // the same alias pocketshell-desktop and pocketshell-web use.
  const [pattern] = tsconfig.compilerOptions.paths['@ui/*'] ?? [];
  if (!pattern?.endsWith('/*')) throw new Error('tsconfig.json has no @ui/* path');
  const dir = pattern.slice(0, -2);
  if (dir !== 'vendor/pocketshell-core/packages/ui/src') {
    throw new Error(`@ui/* must resolve inside the core pin, got ${dir}`);
  }
  return path.join(repoRoot, dir);
}

/** CSS named colours (CSS Color 4), excluding keywords such as transparent/currentColor. */
const NAMED_COLOURS = new Set(
  (
    'aliceblue antiquewhite aqua aquamarine azure beige bisque black blanchedalmond blue blueviolet brown ' +
    'burlywood cadetblue chartreuse chocolate coral cornflowerblue cornsilk crimson cyan darkblue darkcyan ' +
    'darkgoldenrod darkgray darkgreen darkgrey darkkhaki darkmagenta darkolivegreen darkorange darkorchid ' +
    'darkred darksalmon darkseagreen darkslateblue darkslategray darkslategrey darkturquoise darkviolet ' +
    'deeppink deepskyblue dimgray dimgrey dodgerblue firebrick floralwhite forestgreen fuchsia gainsboro ' +
    'ghostwhite gold goldenrod gray green greenyellow grey honeydew hotpink indianred indigo ivory khaki ' +
    'lavender lavenderblush lawngreen lemonchiffon lightblue lightcoral lightcyan lightgoldenrodyellow ' +
    'lightgray lightgreen lightgrey lightpink lightsalmon lightseagreen lightskyblue lightslategray ' +
    'lightslategrey lightsteelblue lightyellow lime limegreen linen magenta maroon mediumaquamarine ' +
    'mediumblue mediumorchid mediumpurple mediumseagreen mediumslateblue mediumspringgreen ' +
    'mediumturquoise mediumvioletred midnightblue mintcream mistyrose moccasin navajowhite navy oldlace ' +
    'olive olivedrab orange orangered orchid palegoldenrod palegreen paleturquoise palevioletred ' +
    'papayawhip peachpuff peru pink plum powderblue purple rebeccapurple red rosybrown royalblue ' +
    'saddlebrown salmon sandybrown seagreen seashell sienna silver skyblue slateblue slategray slategrey ' +
    'snow springgreen steelblue tan teal thistle tomato turquoise violet wheat white whitesmoke yellow ' +
    'yellowgreen'
  ).split(' '),
);

/** Properties whose values carry a colour. */
const COLOUR_PROPERTY = /(?:^|-)(?:color|background|border|outline|fill|stroke|shadow|caret|accent|decoration)/;

function vueFiles(dir: string): string[] {
  return readdirSync(path.join(repoRoot, dir), { withFileTypes: true }).flatMap((entry) => {
    const rel = `${dir}/${entry.name}`;
    if (entry.isDirectory()) return vueFiles(rel);
    return entry.name.endsWith('.vue') ? [rel] : [];
  });
}

function phoneStyleSources(): string[] {
  return ['src/styles.css', 'src/App.vue', ...vueFiles('src/components')];
}

/** Named colours used as a value of a colour-bearing declaration. */
function namedColourDeclarations(css: string): string[] {
  const found: string[] = [];
  for (const match of css.matchAll(/([a-z-]+)\s*:\s*([^;{}]+)/gi)) {
    const [, property, value] = match;
    if (!COLOUR_PROPERTY.test(property.toLowerCase())) continue;
    const words = value.toLowerCase().match(/[a-z]+/g) ?? [];
    const named = words.filter((word) => NAMED_COLOURS.has(word));
    if (named.length > 0) found.push(`${property}: ${value.trim()}`);
  }
  return found;
}

/** Style text only: CSS files whole, Vue files' <style> blocks and inline style bindings. */
function styleText(file: string): string {
  const source = readFileSync(path.join(repoRoot, file), 'utf8');
  if (file.endsWith('.css')) return source;
  const blocks = [...source.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].map((match) => match[1]);
  // Static style="…" and bound :style="…" / v-bind:style="…" attributes.
  const inline = [...source.matchAll(/(?:^|[\s:])style="([^"]*)"/g)].map((match) => match[1]);
  return [...blocks, ...inline].join('\n');
}

function stripComments(css: string): string {
  return css.replace(/\/\*[\s\S]*?\*\//g, '');
}

function declaredTokens(css: string): Set<string> {
  // A quoted key also counts: a bound :style="{ '--x': value }" defines --x on
  // that element at runtime.
  return new Set([...stripComments(css).matchAll(/(--[a-zA-Z0-9-]+)['"]?\s*:/g)].map((match) => match[1]));
}

describe('phone design tokens', () => {
  const files = phoneStyleSources();
  const sharedTokens = declaredTokens(readFileSync(path.join(sharedUiSourceDir(), 'tokens.css'), 'utf8'));

  it('reads the shared token source the @ui alias points at', () => {
    expect(sharedTokens.size).toBeGreaterThan(50);
    for (const token of ['--bg', '--fg', '--accent', '--font-ui', '--font-mono', '--sp-4', '--r-md']) {
      expect(sharedTokens, `shared tokens.css lacks ${token}`).toContain(token);
    }
  });

  it('takes every colour from a token rather than a literal', () => {
    expect(files.length).toBeGreaterThan(2);
    const offenders: string[] = [];
    for (const file of files) {
      stripComments(styleText(file))
        .split('\n')
        .forEach((line, index) => {
          if (COLOUR_LITERAL.test(line)) offenders.push(`${file}:${index + 1}: ${line.trim()}`);
        });
    }
    expect(offenders).toEqual([]);
  });

  it('uses no CSS named colour in a colour declaration', () => {
    const offenders = files.flatMap((file) =>
      namedColourDeclarations(stripComments(styleText(file))).map((declaration) => `${file}: ${declaration}`),
    );
    expect(offenders).toEqual([]);
  });

  it('reads only tokens that the shared set, the phone stylesheet or the runtime defines', () => {
    const local = new Set<string>();
    const used: Array<{ file: string; token: string }> = [];
    for (const file of files) {
      const css = stripComments(styleText(file));
      declaredTokens(css).forEach((token) => local.add(token));
      for (const match of css.matchAll(/var\(\s*(--[a-zA-Z0-9-]+)/g)) used.push({ file, token: match[1] });
    }
    expect(used.length).toBeGreaterThan(0);
    const undefinedTokens = used
      .filter(({ token }) => !sharedTokens.has(token) && !local.has(token) && !RUNTIME_TOKENS.has(token))
      .map(({ file, token }) => `${file}: ${token}`);
    expect(undefinedTokens).toEqual([]);
  });
});
