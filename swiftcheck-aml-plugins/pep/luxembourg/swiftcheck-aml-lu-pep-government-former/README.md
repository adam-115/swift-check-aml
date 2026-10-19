# lu_pep_government_former – Anciens membres du gouvernement (Luxembourg)

## Ce que fait ce parser

Il lit la page officielle des **anciens membres du gouvernement** luxembourgeois (`anciens-membres-gouvernement.html`) et extrait l'ensemble des personnalités ayant exercé des fonctions gouvernementales (Premier ministre, ministres, secrétaires d'État), ainsi que le détail de chacun de leurs mandats et portefeuilles ministériels avec leurs dates d'effet.

Il convertit ces données en entités FollowTheMoney (FtM).

## Contexte

| | |
|---|---|
| Pays | Luxembourg |
| Catégorie PEP | **LU-a** « Chef de l'État, Premier ministre, ministres et secrétaires d'État » (directive (UE) 2015/849, art. 3, point 9 a) |
| Liste officielle | JO C/2023/724 |
| Fonction couverte | Ancien membre du gouvernement (nommé selon l'article 76 de la Constitution) |
| Classe de risque | 1 (la plus élevée) |
| Rétention | 12 mois légale au Luxembourg (la page fournit les dates d'exercice exactes de chaque mandat). |

## Source

| | |
|---|---|
| Éditeur | Gouvernement du Grand-Duché de Luxembourg |
| Adresse | `https://gouvernement.lu/fr/gouvernement/anciens-membres-gouvernement.html` |
| Format | Page HTML officielle (accordéon sous « Par membres du gouvernement ») |
| Typologie | T5 – Page web officielle |
| Licence | Citer « gouvernement.lu » |

La page présente un accordéon des membres sous la section « Par membres du gouvernement » :

```html
<details class="cmp-accordion__item">
  <summary class="cmp-accordion__summary">
    <h3 class="cmp-accordion__header">ARENDT Guy</h3>
  </summary>
  <div class="cmp-accordion__panel">
    <ul>
      <li><a ...>Secrétaire d’État à la Culture, du 18.12.2015 au 05.12.2018</a></li>
    </ul>
  </div>
</details>
```

Le parser extrait le nom de famille (en majuscules) et le prénom, ainsi que chaque mandat ministériel avec ses dates de début et de fin.

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `lu_pep_government_former` (ou `lu_pep_old_government_former`) |
| Plugin-Id | `lu-pep-government-former` |
| Type de source | `HTTP_FILE` (le core télécharge la page, le plugin la convertit) |
| Nature de la source | T5 – Page web officielle |
| URL de la source | `https://gouvernement.lu/fr/gouvernement/anciens-membres-gouvernement.html` |
| Fréquence | 24 h |
| Rétention | 12 mois |
| Paramètres | `{"yearsAfterEnd": "5"}` (par défaut 5 ans, `0` pour conserver tous les mandats) |
| Catégorie couverte | LU-a (Ancien membre du gouvernement) |

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | `lu-pep-government-former-<slug>`, ex. `lu-pep-government-former-guy-arendt` | nom complet, prénom, nom, pays `lu`, topic `role.pep`, `sourceUrl` |
| `Position` | `lu-pep-government-former-<slug-fonction>` | Intitulé officiel de la fonction ministérielle, topics `gov.national`, `gov.executive` |
| `Occupancy` | `<person-slug>-<function-slug>-<date>` | `holder`, `post`, `startDate`, `endDate`, statut `ended` (ou `current`) |

## Règles et contrôles qualité

- Découpe robuste des noms : les mots en majuscules forment le nom de famille.
- Parsing des dates multi-formats : format numérique `DD.MM.YYYY` et format en toutes lettres avec noms de mois français (ex. `17 novembre 2023`).
- Prise en charge des mandats actuels (`depuis le...` ou `le...`) et des mandats terminés (`du... au...`).
- Filtrage configurable par `yearsAfterEnd`.
- Arrêt en erreur franche (`IllegalStateException`) si aucun membre n'est détecté dans la page, interdisant toute publication de dataset vide.
