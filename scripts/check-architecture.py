#!/usr/bin/env python3
from pathlib import Path
import re
root = Path(__file__).resolve().parents[1]
errors = []
for module in ('contracts', 'ordering-core', 'fulfillment-core'):
    for path in (root / module / 'src/main/java').rglob('*.java'):
        text = path.read_text()
        if re.search(r'import (org\.springframework|software\.amazon|com\.amazonaws|com\.fasterxml)', text):
            errors.append(str(path))
        if module == 'ordering-core' and '.commerce.fulfillment.' in text:
            errors.append(str(path))
        if module == 'fulfillment-core' and '.commerce.ordering.' in text:
            errors.append(str(path))
        if '/domain/' in str(path) and re.search(r'import .*\.(application|adapter|platform|contracts)\.', text):
            errors.append(str(path))
if errors:
    raise SystemExit('Architecture violations: ' + ', '.join(errors))
print('PASS: source dependency rules across core modules')
