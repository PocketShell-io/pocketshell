#!/usr/bin/env python3
"""Fail closed on the actual packaged J18/J22 tests; retain their original XML."""
import argparse
from collections import Counter
from pathlib import Path
import shutil
import tempfile
import xml.etree.ElementTree as ET

METHOD_CLASSES = {
    'automaticForwardStartsWithoutPortsAndSurvivesResume': 'com.pocketshell.app.smoke.J18AutoForwardResumeJourney',
    'foregroundServiceKeepsTunnelInBackgroundAndNotificationStopsIt': 'com.pocketshell.app.smoke.J22ForwardServiceFgsJourney',
}
REQUIRED = {(klass, method) for method, klass in METHOD_CLASSES.items()}

def check(directory):
    seen=Counter()
    for report in Path(directory).rglob('*.xml'):
        root=ET.parse(report).getroot()
        for suite in ([root] if root.tag=='testsuite' else root.findall('.//testsuite')):
            if any(int(suite.get(name, '0')) != 0 for name in ('failures', 'errors', 'skipped')):
                raise ValueError('JUnit suite reports failures, errors or skipped tests')
            cases=suite.findall('testcase')
            if int(suite.get('tests','-1')) != len(cases): raise ValueError('JUnit count mismatch')
            for case in cases:
                if any(case.find(tag) is not None for tag in ('failure','error','skipped')): raise ValueError('failed or skipped journey')
                seen[(case.get('classname'),case.get('name'))]+=1
    if set(seen)!=REQUIRED or any(count!=1 for count in seen.values()): raise ValueError(f'expected exactly the two forward journeys, got {seen}')


def self_test():
    with tempfile.TemporaryDirectory() as temp:
        p=Path(temp)/'results.xml'
        cases=''.join(f'<testcase classname="{c}" name="{m}"/>' for c,m in sorted(REQUIRED))
        p.write_text(f'<testsuite tests="2">{cases}</testsuite>')
        check(temp)
        for contents in ('<testsuite tests="0"/>', f'<testsuite tests="2">{cases.replace("/>", "><failure/></testcase>",1)}</testsuite>',f'<testsuite tests="1">{cases.split("/>")[0]}/></testsuite>'):
            p.write_text(contents)
            try: check(temp)
            except ValueError: pass
            else: raise AssertionError('fail-closed regression')

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--results-dir',type=Path)
    parser.add_argument('--evidence-dir',type=Path)
    parser.add_argument('--host-evidence-dir',type=Path)
    parser.add_argument('--run-id')
    parser.add_argument('--self-test',action='store_true')
    args=parser.parse_args()
    if args.self_test: self_test(); print('PASS: forward-service checker self-test')
    else:
        check(args.results_dir)
        if args.host_evidence_dir:
            if not args.run_id: raise ValueError('host evidence requires the same run ID')
            for journey, minimum in (('J18', 2), ('J22', 10)):
                content=(args.host_evidence_dir/f'{journey}-http.log').read_text()
                needle=f'GET /PS_{journey}_{args.run_id}.txt HTTP/1.0" 200'
                if content.count(needle) < minimum: raise ValueError(f'{journey}: missing same-run independent host traffic evidence')
        if args.evidence_dir: shutil.copytree(args.results_dir,args.evidence_dir/'instrumentation-results',dirs_exist_ok=True)
        print('PASS: both real forward-service journeys executed exactly once')
