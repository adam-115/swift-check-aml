import re, uuid
path = 'd:/swiftcheck-aml/swiftcheck-aml/swiftcheck-aml-core/back/src/main/resources/reports/aml_report.jrxml'
with open(path, 'r', encoding='utf-8') as f:
    text = f.read()

def repl(m):
    val = m.group(1)
    try:
        uuid.UUID(val)
        return 'uuid="' + val + '"'
    except ValueError:
        return 'uuid="' + str(uuid.uuid4()) + '"'

new_text = re.sub(r'uuid="([^"]+)"', repl, text)

with open(path, 'w', encoding='utf-8') as f:
    f.write(new_text)
print('Done!')
