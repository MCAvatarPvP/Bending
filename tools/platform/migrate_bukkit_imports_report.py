#!/usr/bin/env python3
"""Reports platform-native imports that still need object-model migration."""
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "src" / "com" / "projectkorra" / "projectkorra"
counts = Counter()
files = defaultdict(list)
for path in SRC.rglob("*.java"):
    if "/platform/bukkit/" in path.as_posix():
        continue
    for line in path.read_text(errors="ignore").splitlines():
        if line.startswith("import org.bukkit") or line.startswith("import net.md_5") or line.startswith(
                "import br.net") or line.startswith("import io.lumine"):
            imp = line.split()[1].rstrip(';')
            counts[imp] += 1
            files[imp].append(str(path.relative_to(ROOT)))

print("Remaining native imports outside platform/bukkit:")
print(f"  imports: {sum(counts.values())}")
print(f"  unique:  {len(counts)}")
print()
for imp, n in counts.most_common():
    print(f"{n:4d} {imp}")
