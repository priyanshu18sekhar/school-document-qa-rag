"""Pretty-prints the document list returned by GET /api/v1/documents.

Kept as a file rather than inlined in load.sh: quoting a Python f-string inside
a bash heredoc inside a shell function is a reliable way to produce a script
that works on one machine and not the next.
"""

import json
import sys

page = json.load(sys.stdin)

print(f'{"TITLE":<40} {"CATEGORY":<10} {"STATUS":<10} CHUNKS')
print("-" * 72)

for document in page.get("items", []):
    title = str(document.get("title", ""))[:39]
    category = str(document.get("category"))
    status = str(document.get("status"))
    chunks = document.get("chunkCount", 0)
    print(f"{title:<40} {category:<10} {status:<10} {chunks}")
    if document.get("errorMessage"):
        print(f"    ! {document['errorMessage']}")

print()
print(f'{page.get("totalElements", 0)} document(s)')
