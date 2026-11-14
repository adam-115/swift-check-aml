# Test de présence de PEP français : SwiftCheck AML et OpenSanctions

12 novembre 2026. Petit test à la main, pas une mesure statistique.

**Méthode.** Pour chaque personne, on cherche son nom dans nos datasets publiés (`fr_pep_*`, normalisation sans accents ni casse) et dans la recherche publique d'OpenSanctions limitée aux PEP (`opensanctions.org/search`, données du 12/11/2026). Un nom trouvé ne prouve pas la bonne fonction ni le bon statut : on ne mesure ici que la présence.

## Résultats

| Échantillon | Taille | SwiftCheck AML | OpenSanctions |
|---|---|---|---|
| A. Personnalités connues, choisies par moi (gouvernement, parlementaires, partis, magistrats, chefs d'état-major, UE, élus locaux) | 30 | 27 (90 %) | 29 (97 %), dont 1 douteuse |
| B. Membres du Conseil constitutionnel (sauf Ferrand, déjà en A) | 8 | 8 | 8 |
| C. Tirage au hasard dans nos datasets (4 ambassadeurs, 4 dirigeants d'entreprises publiques, 2 magistrats de la Cour de cassation) | 10 | 10 (par construction) | 7 (70 %), dont 1 par variante d'orthographe |

## Personnes manquantes

**Chez nous (3 sur 30 en A)**
- François Villeroy de Galhau (Banque de France) : absent du Conseil général publié, qui contient son successeur. À vérifier : sa sortie date-t-elle de moins de 12 mois, et la règle de rétention s'applique-t-elle ?
- Anne Hidalgo (ancienne maire de Paris) : même question.
- Mathias Cormann (OCDE, Paris) : réellement manquant, l'OCDE n'est pas dans la configuration des organisations internationales (FR-h).

**Chez OpenSanctions**
- Christophe Soulard, premier président de la Cour de cassation : non trouvé (un homonyme partiel, Jean-François Soulard).
- Marc Guillaume, vice-président du Conseil d'État : deux fiches, l'une issue de « French Mayors » (un homonyme) et l'autre de Wikidata ; l'identité n'est pas confirmée.
- Dans l'échantillon C : Corinne Fau (présidente de LFB), Renée-Michèle Ott (Cour de cassation), Savinien Grignon-Dumoulin (Cour de cassation).

## Ce que le test montre

- Sur les personnalités connues, OpenSanctions est au même niveau, voire un peu devant : Wikidata couvre bien les noms célèbres, y compris les membres du Conseil constitutionnel et plusieurs ambassadeurs.
- Sur les fonctions moins visibles (magistrats, dirigeants d'entreprises publiques), nous trouvons des personnes qu'OpenSanctions ne connaît pas.
- Les fiches d'OpenSanctions n'indiquent presque jamais la fonction exacte ni la date de fin ; chez nous chaque personne vient d'une source officielle citée.
- La correction à apporter à la comparaison précédente : au niveau des noms, OpenSanctions connaît les membres du Conseil constitutionnel (via Wikidata) ; le mot « absent » désignait l'absence d'un jeu de données officiel dédié.

## Limites

- Les échantillons sont petits et choisis à la main : A est biaisé vers les personnes célèbres, C vers nos propres données. On ne sait pas combien de PEP d'OpenSanctions manquent chez nous, car cela demande de tirer au hasard dans leurs données.
- Les correspondances d'OpenSanctions se font sur le nom ; je n'ai pas confirmé l'identité des fiches (Olivier Faure : quatre fiches homonymes).
- Villeroy de Galhau et Hidalgo : les dates de fin de fonctions ne sont pas vérifiées.
