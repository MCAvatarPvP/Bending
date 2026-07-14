#!/usr/bin/env python3
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMMON = ROOT / 'common' / 'src' / 'main' / 'java'
ABILITY_PACKAGES = [
    'com/projectkorra/projectkorra/ability',
    'com/projectkorra/projectkorra/airbending',
    'com/projectkorra/projectkorra/waterbending',
    'com/projectkorra/projectkorra/earthbending',
    'com/projectkorra/projectkorra/firebending',
    'com/projectkorra/projectkorra/chiblocking',
    'com/projectkorra/projectkorra/avatar',
]
COMMON_FORBIDDEN = [
    r'^import\s+org\.bukkit\.', r'^import\s+io\.papermc\.',
    r'^import\s+net\.minecraft\.', r'^import\s+net\.fabricmc\.',
    r'^import\s+net\.md_5\.', r'^import\s+com\.sk89q\.',
    r'^import\s+com\.massivecraft\.', r'^import\s+me\.angeschossen\.',
    r'^import\s+br\.net\.', r'^import\s+com\.bekvon\.',
    r'^import\s+me\.ryanhamshire\.', r'^import\s+com\.griefcraft\.',
    r'^import\s+org\.betonquest\.', r'^import\s+io\.lumine\.',
    r'^import\s+ga\.strikepractice\.', r'^import\s+org\.geysermc\.',
    r'^import\s+me\.clip\.', r'^import\s+com\.palmergames\.',
    r'^import\s+com\.github\.retrooper\.', r'^import\s+com\.griefdefender\.',
]
ABILITY_FORBIDDEN = [r'org\.bukkit', r'io\.papermc', r'net\.minecraft', r'net\.fabricmc', r'Bukkit\.', r'PaperLib']


def scan(paths, patterns):
    bad = []
    for file in paths:
        text = file.read_text(errors='ignore')
        for i, line in enumerate(text.splitlines(), 1):
            for pat in patterns:
                if re.search(pat, line):
                    bad.append((file.relative_to(ROOT), i, line.strip()))
    return bad


common_files = list(COMMON.rglob('*.java'))
ability_files = []
for pkg in ABILITY_PACKAGES:
    path = COMMON / pkg
    if path.exists():
        ability_files += list(path.rglob('*.java'))

bad = scan(common_files, COMMON_FORBIDDEN) + scan(ability_files, ABILITY_FORBIDDEN)
if bad:
    print('Platform boundary violations:')
    for file, line, text in bad:
        print(f'{file}:{line}: {text}')
    sys.exit(1)
print(
    f'OK: scanned {len(common_files)} common files and {len(ability_files)} ability files; no forbidden platform imports/calls found.')
