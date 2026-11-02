# be_pep_rca – Membres de la famille des PEP belges (Wikidata)

## Ce que fait ce parser

Il interroge Wikidata pour trouver les PEP belges vivants, puis leurs proches vivants au sens de l'**article 4, 29°, de la loi du 18 septembre 2017** :

- conjoint ou équivalent ;
- enfants et leurs conjoints ;
- parents.

**Les frères et sœurs ne sont pas repris** (ils le sont au Luxembourg, qui suit l'AMLR). C'est une reprise de [`fr_pep_rca`](../../france/swiftcheck-aml-fr-pep-rca).

Mesure du 02/11/2026 : 1 321 PEP belges dans Wikidata, dont 63 avec des proches connus ; 74 proches, 75 liens familiaux, 58 photos Wikimedia Commons sur 111 (environ 10 s).

## Contexte

| | |
|---|---|
| Catégorie PEP | **BE-RCA** – Membre de la famille d'une PEP |
| Couverture | Partielle : seules les familles décrites dans Wikidata |
| Rétention | 12 mois (gérée par le core) |

## Quelles PEP ?

Le parser travaille avec une **liste autorisée** de fonctions de l'Annexe IV décrites dans Wikidata (QID relevés le 02/11/2026) :

| Paramètre | Fonctions |
|---|---|
| `positions` | Roi des Belges (Q13592862), Premier ministre (Q213107), vice-Premier ministre (Q87885285), membre de la Chambre (Q15705021), sénateur (Q17619252), membres des parlements flamand (Q15105064), wallon (Q19351455) et bruxellois (Q33108580), ministres-présidents de Flandre (Q2746377), de Wallonie (Q3100220), de la Région de Bruxelles-Capitale (Q435627) et de la Communauté germanophone (Q2326814), gouverneur de la Banque nationale (Q100530029) |
| `positionClasses` | toute fonction de juridiction Belgique (Q31) qui est une sorte de « ministre » (Q83307), soit les membres du gouvernement fédéral |

Pas encore dans la liste, faute de QID trouvé : membres des parlements de la Fédération Wallonie-Bruxelles et de la Communauté germanophone, juges de la Cour constitutionnelle, ministres des gouvernements régionaux (sauf les ministres-présidents). Pour en ajouter, il suffit de compléter le paramètre `positions`, sans recompiler.

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `be_pep_rca` |
| Plugin-Id | `be-pep-rca` |
| Type de source | `PLUGIN_MANAGED` (SPARQL) |
| Paramètres | `{"endpoint": "https://query.wikidata.org/sparql", "yearsAfterEnd": "1", "country": "Q31", "positions": "Q13592862 …", "positionClasses": "Q83307"}` |

## Ce qui est produit (FtM)

Pour chaque lien, trois entités : la PEP (`Person`, `role.pep`), le proche (`Person`, `role.rca`) et le lien `Family`. Les identifiants reprennent le QID Wikidata. Seules les PEP qui ont au moins un proche sont écrites.

## Limites connues

- **Données collaboratives** : Wikidata peut contenir des erreurs ou des oublis, à contrôler.
- Les proches associés (art. 4, 30°) ne sont pas décrits dans Wikidata.

## Tests

`BePepRcaParserTest` : la conversion est la même que celle de `fr_pep_rca`, et elle est testée sur des réponses Wikidata réelles (extrait du 28/10/2026). Les tests couvrent les liens familiaux sans la fratrie et la requête en liste autorisée.
