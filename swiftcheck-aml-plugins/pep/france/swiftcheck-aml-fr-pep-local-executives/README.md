# fr_pep_local_executives – Exécutifs des collectivités de 50 000 habitants ou plus (France, AMLR)

## Ce que fait ce parser

Il croise le **Répertoire national des élus** (RNE) et les **populations municipales Insee** pour produire les dirigeants des collectivités d'au moins 50 000 habitants : **maires**, **présidents d'intercommunalité**, **de conseil départemental**, **de conseil régional** et **des collectivités à statut particulier**. Ces fonctions deviennent PEP le **10 juillet 2027** (règlement (UE) 2024/1624, AMLR) ; aujourd'hui, les élus locaux ne sont pas PEP en France.

Mesure du 28/10/2026 : 134 maires, 266 présidents d'intercommunalité, 93 présidents de conseil départemental, 14 présidents de conseil régional, 12 présidents de collectivité à statut particulier ; 455 personnes (un maire peut aussi présider son intercommunalité), toutes avec leur date de naissance.

## Contexte

| | |
|---|---|
| Catégorie PEP | **FR-AMLR** (classe 3) |
| Fonction couverte | Maire ou président d'une intercommunalité, d'un département ou d'une région de 50 000 habitants ou plus |
| Rétention | 12 mois (rétention gérée par le core) |

Seul le chef de l'exécutif est repris : pas les adjoints, vice-présidents ni conseillers.

## Sources

| Fichier | Adresse (paramètre) | Contenu |
|---|---|---|
| Populations | `populationsUrl` : `https://geo.api.gouv.fr/communes?fields=code,nom,population,codeDepartement,codeRegion,codeEpci&format=json` | population municipale Insee de chaque commune, avec son département, sa région et son intercommunalité |
| Maires | `mayorsUrl` (RNE `elus-maires-mai.csv`) | code de la commune, nom, prénom, sexe, naissance, début de la fonction |
| Intercommunalités | `epciUrl` (RNE `elus-conseillers-communautaires-epci.csv`) | SIREN de l'EPCI, fonction « Président du conseil communautaire » |
| Départements | `departmentsUrl` (RNE `elus-conseillers-departementaux-cd.csv`) | fonction « Président du conseil départemental » |
| Régions | `regionsUrl` (RNE `elus-conseillers-regionaux-cr.csv`) | fonction « Président du conseil régional » |
| Statut particulier | `specialUrl` (RNE `elus-membres-assemblee-ma.csv`) | « Président de l'assemblée », « Président de l'organe exécutif » |

Le RNE (ministère de l'Intérieur, Licence ouverte) est publié sur data.gouv.fr et mis à jour chaque trimestre ; les adresses `https://www.data.gouv.fr/api/1/datasets/r/<id>` désignent toujours la dernière version.

**Population d'une collectivité** = somme des populations de ses communes. Collectivités à statut particulier : rattachées à la région du même code (Corse « 94 », collectivités d'outre-mer « 975 », « 987 »…) ou au département (Martinique « 972 », Guyane « 973 ») ; la Métropole de Lyon (« 69M ») à son EPCI (200046977).

## Configuration dans SwiftCheck (écran « Parsers »)

| Champ | Valeur |
|---|---|
| Nom du dataset | `fr_pep_local_executives` |
| Plugin-Id | `fr-pep-local-executives` |
| Type de source | `PLUGIN_MANAGED` (six fichiers) |
| Fréquence | 168 h |
| Paramètres | les six adresses ci-dessus et `minPopulation` (50000) |

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | nom + prénom + date de naissance | nom, prénom, date de naissance, genre, pays `fr`, fonctions (« Maire – Lyon »), topic `role.pep` |
| `Position` | fonction + code de la collectivité | « Maire – Lyon », collectivité (`subnationalArea`), population, topics `gov.muni` (commune, EPCI) ou `gov.state` (département, région, statut particulier) et `gov.executive` |
| `Occupancy` | personne + fonction + collectivité | début de la fonction, statut `current` |

## Limites connues

- **Nouvelle-Calédonie** : le RNE ne distingue pas le président du Congrès de ceux des trois provinces ; tous sont repris, avec la population du territoire (la province des Îles Loyauté a moins de 50 000 habitants).
- Après les municipales de mars 2026, quelques présidents d'intercommunalité manquent encore dans le RNE (mises à jour en cours d'après le ministère).

## Tests

`FrPepLocalExecutivesParserTest`, sur des extraits réels du RNE (11/08/2026) et des populations : seuil, présidents seulement, collectivités à statut particulier, paramètre `minPopulation`.
