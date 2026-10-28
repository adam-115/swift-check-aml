# fr_pep_banque_de_france – Conseil général de la Banque de France

## Ce que fait ce parser

Il lit la page de gouvernance de banque-france.fr et produit les **membres du Conseil général de la Banque de France**, avec leur rôle (gouverneur, sous-gouverneurs, représentant du personnel…).

Mesure du 28/10/2026 : 11 membres, dont le gouverneur Emmanuel Moulin (depuis le 2 juin 2026).

## Contexte

| | |
|---|---|
| Pays | France |
| Catégorie PEP | **FR-e** « Cour des comptes et Conseil général de la Banque de France » (CMF, art. L. 142-7 et R. 561-18 ; directive (UE) 2015/849, art. 3, point 9 e) |
| Fonction couverte | Membre du Conseil général de la Banque de France |
| Classe de risque | 2 |
| Rétention | 12 mois (rétention gérée par le core) |

## Source

| | |
|---|---|
| Éditeur | Banque de France |
| Page | `https://www.banque-france.fr/fr/banque-de-france/gouvernance-forte-et-transparente/gouvernance-comites-consultatifs` |
| Format | page HTML (≈ 1 Mo) ; la section est dans un accordéon « Les organes délibératifs et de contrôle » |

```html
<h3>Le Conseil général</h3> …
<h4>Membres</h4>
<p>Emmanuel Moulin, gouverneur, président du Conseil général<br>
Denis Beau, premier sous-gouverneur<br> … Anne-Laure Delatte<br> …</p>
<h4>Censeur</h4> …
```

## Configuration dans SwiftCheck (écran « Parsers »)

| Champ | Valeur |
|---|---|
| Nom du dataset | `fr_pep_banque_de_france` |
| Plugin-Id | `fr-pep-banque-de-france` |
| Type de source | `HTTP_FILE` |
| Nature de la source | T5 – Page web officielle |
| Adresse | voir ci-dessus |
| Fréquence | 168 h |
| Paramètres | aucun |

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | `fr-pep-banque-de-france-<nom>` | nom, prénom, pays `fr`, fonctions (dont le rôle, ex. « Premier sous-gouverneur (Banque de France) »), topic `role.pep` |
| `Position` | `…-general-council` | « Membre du Conseil général de la Banque de France », topics `gov.national`, `gov.financial` |
| `Occupancy` | nom + `general-council` | statut `current`, rôle |

## Règles et erreurs

- Seule la liste « Membres » est reprise : le censeur et son suppléant assistent aux séances sans être membres.
- Section introuvable ou vide : l'exécution s'arrête (FAILED).

## Limites connues

- Ni date de naissance ni date de nomination sur la page. Les nominations sont publiées au Journal officiel (ex. décret du 10 juin 2026 nommant Mme Fabienne Lebreton).
- L'ID repose sur le nom : une faute corrigée sur la page crée une nouvelle entité.

## Tests

`FrPepBanqueDeFranceParserTest`, sur la page réelle (octobre 2026).

## Construire

```bash
mvn -pl swiftcheck-aml-plugins/pep/france/swiftcheck-aml-fr-pep-banque-de-france -am package
```
