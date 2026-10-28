# fr_pep_constitutional_council – Conseil constitutionnel (France)

## Ce que fait ce parser

Il lit la page « Les membres » de conseil-constitutionnel.fr, puis la fiche de chaque membre, et produit le **président et les 9 membres nommés** avec leur date de naissance et leur date de prestation de serment.

Mesure du 28/10/2026 : 9 membres (président : Richard Ferrand), tous avec leur date de naissance.

## Contexte

| | |
|---|---|
| Pays | France |
| Catégorie PEP | **FR-d** « Conseil constitutionnel, Conseil d'État et Cour de cassation » (CMF, art. R. 561-18 ; directive (UE) 2015/849, art. 3, point 9 d) |
| Fonction couverte | Président, membre ou secrétaire général du Conseil constitutionnel : **partielle** (pas le secrétaire général) |
| Classe de risque | 2 |
| Rétention | 12 mois : un membre qui quitte le Conseil reste publié un an (rétention gérée par le core) |

Le secrétaire général n'est pas nommé sur le site ; il est nommé par décret publié au Journal officiel.

## Source

| | |
|---|---|
| Éditeur | Conseil constitutionnel |
| Pages | `https://www.conseil-constitutionnel.fr/le-conseil-constitutionnel/les-membres`, puis `/membres/<nom>` |
| Format | pages HTML |

```html
<a href="/membres/richard-ferrand" rel="bookmark" title="Richard FERRAND"> …
  <div class="title">Richard FERRAND</div>
  <span class="president-conseil">Président du Conseil Constitutionnel</span>
  <div class="field field--name-field-texte-nomination …">Nommé le 20 février 2025 par le Président de la République</div>
```

Fiche : « A prêté serment le 7 mars 2025 … », « Né le 1<sup>er</sup> juillet 1962 à Rodez (Aveyron) ».

## Configuration dans SwiftCheck (écran « Parsers »)

| Champ | Valeur |
|---|---|
| Nom du dataset | `fr_pep_constitutional_council` |
| Plugin-Id | `fr-pep-constitutional-council` |
| Type de source | `PLUGIN_MANAGED` : la page des membres puis 9 fiches, pause de 500 ms |
| Nature de la source | T5 – Page web officielle |
| Fréquence | 168 h |
| Paramètres | `{"membersUrl": "https://www.conseil-constitutionnel.fr/le-conseil-constitutionnel/les-membres", "delayMs": "500"}` |

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | `fr-pep-constitutional-council-<fiche>` (ex. `richard-ferrand`) | nom (« Richard FERRAND »), prénom, nom de famille, date et lieu de naissance, pays et nationalité `fr`, fonctions, topic `role.pep` |
| `Position` | `…-member` et `…-president` | « Membre du Conseil constitutionnel », « Président du Conseil constitutionnel », topics `gov.national`, `gov.judicial` |
| `Occupancy` | fiche + fonction | début = prestation de serment (à défaut, nomination), statut `current`, autorité de nomination |

## Règles et erreurs

- Chaque membre apparaît deux fois sur la page (composition et placement protocolaire) : il n'est écrit qu'une fois.
- Les anciens membres listés plus bas sur la page ne sont pas repris.
- Fiche illisible : le membre est gardé avec la date de nomination, sans naissance ; la fiche est citée dans le message.
- Page sans aucun membre ou paramètre manquant : l'exécution s'arrête (FAILED).

## Tests

`FrPepConstitutionalCouncilParserTest`, sur la page réelle des membres et la fiche de Richard Ferrand (octobre 2026).

## Construire

```bash
mvn -pl swiftcheck-aml-plugins/pep/france/swiftcheck-aml-fr-pep-constitutional-council -am package
```
