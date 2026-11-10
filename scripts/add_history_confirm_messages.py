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
for key, value in {
    'gui.confirm.action': '<green>Confirm',
    'gui.history.type.trade': '<white>Trade',
    'gui.history.type.collect': '<gold>Collection',
    'gui.history.type.stock': '<yellow>Stock change',
    'gui.history.type.recovery': '<aqua>Recovery',
    'gui.history.type.delete': '<red>Deletion',
    'gui.history.type.transfer': '<light_purple>Ownership transfer',
    'gui.history.type.config': '<yellow>Configuration',
    'gui.history.type.other': '<white>Activity',
}.items():
    put(en, key, value)
save('src/main/resources/messages_en.yml', en,
     '# BarterShop English messages (complete defaults).\n'
     '# Server-specific overrides belong in messages.yml.\n\n')

de = yaml.safe_load(open('src/main/resources/messages_de.yml', encoding='utf-8'))
for key, value in {
    'gui.confirm.action': '<green>Bestätigen',
    'gui.history.type.trade': '<white>Tausch',
    'gui.history.type.collect': '<gold>Abholung',
    'gui.history.type.stock': '<yellow>Bestandsänderung',
    'gui.history.type.recovery': '<aqua>Wiederherstellung',
    'gui.history.type.delete': '<red>Löschung',
    'gui.history.type.transfer': '<light_purple>Eigentümerwechsel',
    'gui.history.type.config': '<yellow>Konfiguration',
    'gui.history.type.other': '<white>Aktivität',
}.items():
    put(de, key, value)
save('src/main/resources/messages_de.yml', de,
     '# BarterShop – vollständige deutsche Nachrichten.\n'
     '# Serverspezifische Änderungen gehören in diese Datei.\n\n')
print('ok')
