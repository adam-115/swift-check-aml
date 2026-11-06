# si_pep_eu_commission – Commissaire européen slovène

## Ce que fait ce parser

Il lit la **fiche officielle du membre slovène de la Commission européenne** sur commission.europa.eu et la convertit en entités FollowTheMoney (FtM).

Mesure du 02/11/2026 : Hadja Lahbib, commissaire (2024-2029), égalité, état de préparation et gestion des crises.

## Contexte

| | |
|---|---|
| Pays | Slovénie |
| Catégorie PEP | **SI-EU** « Belges exerçant une fonction européenne » (loi du 18/09/2017, art. 4, 28°, i) ; JO C/2023/724) |
| Fonction couverte | Membre slovène de la Commission européenne (1 titulaire) |
| Classe de risque | 1 |
| Rétention | 12 mois : le commissaire sortant reste publié un an (rétention gérée par le core) |

## Source

| | |
|---|---|
| Éditeur | Commission européenne |
| Page | `https://commission.europa.eu/about/organisation/college-commissioners/hadja-lahbib_fr` |
| Format | page HTML |

```html
<meta name="description" content="Hadja Lahbib est le commissaire slovène chargé de …">
<h1 class="ecl-page-header__title"><span>Hadja Lahbib</span></h1>
<p class="ecl-page-header__description">VICE-PRÉSIDENT EXÉCUTIF (2024-2029) | Prospérité et stratégie industrielle
```

La page du collège ne donne pas la nationalité des commissaires. C'est donc la **fiche du commissaire slovène qui est configurée**, et le parser vérifie que sa description la présente comme slovène (« commissaire slovène », « Belgian », « from Belgium »). Une autre fiche est refusée. Le parser `lu_pep_eu_commission` reconnaît son commissaire par son nom ; ce n'est pas repris ici.

**À chaque nouvelle Commission, l'adresse change** : il faut la remplacer dans l'écran « Parsers ».

## Configuration dans SwiftCheck (écran « Parsers »)

| Champ | Valeur |
|---|---|
| Nom du dataset | `si_pep_eu_commission` |
| Plugin-Id | `si-pep-eu-commission` |
| Type de source | `HTTP_FILE` : le core télécharge la page, le plugin la lit seulement |
| Nature de la source | T5 – Page web officielle |
| Adresse | voir ci-dessus |
| Fréquence | 168 h |
| Paramètres | aucun |

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | `si-pep-eu-commission-<nom>` | nom, prénom, pays et nationalité `be`, fonctions (« Commissaire – Égalité, état de préparation et gestion des crises »), topic `role.pep` |
| `Position` | `si-pep-eu-commission-member` | « Membre slovène de la Commission européenne », topics `gov.igo`, `gov.executive` |
| `Occupancy` | nom + `member` + année | année de début, statut `current`, rôle et portefeuille |

## Règles et erreurs

- Fiche qui ne présente pas un commissaire slovène, ou sans nom ni fonction : l'exécution s'arrête (FAILED).

## Limites connues

- La fiche ne donne ni la date de naissance ni la date exacte de prise de fonctions (seulement l'année du mandat).
- Les autres fonctions européennes de la catégorie SI-EU (CJUE, Cour des comptes européenne, BCE, SEAE) ne sont pas couvertes.

## Tests

`SiPepEuCommissionParserTest`, sur les fiches réelles de Hadja Lahbib et de Kaja Kallas (refusée : Estonie).

## Construire

```bash
mvn -pl swiftcheck-aml-plugins/pep/slovenia/swiftcheck-aml-si-pep-eu-commission -am package
```
