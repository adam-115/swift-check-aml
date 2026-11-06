# se_pep_* – Riksdag (Suède)

Un seul appel à l'API ouverte `https://data.riksdagen.se/personlista/?utformat=json&rdlstatus=samtliga` (≈ 19 Mo, 3 524 personnes avec leurs missions datées).

- Paramètre `scope` : `parliament` (députés, présidence du Riksdag), `government` (ministres et département), `parties` (chefs de parti, porte-parole), `oversight` (Riksrevisionen, médiateurs JO). `monthsAfterEnd` : 12 (défaut) ou 600 pour l'historique.
- Six jeux, un seul plugin : `se_pep_parliament`, `se_pep_parliament_history`, `se_pep_government`, `se_pep_government_history`, `se_pep_parties`, `se_pep_oversight`.
- Dates exactes de mandat ; naissance : année seulement (absente pour les non-députés). Une fin de mission dans le futur = mission en cours.
- Limites : secrétaires d'État, hautes cours et direction de la Riksbank absents de la source.
