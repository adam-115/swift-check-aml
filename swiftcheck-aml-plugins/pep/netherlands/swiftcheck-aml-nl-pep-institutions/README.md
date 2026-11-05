# nl-pep-institutions

Dataset `nl_pep_institutions` : Raad van State, Hoge Raad et Algemene Rekenkamer (catégories NL-d et NL-e).

**Type** : `PLUGIN_MANAGED`. Paramètre `sources` (« clé|Institution|adresse », séparées par des points-virgules) ; la clé choisit
l'extracteur :

| Clé | Page | Contenu lu |
|---|---|---|
| `raad_van_state` | `raadvanstate.nl/overrvs/organisatie/leden-staatsraden/` | Vice-President, Lid, Staatsraad… ; prénom entre parenthèses |
| `hoge_raad` | `hogeraad.nl/hoge-raad/kamers/` | président, vice-présidents, raadsheren ; initiales seulement |
| `algemene_rekenkamer` | `rekenkamer.nl/over-de-algemene-rekenkamer/organisatie/college` | phrase « bestaat momenteel uit … » |

**Résultat réel (11/2026)** : 115 membres (Raad van State 75, Hoge Raad 37, Algemene Rekenkamer 3). Ni dates ni naissance ; seuls les
membres en fonction sont nommés. De Nederlandsche Bank répond 403 : non couverte, non contournée.
