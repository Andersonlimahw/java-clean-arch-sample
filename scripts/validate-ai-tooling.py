#!/usr/bin/env python3
"""Validate canonical discovery, aliases and scaffold preflight without writes."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
SKILLS = ('create-crud', 'local-gate', 'test-resource', 'run-api-requests',
          'flyway-migration', 'docs-sync', 'add-integration-event')
for name in SKILLS:
    canonical = ROOT / '.agents/skills' / name
    body = (canonical / 'SKILL.md').read_text()
    assert body.startswith('---\n') and f'name: {name}\n' in body, name
    alias = ROOT / '.claude/skills' / name
    assert alias.is_symlink() and alias.resolve() == canonical.resolve(), alias
    command = ROOT / '.gemini/commands' / f'{name}.toml'
    assert f'.agents/skills/{name}/SKILL.md' in command.read_text(), command
for alias, target in [('GEMINI.md', 'AGENTS.md'),
                      ('.claude/agents/architecture-reviewer.md', '.agents/reviewers/architecture.md')]:
    path = ROOT / alias
    assert path.is_symlink() and path.resolve() == (ROOT / target).resolve(), alias
for base in ('.agents', '.claude', '.gemini'):
    for path in (ROOT / base).rglob('*'):
        assert not path.is_symlink() or path.exists(), f'broken alias: {path}'
probe = 'tooling-probe-resources'
while (ROOT / 'src/main/java/com/muse/payments' / probe.replace('-', '')).exists():
    probe = 'extra-' + probe
subprocess.run(['python3', '-I', str(ROOT / '.agents/skills/create-crud/scripts/scaffold.py'),
                probe, '--root', str(ROOT), '--dry-run'], check=True, stdout=subprocess.DEVNULL)
print('AI tooling validation passed: 7 canonical skills, aliases, reviewer and scaffold preflight')
