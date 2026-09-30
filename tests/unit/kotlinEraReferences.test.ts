import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * Kotlin-era reference guard (issue #2938). The 0.6.0 tree has no `app2`,
 * `shared/*` Gradle modules, Compose render harness or Kotlin design kit, so
 * live docs, agent prompts and app source must not describe them as current.
 *
 * A match is allowed only when:
 * - its file is on HISTORICAL_FILES (a doc that is history as a whole), or
 * - a HISTORY_MARKER appears in its own block (paragraph, list item or code
 *   fence) or in a heading it sits under; the previous block also counts,
 *   but only as the lead-in to a code fence or when it ends with ':'.
 *
 * Everyday words ("legacy", "removed", "deleted", "history") are deliberately
 * NOT markers: an incidental one would clear a live claim (review K3). Mark
 * history with `release/0.5.x`, "0.5.x history" or "historical".
 *
 * `scripts/` and `.github/` are deliberately out of scope until #2934 and #2863
 * retire or port the legacy gate scripts that still name these modules.
 */
const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

const KOTLIN_ERA = /app2|:shared:|render\.sh|DesignRenders|Roborazzi|design-kit\/android|shared\/ui-kit/;

const SCANNED_PATHS = ['docs', 'src', 'AGENTS.md', 'process.md', 'README.md', '.claude/agents'];

/** Docs that are history as a whole; each carries its own historical framing. */
const HISTORICAL_FILES: readonly RegExp[] = [
  /^docs\/rewrite-implementation-plan\.md$/, // "Historical PocketShell rewrite playbook"
  /^docs\/rewrite-diagnosis-and-design\.md$/, // "Historical diagnosis, superseded"
  /^docs\/ci-pitfalls\.md$/, // header: entries are 0.5.x incident history
  /^docs\/decisions\.md$/, // the locked-decision log
  /^docs\/audit-[^/]+\.md$/, // dated audits
  /^docs\/js-first-rewrite-(plan|inventory)\.md$/, // pre-deletion migration maps from app2
  /^docs\/migration\//, // archived 0.5.x Room schemas for the importer
  /^docs\/design-kit\//, // 2026-09-06 Compose-era handoff, README marks it historical
];

const BINARY = /\.(png|jpe?g|gif|webp|pdf|ttf|otf|woff2?|ico)$/i;

/** Explicit history framing; honoured for the whole block and its headings. */
const HISTORY_MARKER = /release\/0\.5\.x|0\.5\.x history|historical|pre-rewrite|pre-app2/i;

const LIST_ITEM = /^\s*(?:[-*+]|\d+\.)\s/;

function trackedFiles(): string[] {
  const out = execFileSync('git', ['ls-files', '-z', '--', ...SCANNED_PATHS], { cwd: repoRoot, encoding: 'utf8' });
  return out.split('\0').filter((file) => file.length > 0 && !BINARY.test(file) && existsSync(path.join(repoRoot, file)));
}

/**
 * Docs that mark a boundary themselves: everything from `fromHeading` on is
 * history. Each entry cites the doc's own statement.
 */
const HISTORICAL_FROM_HEADING: Readonly<Record<string, string>> = {
  // docs/release.md "Branch layout": 'Everything below the two branch sections
  // ("Signing" onward) is the pre-#2934 procedure ... only make sense on release/0.5.x'.
  'docs/release.md': '## Signing',
};

interface Block {
  start: number;
  end: number;
  fenced: boolean;
}

/** Split markdown into blocks: paragraphs, single list items, headings and code fences. */
function markdownBlocks(lines: string[]): Block[] {
  const blocks: Block[] = [];
  let current: Block | null = null;
  let inFence = false;
  const close = () => {
    if (current) blocks.push(current);
    current = null;
  };
  lines.forEach((line, index) => {
    const fence = /^\s*```/.test(line);
    if (inFence) {
      current!.end = index;
      if (fence) {
        inFence = false;
        close();
      }
      return;
    }
    if (fence) {
      close();
      current = { start: index, end: index, fenced: true };
      inFence = true;
      return;
    }
    if (line.trim() === '') {
      close();
      return;
    }
    if (/^#{1,6}\s/.test(line)) {
      close();
      blocks.push({ start: index, end: index, fenced: false });
      return;
    }
    if (!current || LIST_ITEM.test(line)) {
      close();
      current = { start: index, end: index, fenced: false };
      return;
    }
    current.end = index;
  });
  close();
  return blocks;
}

/** Every match not covered by the allowlist or a history marker, as `file:line: text`. */
function unmarkedKotlinEraReferences(file: string, source: string): string[] {
  if (HISTORICAL_FILES.some((pattern) => pattern.test(file))) return [];
  const lines = source.split('\n');
  const offenders = (indexes: number[]) => indexes.map((index) => `${file}:${index + 1}: ${lines[index].trim()}`);
  const matches = lines.flatMap((line, index) => (KOTLIN_ERA.test(line) ? [index] : []));
  if (!file.endsWith('.md')) return offenders(matches);

  const historyFrom = HISTORICAL_FROM_HEADING[file];
  const blocks = markdownBlocks(lines);
  const text = (block: Block) => lines.slice(block.start, block.end + 1).join('\n');
  const headings: string[] = [];
  let inHistory = false;
  const unmarked: number[] = [];
  blocks.forEach((block, blockIndex) => {
    const first = lines[block.start];
    const heading = !block.fenced ? /^(#{1,6})\s/.exec(first) : null;
    if (heading) {
      headings.length = heading[1].length - 1;
      headings.push(first);
      if (historyFrom && first.startsWith(historyFrom)) inHistory = true;
    }
    if (inHistory) return;
    const previous = blocks[blockIndex - 1];
    const leadIn =
      previous && !/^#{1,6}\s/.test(lines[previous.start]) && (block.fenced || text(previous).trimEnd().endsWith(':'))
        ? text(previous)
        : '';
    const marked =
      HISTORY_MARKER.test(text(block)) || HISTORY_MARKER.test(leadIn) || headings.some((h) => HISTORY_MARKER.test(h));
    for (let index = block.start; index <= block.end; index += 1) {
      if (!KOTLIN_ERA.test(lines[index])) continue;
      if (marked) continue;
      unmarked.push(index);
    }
  });
  return offenders(unmarked);
}

describe('Kotlin-era references', () => {
  it('scans the tracked live docs, prompts and app source', () => {
    const files = trackedFiles();
    expect(files).toContain('docs/design-system.md');
    expect(files).toContain('AGENTS.md');
    expect(files.some((file) => file.startsWith('src/'))).toBe(true);
    expect(files.some((file) => file.startsWith('scripts/') || file.startsWith('.github/'))).toBe(false);
  });

  it('flags an unmarked reference and accepts a marked one', () => {
    expect(unmarkedKotlinEraReferences('docs/x.md', '# Modules\n\n`app2` is the only module.\n')).toHaveLength(1);
    expect(unmarkedKotlinEraReferences('src/x.ts', '// see shared/ui-kit\n')).toHaveLength(1);
    expect(unmarkedKotlinEraReferences('docs/x.md', '## Legacy lanes (`release/0.5.x`)\n\nRun `scripts/render.sh`.\n')).toEqual([]);
    expect(unmarkedKotlinEraReferences('docs/x.md', '## Legacy lanes\n\nRun `scripts/render.sh`.\n')).toHaveLength(1);
    expect(unmarkedKotlinEraReferences('docs/x.md', 'On `release/0.5.x`,\n`app2` runs.\n')).toEqual([]);
    expect(unmarkedKotlinEraReferences('docs/design-kit/spec/A.md', 'Roborazzi\n')).toEqual([]);
    // A live paragraph after a history paragraph is still live (review K4).
    expect(unmarkedKotlinEraReferences('docs/x.md', 'Old plans are history.\n\n`app2` is the module.\n')).toHaveLength(1);
    expect(unmarkedKotlinEraReferences('docs/x.md', 'Old plans are historical.\n\n`app2` is the module.\n')).toHaveLength(1);
    // An everyday word in a sibling list item does not clear another item (review K3).
    expect(unmarkedKotlinEraReferences('docs/x.md', '- `app2` renders.\n- the tab bar was removed.\n')).toHaveLength(1);
    expect(unmarkedKotlinEraReferences('docs/x.md', '- `app2` renders.\n- see `release/0.5.x`.\n')).toHaveLength(1);
    expect(unmarkedKotlinEraReferences('docs/x.md', '- `app2` renders the terminal; its old tab bar was removed.\n')).toHaveLength(1);
    // A code fence takes its lead-in sentence's marker.
    expect(unmarkedKotlinEraReferences('docs/x.md', 'On `release/0.5.x` run:\n\n```\n./gradlew :app2:test\n```\n')).toEqual([]);
  });

  it('finds no unmarked app2, :shared:, Compose render or Kotlin design-kit reference', () => {
    const offenders = trackedFiles().flatMap((file) =>
      unmarkedKotlinEraReferences(file, readFileSync(path.join(repoRoot, file), 'utf8')),
    );
    expect(offenders).toEqual([]);
  });
});
