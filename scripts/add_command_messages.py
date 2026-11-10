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


english = yaml.safe_load(open('src/main/resources/messages_en.yml', encoding='utf-8'))
for key, value in {
    'command.language-players-only': '<red>Only players can choose an interface language.',
    'command.language-usage': '<yellow>Usage: /shop language <en|de>',
    'command.language-unknown': '<red>Unknown language: <white><language></white>. Use en or de.',
    'command.language-set': '<green>Language changed to <white><language></white>.',
    'admin.maintenance-usage': '<red>Usage: /shopadmin maintenance <on|off|toggle>',
    'admin.maintenance-set': '<green>Maintenance mode is now <white><state></white>.',
    'admin.maintenance-state-on': '<red>ON',
    'admin.maintenance-state-off': '<green>OFF',
    'admin.signs-refreshed': '<green>Shop anchors scanned and sign text refreshed.',
}.items():
    put(english, key, value)
save('src/main/resources/messages_en.yml', english,
     '# BarterShop English messages (complete defaults).\n'
     '# Server-specific overrides belong in messages.yml.\n\n')

german = yaml.safe_load(open('src/main/resources/messages_de.yml', encoding='utf-8'))
for key, value in {
    'command.language-players-only': '<red>Nur Spieler können eine Oberflächensprache wählen.',
    'command.language-usage': '<yellow>Verwendung: /shop language <en|de>',
    'command.language-unknown': '<red>Unbekannte Sprache: <white><language></white>. Nutze en oder de.',
    'command.language-set': '<green>Sprache auf <white><language></white> geändert.',
    'admin.maintenance-usage': '<red>Verwendung: /shopadmin maintenance <on|off|toggle>',
    'admin.maintenance-set': '<green>Der Wartungsmodus ist jetzt <white><state></white>.',
    'admin.maintenance-state-on': '<red>AN',
    'admin.maintenance-state-off': '<green>AUS',
    'admin.signs-refreshed': '<green>Shop-Anker geprüft und Schildtexte aktualisiert.',
}.items():
    put(german, key, value)
save('src/main/resources/messages_de.yml', german,
     '# BarterShop – vollständige deutsche Nachrichten.\n'
     '# Serverspezifische Änderungen gehören in diese Datei.\n\n')
print('ok')
