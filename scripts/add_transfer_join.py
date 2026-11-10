import re
import yaml


def put(d, path, value):
    parts = path.split('.')
    cur = d
    for part in parts[:-1]:
        cur = cur.setdefault(part, {})
    cur[parts[-1]] = value


def save(path, data, header):
    serialized = yaml.dump(data, allow_unicode=True, sort_keys=False, width=1000,
                           default_flow_style=False)
    serialized = re.sub(r'^(\s*)true:', r"\1'yes':", serialized, flags=re.M)
    serialized = re.sub(r'^(\s*)false:', r"\1'no':", serialized, flags=re.M)
    open(path, 'w', encoding='utf-8').write(header + serialized)


en = yaml.safe_load(open('src/main/resources/messages_en.yml', encoding='utf-8'))
put(en, 'transfer.pending-join', '<gold>You have <white><count></white> pending shop transfer request(s). Open /shop and choose Transfer requests.')
save('src/main/resources/messages_en.yml', en,
     '# BarterShop English messages (complete defaults).\n'
     '# Server-specific overrides belong in messages.yml.\n\n')

de = yaml.safe_load(open('src/main/resources/messages_de.yml', encoding='utf-8'))
put(de, 'transfer.pending-join', '<gold>Du hast <white><count></white> offene Shop-Übertragungsanfrage(n). Öffne /shop und wähle Übertragungsanfragen.')
save('src/main/resources/messages_de.yml', de,
     '# BarterShop – vollständige deutsche Nachrichten.\n'
     '# Serverspezifische Änderungen gehören in diese Datei.\n\n')
print('ok')
