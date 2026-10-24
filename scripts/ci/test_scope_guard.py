"""Behavioral boundary fixtures over real commit graphs; no candidate execution."""
import datetime as dt
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('scope_guard', Path(__file__).with_name('scope_guard.py'))
g = importlib.util.module_from_spec(spec)
spec.loader.exec_module(g)
ID = 'd0e54a43-debd-492f-a1fc-af2eccc685b9'


class BoundaryTests(unittest.TestCase):
    def setUp(self):
        scratch = Path(os.environ.get('TMPDIR', str(Path(__file__).resolve().parents[2] / '.local/tmp')))
        scratch.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='scope-fixture-', dir=scratch)
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.git('init', '-q', '-b', 'main')
        self.git('config', 'user.email', 'fixture@example.invalid')
        self.git('config', 'user.name', 'Scope fixture')
        self.write('SCOPE.md', 'Product charter\n')
        self.write('scope-exceptions.json', '[]')
        self.commit('baseline')
        self.base = self.git('rev-parse', 'HEAD').strip()

    def git(self, *args):
        return g.git(self.repo, *args)

    def write(self, name, text):
        p = self.repo / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)

    def commit(self, message='fixture\n\nScope: in'):
        self.git('add', '.')
        self.git('commit', '-qm', message)

    def check(self, product='hub', **kw):
        return g.check(self.repo, product, self.base, 'HEAD', **kw)[0]

    def test_current_main_without_exceptions(self):
        self.assertEqual(self.check(), [])

    def test_allowed_bounded_capture_ui(self):
        self.write('public/capture.js', 'showCapture(await readSnapshot(session));\n')
        self.commit()
        self.assertEqual(self.check(), [])

    def test_hub_pty_proxy_and_dependencies_fail(self):
        for code in ['import pty from "node-pty";', 'import "@xterm/xterm";', 'import ssh from "ssh2";', 'startSFTP();', 'enable_port_forward();', 'import "pocketshell-client";', 'new WebSocketPair();']:
            with self.subTest(code=code):
                patch = '+++ b/src/proxy.ts\n@@ -0,0 +1 @@\n+' + code
                self.assertTrue(g.hits('hub', patch))

    def test_manifest_dependencies_fail(self):
        self.write('package.json', '{"dependencies":{"node-pty":"1"}}')
        self.commit()
        self.assertTrue(self.check())

    def test_skip_ci_and_reverted_intermediate_commit_fail(self):
        self.write('src/proxy.ts', 'import "ssh2";\n')
        self.commit('proxy [skip ci]')
        (self.repo / 'src/proxy.ts').unlink()
        self.commit('remove proxy [skip ci]')
        self.assertTrue(self.check())

    def test_merge_side_commit_is_checked(self):
        self.git('checkout', '-qb', 'feature')
        self.write('src/pty.ts', 'import "node-pty";\n')
        self.commit()
        self.git('checkout', 'main')
        self.write('src/ok.ts', 'capture();\n')
        self.commit()
        self.git('merge', '--no-ff', '-m', 'merge [skip ci]', 'feature')
        self.assertTrue(self.check())

    def test_same_range_exception_cannot_approve_feature(self):
        exception = dict(path='src/proxy.ts', token='ssh2', intake_id=ID, approved_by='Alexey', date='2026-10-24')
        self.write('scope-exceptions.json', json.dumps([exception]))
        self.commit('approval')
        self.write('src/proxy.ts', 'import "ssh2";\n')
        self.commit()
        self.assertTrue(self.check())
        self.base = self.git('rev-parse', 'HEAD^').strip()
        self.assertEqual(self.check(), [])

    def test_exception_needs_intake_and_approver(self):
        self.write('scope-exceptions.json', '[{"path":"src/proxy.ts","token":"ssh2"}]')
        self.commit()
        with self.assertRaises(ValueError):
            self.check()

    def test_trailer_warning_week_and_then_failure(self):
        self.write('src/session_control.ts', 'capture();\n')
        self.commit('session change')
        v, w, count = g.check(self.repo, 'hub', self.base, 'HEAD', today=dt.date(2026,10,30))
        self.assertEqual(v, [])
        self.assertTrue(w)
        self.assertTrue(self.check(today=dt.date(2026,10,31)))

    def test_trailer_in_body_does_not_count(self):
        self.write('public/capture.js', 'capture();\n')
        self.commit('change\n\nScope: in\n\nSome prose afterwards')
        self.assertTrue(self.check(today=dt.date(2026,11,1)))

    def test_gray_intake_trailer(self):
        self.write('src/registry.ts', 'register();\n')
        self.commit('registry\n\nScope: gray ' + ID)
        self.assertEqual(self.check(today=dt.date(2026,11,1)), [])

    def test_manual_pocketshell_start_passes(self):
        self.write('src/start.ts', 'startSession({engine: "claude", cwd: humanFolder});\n')
        self.commit()
        self.assertEqual(self.check('pocketshell'), [])

    def test_pocketshell_fleet_and_quota_history_fail(self):
        for name, text in [('src/fleet.ts','const fleet = chooseMachine();'), ('src/history.ts','export const quota_history = [];'), ('src/push/new.py','send();'), ('src/cards/note.py','save();'), ('src/mcp/server.ts','serve();'), ('src/commands/a.ts','export function runReviewers() {}'), ('src/api.ts','new McpServer();')]:
            with self.subTest(name=name):
                self.assertTrue(g.hits('pocketshell', '+++ b/' + name + '\n@@ -0,0 +1 @@\n+' + text))

    def test_label_and_prior_approved_adr_required(self):
        adr = 'docs/adr/scope.md'
        self.write(adr, 'Status: approved\nApproved-by: Alexey\nIntake: ' + ID + '\n')
        self.commit('approved decision')
        self.base = self.git('rev-parse','HEAD').strip()
        self.write('src/router.ts', 'const fleet = selectMachine();')
        self.commit()
        self.assertTrue(self.check('pocketshell', labels=['scope:gray']))
        self.assertTrue(self.check('pocketshell', pr_body=adr))
        self.assertEqual(self.check('pocketshell', labels=['scope:gray'], pr_body=adr), [])
        self.base = self.git('rev-parse','HEAD~2').strip()
        self.assertTrue(self.check('pocketshell', labels=['scope:gray'], pr_body=adr))

    def test_network_routing_and_link_builder_pass(self):
        self.write('src/network.ts', 'const routing = tcp.connect();\n')
        self.write('src/links.ts', 'return "https://hub.dtcdev.click/sessions/" + id;\n')
        self.commit()
        self.assertEqual(self.check('pocketshell'), [])
        self.write('src/links.ts','fetch("https://hub.dtcdev.click/api/fleet");')
        self.commit()
        self.assertTrue(self.check('pocketshell'))

    def test_historical_comment_false_positives(self):
        for path, text in [('src/transport/ssh.ts', '/** Vault credential routing (agent.ts). */'), ('src/tree.vue', ' Same placement as the tab strip. -->'), ('android/app/src/androidTest/java/Fixture.java', 'assertFalse("routing token", true);'), ('security/inproc_test.go', 't.Fatal("routing token");'), ('release/inputs/dossiers/build.json', '{"fleet":"fixture"}')]:
            self.assertEqual(g.hits('pocketshell', '+++ b/' + path + '\n@@ -0,0 +1 @@\n+' + text), [])

    def test_multiline_docstrings_are_not_runtime_scope(self):
        patch = '\n'.join(['+++ b/src/endpoint.py', '@@ -0,0 +1,5 @@', '+\"\"\"Release note', '+Fleet consumer agreement.', '+\"\"\"', '+def manual_start():', '+    pass'])
        self.assertEqual(g.hits('pocketshell', patch), [])
        self.assertTrue(g.hits('pocketshell', patch + '\n+fleet = select_machine()'))

    def test_comment_suffix_cannot_hide_executable_scope(self):
        for path, text in [('src/proxy.ts', 'import "ssh2"; // -->'), ('src/proxy.py', 'import ssh2 # -->'), ('src/view.vue', 'import "ssh2"; // -->')]:
            self.assertTrue(g.hits('hub', '+++ b/' + path + '\n@@ -0,0 +1 @@\n+' + text))
        for text in ['const fleet = 1; // -->', '#fleet = 1', '*fleet() {}']:
            self.assertTrue(g.hits('pocketshell', '+++ b/src/router.ts\n@@ -0,0 +1 @@\n+' + text))

    def test_uncommitted_and_untracked_proxy_fail(self):
        self.write('src/new.ts', 'import "node-pty";')
        self.assertTrue(self.check(working=True))
        self.git('add','.')
        self.assertTrue(self.check(working=True))


if __name__ == '__main__':
    unittest.main()
