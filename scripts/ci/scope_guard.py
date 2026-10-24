#!/usr/bin/env python3
"""Diff-only product boundary check. Never imports or executes candidate code."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess
import sys

# Fixed adoption date: changing a candidate policy cannot postpone enforcement.
TRAILER_ENFORCED = dt.date(2026, 10, 31)
HUB_TOKENS = {
    'xterm': r'(?i)\bxterm\b',
    'node-pty': r'(?i)\b(?:@lydell/)?node-pty\b',
    'ssh2': r'(?i)\bssh2\b',
    'sftp': r'(?i)sftp',
    'port-forward': r'(?i)port[-_ ]?forward(?:ing)?',
    'pocketshell-package': r'(?i)(?:@pocketshell/|\bpocketshell-)[\w-]+',
    'websocket': r'(?i)\b(?:WebSocketPair|new\s+WebSocket|WebSocketServer|acceptWebSocket|websocket\.accept)\b|[\x22\x27]/[^\x22\x27]*(?:websocket|attach-ws|/ws)(?:/|[\x22\x27])',
}
POCKET_TOKENS = {
    'hub-work': r'(?i)\b(?:tier|intake|fleet|placement)\b|quota[ _-]+(?:admission|history)|acceptance[ _-]+criteria|(?:fleet|model)[_-]?router',
    'routing': r'(?i)\brouting\b',
    'hub-runtime': r'(?i)hub\.dtcdev\.click',
    'agent-automation': r'(?i)\b(?:auto[_-]?(?:review|retry|dispatch)|quota[_-]?(?:scheduler|reset[_-]?timer))\b',
    'mcp-server': r'(?i)(?:@modelcontextprotocol/sdk/server|\bMcpServer\b|\bServer\([^\n]*mcp)',
}
UUID = r'[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}'


def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args], stderr=subprocess.DEVNULL).decode('utf-8', 'replace')


def blob(repo, rev, path, default=None):
    try:
        return git(repo, 'show', f'{rev}:{path}')
    except subprocess.CalledProcessError:
        return default


def additions(patch):
    """Return path/line/text without leaking candidate contents to diagnostics."""
    path, lineno = None, 0
    for line in patch.splitlines():
        if line.startswith('+++ '):
            path = line[4:]
            if path.startswith('b/'):
                path = path[2:]
            elif path == '/dev/null':
                path = None
            if path and path.startswith('"'):
                raise ValueError('quoted paths are not supported; rename the file before checking')
        elif line.startswith('@@ '):
            match = re.search(r'\+(\d+)', line)
            if match:
                lineno = int(match[1]) - 1
        elif line.startswith('+') and path:
            lineno += 1
            yield path, lineno, line[1:]
        elif line.startswith(' '):
            lineno += 1


def runtime(path):
    parts = Path(path).parts
    return not (any(p.lower() in ('test', 'tests', '__tests__', 'androidtest', 'testfixtures', 'docs', '.github', 'scripts', 'vendor', 'node_modules') for p in parts)
                or path.endswith(('.md', '.snap', '.lock')) or '.test.' in path or '.spec.' in path or path.endswith('_test.go') or path.startswith('release/inputs/'))


def comment(path, line):
    stripped = line.lstrip()
    if Path(path).suffix in ('.py', '.sh', '.yaml', '.yml', '.toml') and stripped.startswith('#'):
        return True
    if stripped.startswith('//'):
        return True
    # A diff hunk may contain only the last plain-text line of a markup comment.
    # Never classify executable code with a trailing comment marker as a comment.
    return (Path(path).suffix in ('.vue', '.html', '.xml', '.svelte')
            and bool(re.fullmatch(r"[A-Za-z0-9 ,.'`<>@-]+-->", stripped)))


def sensitive(path):
    return path in ('src/session_control.ts', 'src/registry.ts') or path.startswith(('src/auth/', 'public/'))


def frozen(path):
    return path == 'src/commands/a.ts' or bool(re.search(r'(?i)(?:^|/)(?:push|cards|mcp|psc|quota[-_]history)(?:/|\.)|(?:^|/)(?:mcp[_-](?:server|proxy)|usage/(?:capture|reset))', path))


def hits(product, patch, repo_name=''):
    found = []
    block, previous_path, previous_line = None, None, 0
    for path, lineno, line in additions(patch):
        if path != previous_path or lineno != previous_line + 1:
            block = None
        previous_path, previous_line = path, lineno
        if block:
            if block in line:
                block = None
            continue
        stripped = line.lstrip()
        for opening, closing in [('\"\"\"', '\"\"\"'), ("'''", "'''"), ('/*', '*/'), ('<!--', '-->')]:
            if stripped.startswith(opening):
                if closing not in stripped[len(opening):]:
                    block = closing
                break
        else:
            opening = None
        if opening:
            continue
        if not runtime(path) and path not in ('package-lock.json', 'uv.lock'):
            continue
        if product == 'hub':
            if not (path.startswith(('src/', 'host/', 'public/')) or path in ('package.json', 'package-lock.json', 'pyproject.toml', 'requirements.txt', 'uv.lock')):
                continue
            patterns = HUB_TOKENS
        else:
            if frozen(path):
                found.append((path, lineno, 'removed-feature'))
            patterns = dict(POCKET_TOKENS)
            if repo_name.split('-wt-')[0] == 'pocketshell-cli':
                patterns['quota-push'] = r'(?i)\b(?:FCM|firebase)\b'
        if comment(path, line):
            continue
        for token, pattern in patterns.items():
            if product == 'pocketshell':
                # Actual link builders may emit navigation URLs, never runtime calls.
                if token == 'hub-runtime' and re.search(r'(?i)(?:^|/)(?:link[-_]?builders?|links)(?:\.[\w]+)?$', path) and not re.search(r'\b(?:fetch|request|axios|connect)\s*\(', line):
                    continue
                # Network/terminal routing belongs to PocketShell, model routing does not.
                if token == 'routing' and re.search(r'(?i)\b(?:ssh|tcp|udp|network|tunnel|port|terminal)\b', line) and not re.search(r'(?i)\b(?:model|account|quota|fleet|agent)\b', line):
                    continue
            if re.search(pattern, line):
                found.append((path, lineno, token))
    return found


def exceptions(repo, rev):
    raw = blob(repo, rev, 'scope-exceptions.json', '[]')
    values = json.loads(raw)
    if not isinstance(values, list):
        raise ValueError('scope-exceptions.json must be an array')
    for item in values:
        if (not isinstance(item, dict) or not all(isinstance(item.get(k), str) and item[k].strip() for k in ('path', 'token', 'intake_id', 'approved_by', 'date'))
                or not re.fullmatch(UUID, item['intake_id']) or item['token'] not in HUB_TOKENS):
            raise ValueError('every exception requires path, known token, intake_id UUID, approved_by and date')
        dt.date.fromisoformat(item['date'])
    return values


def approved_adr(repo, base, body):
    # Only an already-approved, repository-local ADR on the trusted base counts.
    for path in re.findall(r'docs/(?:adr|decisions)/[A-Za-z0-9._/-]+\.md', body or ''):
        if '..' in Path(path).parts:
            continue
        text = blob(repo, base, path, '')
        if (re.search(r'(?im)^Status:\s*approved\s*$', text)
                and re.search(r'(?im)^Approved-by:\s*Alexey\s*$', text)
                and re.search(r'(?im)^Intake:\s*' + UUID + r'\s*$', text)):
            return True
    return False


def event_base(repo, head):
    event_path = os.environ.get('GITHUB_EVENT_PATH')
    if event_path:
        event = json.loads(Path(event_path).read_text())
        if 'pull_request' in event:
            return event['pull_request']['base']['sha']
        before = event.get('before')
        if before and before != '0' * 40:
            return before
        if os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch':
            return git(repo, 'rev-parse', head + '^').strip()
    return 'origin/main'


def check(repo, product, base, head, today=None, working=False, labels=(), pr_body=''):
    today = today or dt.datetime.now(dt.timezone.utc).date()
    base = git(repo, 'rev-parse', '--verify', base + '^{commit}').strip()
    head = git(repo, 'rev-parse', '--verify', head + '^{commit}').strip()
    # Base may be a sibling PR base; inspect commits on the candidate side only.
    common = git(repo, 'merge-base', base, head).strip()
    trusted = exceptions(repo, base) if product == 'hub' else []
    if product == 'hub':
        exceptions(repo, head)  # Validate candidate additions even without a token hit.
        if working and (Path(repo) / 'scope-exceptions.json').exists():
            raw = json.loads((Path(repo) / 'scope-exceptions.json').read_text())
            if not isinstance(raw, list) or any(not isinstance(e, dict) or not all(e.get(k) for k in ('path', 'token', 'intake_id', 'approved_by', 'date')) for e in raw):
                raise ValueError('invalid working exceptions')
    commits = git(repo, 'rev-list', '--reverse', f'{base}..{head}').split()
    patches, warnings = [], []
    for sha in commits:
        parents = git(repo, 'rev-list', '--parents', '-n', '1', sha).split()[1:]
        for parent in parents:
            patch = git(repo, '-c', 'core.quotePath=false', 'diff', '--no-ext-diff', '--no-renames', '--unified=0', parent, sha)
            patches.append((sha[:12], patch))
        names = git(repo, 'diff-tree', '--no-commit-id', '--name-only', '-m', '-r', sha).splitlines()
        if product == 'hub' and any(sensitive(p) for p in names):
            message = git(repo, 'show', '-s', '--format=%B', sha)
            trailers = message.strip().split('\n\n')[-1]
            if not re.search(r'(?m)^Scope: (?:in|gray ' + UUID + r')\s*$', trailers):
                entry = (sha[:12], 'commit', 'Scope trailer missing (enforced 2026-10-31)')
                if today < TRAILER_ENFORCED:
                    warnings.append(entry)
                else:
                    patches.append((sha[:12], ''))
                    warnings.append(entry + ('fail',))
    # Net diff catches newly introduced/changed files even on unusual graph shapes.
    patches.append(('diff', git(repo, '-c', 'core.quotePath=false', 'diff', '--no-ext-diff', '--no-renames', '--unified=0', common, head)))
    if working:
        patches.append(('working', git(repo, '-c', 'core.quotePath=false', 'diff', '--no-ext-diff', '--no-renames', '--unified=0', head)))
        for path in git(repo, 'ls-files', '--others', '--exclude-standard').splitlines():
            p = Path(repo) / path
            if p.is_file() and p.stat().st_size <= 2_000_000:
                text = p.read_text(errors='replace')
                patches.append(('untracked', '+++ b/' + path + '\n@@ -0,0 +1 @@\n' + '\n'.join('+' + line for line in text.splitlines())))
    violations = []
    for revision, patch in patches:
        for path, line, token in hits(product, patch, Path(repo).name):
            if any(e['path'] == path and e['token'] == token for e in trusted):
                continue
            violations.append((revision, path, line, token))
    if product == 'pocketshell' and 'scope:gray' in labels and approved_adr(repo, base, pr_body):
        violations = []
    for warning in warnings:
        if len(warning) == 4:
            violations.append((warning[0], 'commit', 0, warning[2]))
    return sorted(set(violations)), warnings, len(commits)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--product', choices=('hub', 'pocketshell'), default='pocketshell')
    p.add_argument('--repo', default='.')
    p.add_argument('--base')
    p.add_argument('--head', default='HEAD')
    p.add_argument('--working', action='store_true')
    args = p.parse_args()
    try:
        labels, body = [], ''
        if os.environ.get('GITHUB_EVENT_PATH'):
            event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
            pr = event.get('pull_request', {})
            labels = [x['name'] for x in pr.get('labels', [])]
            body = pr.get('body') or ''
        violations, warnings, count = check(args.repo, args.product, args.base or event_base(args.repo, args.head), args.head, working=args.working, labels=labels, pr_body=body)
        for warning in warnings:
            print('WARNING:', *warning[:3])
        for revision, path, line, token in violations:
            print(f'FAIL: {revision} {path}:{line}: {token}')
        print(f'Scope guard: {count} new commits; {len(violations)} violations')
        return bool(violations)
    except (OSError, ValueError, subprocess.CalledProcessError, KeyError) as exc:
        print('Scope guard failed closed:', type(exc).__name__, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
