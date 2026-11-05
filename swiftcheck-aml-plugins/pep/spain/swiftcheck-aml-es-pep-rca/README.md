# es_pep_rca – Famille des PEP (Wikidata)

## Ce que fait ce parser

Reprise de `be_pep_rca` : les PEP espagnoles décrites dans Wikidata (monarque, président et vice-président du Gouvernement, députés, sénateurs, magistrats du Tribunal constitutionnel, président du Tribunal suprême, gouverneur de la Banque d'Espagne, ministres) et leur famille au sens de l'article 14.4 (conjoint, enfants et leurs conjoints, parents ; pas la fratrie). Rétention de 2 ans.

## Contexte

| | |
|---|---|
| Bloc du référentiel | **ES-RCA** (loi 10/2010, art. 14 ; liste de la Commission, JO C/2023/724) |
| Source | Wikidata (SPARQL) |
| Rétention | 24 mois après la fin des fonctions (loi 10/2010, art. 14.9) |

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `es_pep_rca` |
| Plugin-Id | `es-pep-rca` |
| Type de source | PLUGIN_MANAGED (SPARQL) |
| Fréquence | 168 h |
| Paramètres | `{"endpoint":"https://query.wikidata.org/sparql","yearsAfterEnd":"2","country":"Q29","positions":"Q3847454 Q844587 Q3113035 Q18171345 Q19323171 Q17014422 Q64624734 Q28518041 Q29934477 Q6352222","positionClasses":"Q83307"}` |

Données brutes archivées : `peps.json` et `relatives.json` (réponses SPARQL).

## Ce qui est produit (FtM)

`Person` pour la PEP et pour chaque proche (`role.rca`), `Family` avec la nature du lien.

## Limites

- Seules les familles décrites dans Wikidata. Données collaboratives, à contrôler.

## Tests

`EsPepRcaParserTest` sur une réponse réelle (anciens présidents du Gouvernement et leurs proches).

Construire : `mvn -pl swiftcheck-aml-plugins/pep/spain/swiftcheck-aml-es-pep-rca -am package`.
