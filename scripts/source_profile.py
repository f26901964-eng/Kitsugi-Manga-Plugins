"""Active single-source release policy (separate from the retired Gölge app patch)."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def read_profile(path: Path = ROOT / 'sources.properties') -> dict[str, str]:
    profile = {}
    for raw in path.read_text(encoding='utf-8').splitlines():
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        name, separator, value = line.partition('=')
        if not separator or name.strip() in profile:
            raise ValueError('Invalid or duplicate source profile key')
        profile[name.strip()] = value.strip()
    expected = {'activeSource': 'mangadenizi', 'keiyoushi.enabled': 'tr:mangadenizi', 'kotatsu.enabled': '', 'kitsugi.adapter': 'mex'}
    if profile != expected:
        raise ValueError('Only the native MangaDenizi MEX recovery source may be active')
    return profile
