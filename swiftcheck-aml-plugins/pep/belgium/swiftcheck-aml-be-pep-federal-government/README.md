# be_pep_federal_government – Membres du gouvernement fédéral (Belgique)

## Ce que fait ce parser

Il lit la page **« Composition et répartition des compétences du gouvernement fédéral »** de belgium.be. Pour chaque membre, il en tire :

- la civilité (qui donne le genre) ;
- le nom ;
- la fonction exacte ;
- la page personnelle ;
- le portrait officiel.

Mesure du 02/11/2026 : les 15 membres du gouvernement De Wever, du Premier ministre (« Monsieur Bart De Wever, Premier ministre ») à la ministre des Classes moyennes.

## Contexte

| | |
|---|---|
| Catégorie PEP | **BE-a** – Premier ministre, vice-Premiers ministres, ministres et secrétaires d'État fédéraux (loi du 18/09/2017, Annexe IV, 1°, b) |
| Source | belgium.be (SPF Chancellerie), page HTML |
| Rétention | 12 mois (gérée par le core) |

premier.be refuse les clients automatiques (HTTP 403, « Access Denied ») : il n'est pas utilisé, et ce refus n'est pas contourné.

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `be_pep_federal_government` |
| Plugin-Id | `be-pep-federal-government` |
| Type de source | `PLUGIN_MANAGED` (page web) |
| Fréquence | 24 h (un remaniement doit être vu vite) |
| Paramètre | `{"url": "https://www.belgium.be/fr/la_belgique/pouvoirs_publics/autorites_federales/gouvernement_federal/composition_gouvernement"}` |

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | nom | nom, prénom (premier mot), nom de famille, genre, pays `be`, fonction, page personnelle, topic `role.pep` ; portrait officiel (« © Belgium.be ») |
| `Position` | fonction | « Premier ministre », « Vice-premier ministre et ministre de l'Emploi… », topics `gov.national`, `gov.executive` |
| `Occupancy` | nom + fonction | statut `current` |

## Limites connues

- Ni date de naissance ni date d'entrée en fonction sur la page.
- Les anciens membres ne sont pas couverts (lot 3 : Moniteur belge).
- Le prénom est le premier mot du nom.

## Tests

`BePepFederalGovernmentParserTest`, sur la page réelle du 02/11/2026 : les 15 membres, leurs fonctions et leurs portraits, et l'arrêt si la page ne liste plus personne.
