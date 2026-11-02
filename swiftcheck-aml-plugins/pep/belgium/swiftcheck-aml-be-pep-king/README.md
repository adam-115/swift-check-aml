# be_pep_king – Le Roi des Belges

## Ce que fait ce parser

Il produit le **Roi des Belges** à partir de deux sources, croisées à chaque exécution :

| Source | Ce qu'elle apporte |
|---|---|
| monarchie.be, page « Le Roi » (Palais royal) | la naissance, qui fait foi : « Sa Majesté le Roi est né à Bruxelles le 15 avril 1960 » |
| Wikidata, titulaire actuel de « Roi des Belges » (Q13592862) | le nom (« Philippe de Belgique ») et la date d'accession (21/07/2013) |

La page officielle ne donne pas le nom dans un champ lisible. Wikidata est donc contrôlé par elle : **le prénom et la date de naissance trouvés dans Wikidata doivent correspondre à la page**. Sinon, l'exécution s'arrête. C'est ce qui se passerait lors d'un changement de règne pas encore saisi partout, ou d'une modification de la page. Elle s'arrête aussi si Wikidata ne donne pas exactement un Roi en fonction.

Mesure du 02/11/2026 : le roi Philippe, né à Bruxelles le 15/04/1960, en fonction depuis le 21/07/2013. Wikidata indique Laeken comme lieu de naissance ; c'est la page officielle qui fait foi.

## Contexte

| | |
|---|---|
| Catégorie PEP | **BE-a** – Roi des Belges (loi du 18/09/2017, Annexe IV, 1°, a) |
| Rétention | 12 mois (gérée par le core) |

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `be_pep_king` |
| Plugin-Id | `be-pep-king` |
| Type de source | `PLUGIN_MANAGED` |
| Paramètres | `{"pageUrl": "https://www.monarchie.be/fr/famille-royale/le-roi", "endpoint": "https://query.wikidata.org/sparql"}` |

## Ce qui est produit (FtM)

`Person` (ID : QID Wikidata `Q155004`, alias « Roi Philippe », date et lieu de naissance, `wikidataId`), `Position` « Roi des Belges » (topics `gov.head`, `gov.national`), `Occupancy` (début 2013-07-21, statut `current`).

**Pas de photo** : l'image de Wikidata est une photo de visite officielle, pas un portrait seul (règle : jamais de photo de groupe).

## Tests

`BePepKingParserTest`, sur la page et la réponse Wikidata réelles (02/11/2026). Les tests couvrent :

- le croisement des deux sources ;
- l'arrêt si elles ne concordent pas ;
- l'arrêt s'il n'y a pas un seul Roi en fonction.
