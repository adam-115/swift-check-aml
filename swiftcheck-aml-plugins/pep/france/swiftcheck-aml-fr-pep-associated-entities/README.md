# fr_pep_associated_entities – Organisations liées aux PEP (France, HATVP)

## Ce que fait ce parser

Il lit les **déclarations d'intérêts** publiées par la HATVP et produit, pour chaque parlementaire, membre du Gouvernement et député européen :
- les **organismes qu'il dirige** (conseil d'administration, présidence, comité…) ;
- les **sociétés dans lesquelles il détient une participation significative**.

Même modèle que [`lu_pep_associated_entities`](../../luxembourg/swiftcheck-aml-lu-pep-associated-entities) (déclarations des députés luxembourgeois).

Mesure du 28/10/2026 : 825 déclarations de PEP (une par personne), 1 563 organismes liés à 393 PEP (1 461 organes dirigeants, 230 participations significatives ; 795 petites participations écartées), 0 valeur rejetée, environ 2 s.

## Contexte

| | |
|---|---|
| Catégorie PEP | **FR-RCA** « Membres de la famille et proches associés » (CMF, art. R. 561-18, III) |
| Fonction couverte | Proche associé d'une PEP : **partielle** (organismes et participations déclarés par la PEP elle-même) |
| Rétention | 12 mois (rétention gérée par le core) |

## Source

| | |
|---|---|
| Éditeur | Haute Autorité pour la transparence de la vie publique |
| Fichier | `https://www.hatvp.fr/livraison/merge/declarations.xml` (≈ 80 Mo, XML) |
| Licence | Licence Ouverte / Open Licence (Etalab 2.0) |

Pour chaque déclarant, seule sa **dernière déclaration d'intérêts** (`DI` ou `DIA`) est lue. Sections reprises :

| Section | Contenu | Règle |
|---|---|---|
| `participationDirigeantDto` | organisme (`nomSociete`), rôle (`activite`), début et fin (`MM/AAAA`) | en cours, ou terminé depuis moins de `monthsAfterEnd` mois |
| `participationFinanciereDto` | société, valeur (`evaluation`), capital détenu (`capitalDetenu`, %), nombre de parts | significative : capital ≥ `minCapitalPercent` % **ou** valeur ≥ `minValue` € |

Mesures sur le fichier réel : la valeur médiane d'une participation est d'environ 4 000 € (souvent quelques actions cotées, ex. « 20 actions REXEL ») ; 397 participations dépassent 100 000 €.

## Configuration dans SwiftCheck (écran « Parsers »)

| Champ | Valeur |
|---|---|
| Nom du dataset | `fr_pep_associated_entities` |
| Plugin-Id | `fr-pep-associated-entities` |
| Type de source | `HTTP_FILE` |
| Nature de la source | T7 – Registre de déclarations |
| Adresse | `https://www.hatvp.fr/livraison/merge/declarations.xml` |
| Fréquence | 168 h |
| Paramètres | `{"mandateTypes": "depute senateur gouvernement europe", "minCapitalPercent": "10", "minValue": "100000", "monthsAfterEnd": "12"}` |

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | nom + prénom + date de naissance | la PEP : nom, date de naissance, genre, pays `fr`, mandat déclaré, topic `role.pep` |
| `Organization` | nom de l'organisme (sans casse) | nom, topic `role.rca`, « Déclaré à la HATVP par … » ; un organisme cité par plusieurs PEP n'est écrit qu'une fois |
| `Directorship` | PEP + organisme | rôle, début, fin |
| `Ownership` | PEP + organisme | pourcentage, nombre de parts, valeur (EUR) |

## Limites connues

- Les noms masqués par la HATVP (« SCI [Données non publiées] ») ne sont pas repris.
- Les noms d'organismes sont saisis librement par les déclarants : un même organisme peut apparaître sous plusieurs orthographes.
- Les élus locaux déclarent aussi à la HATVP ; ils ne sont pas lus (ils ne sont pas PEP aujourd'hui).
- Une PEP qui a quitté ses fonctions reste dans le fichier tant que la HATVP la publie ; ses organismes restent donc publiés (approche prudente).

## Tests

`FrPepAssociatedEntitiesParserTest`, sur un extrait réel du fichier (quatre déclarations, 27/10/2026) : dernière déclaration par personne, élu local ignoré, organes dirigeants terminés écartés, seuils des participations, noms masqués, `readSourceRecords`.
