# bitchat legacy

Messagerie mesh décentralisée pour **Android 4.0+** (minSdk 14), pensée pour les
appareils que les clients bitchat modernes laissent de côté.

Testé cible principale : **Samsung Galaxy XCover 3 (SM-G388F), Android 5.1.1**.

Aucun compte, aucun numéro de téléphone, aucun serveur central.

## Pourquoi pas Bluetooth Low Energy ?

Le vrai bitchat s'appuie sur le rôle périphérique BLE (chaque téléphone émet
son advertising tout en scannant). Sur Android :

| Android | API | Scan BLE | Advertising |
|---|---|---|---|
| 4.3 – 4.4 | 18–19 | oui | **non, inexistant** |
| 5.0 – 5.1 | 21–22 | oui | instable, souvent exclusif du scan |
| 8.0+ | 26+ | oui | oui, stable |

Un mesh automatique est donc impossible en BLE sur Android 4/5.

## Ce que fait cette version

Deux transports, exactement la même logique de routage que bitchat :

1. **Bluetooth Classic RFCOMM en mode *insecure***
   `listenUsingInsecureRfcommWithServiceRecord()` et
   `createInsecureRfcommSocketToServiceRecord()` existent depuis l'API 10 et
   connectent **sans appairage ni dialogue utilisateur**. C'est ce qui rend le
   mesh automatique possible.
2. **UDP broadcast** sur le réseau local — utile quand tous les appareils sont
   sur le même hotspot Wi-Fi : découverte instantanée, aucune limite de liens.

Le routeur (`MeshService`) reproduit le comportement de bitchat :

- **inondation** de chaque paquet vers tous les liens sauf celui d'origine ;
- **TTL de 7 sauts**, décrémenté à chaque relais ;
- **déduplication LRU** (2048 identifiants) pour casser les boucles ;
- **annonces de présence** toutes les 20 s, avec pseudo et clé publique ;
- **messages privés chiffrés** de bout en bout.

## Chiffrement

ECDH **P-256** + **HKDF-SHA256** + **AES-CTR** + **HMAC-SHA256** en
encrypt-then-MAC. Tout est disponible dès l'API 11, donc aucun besoin de
SpongyCastle et un APK minuscule — ce qui compte sur un SoC de 2013.

AES/GCM n'existe qu'à partir de l'API 19, d'où la construction manuelle.

L'identité réseau est les 16 premiers octets du SHA-256 de la clé publique
publique, comme bitchat dérive son identifiant de sa clé d'identité.

## Commandes

```
/msg <pseudo> <texte>   message privé chiffré
/nick <pseudo>          changer de pseudo
/who                    lister les pairs
/slap [cible]           clin d'œil IRC
/id                     afficher son identifiant réseau
/clear                  vider le journal
/help                   aide
```

**Triple-tap sur le journal** : effacement d'urgence (identité, pairs,
historique), comme bitchat.

## Compiler

Le dépôt n'inclut pas de wrapper Gradle. En local, avec Gradle 7.5+ et le
JDK 11 :

```bash
gradle assembleDebug
```

L'APK sort dans `app/build/outputs/apk/debug/`.

**Ou laissez la CI le faire** : le workflow `.github/workflows/build.yml`
compile l'APK à chaque push sur `main` et le publie dans les artefacts du run.
Aucune configuration de signature n'est nécessaire pour un APK debug.

## Installer sur l'appareil

1. Copiez l'APK sur le téléphone (câble, carte SD, Bluetooth).
2. Paramètres → Sécurité → **Sources inconnues**.
3. Ouvrez l'APK et installez.

`targetSdkVersion 22` a un avantage concret : **aucune permission runtime**. Le
système accorde tout à l'installation, il n'y a pas de boîte de dialogue à
gérer.

## Limites à connaître

- Ce réseau est **séparé** de celui des clients bitchat officiels : le
transport est différent (RFCOMM + UDP au lieu de BLE). Deux appareils sous
`bitchat-legacy` se voient entre eux, pas les clients modernes.
- Android 4.3/4.4 (API 18–19) n'ont pas de BLE advertising, mais RFCOMM y
fonctionne : cette version les couvre donc, contrairement à l'approche BLE.
- Le Bluetooth Classic est plus gourmand en énergie que le BLE. Sur un XCover 3,
attendez-vous à une consommation notable en usage continu.
- Le code n'a **pas été compilé ni testé sur appareil** au moment de sa rédaction.
Prévoyez des ajustements de compilation.

## Licence

Domaine public, comme le bitchat original. Voir [LICENSE](LICENSE).
