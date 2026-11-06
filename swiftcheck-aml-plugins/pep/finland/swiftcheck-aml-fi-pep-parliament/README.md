# fi_pep_parliament / fi_pep_government – Eduskunta (Finlande)

Députés et ministres finlandais d'après l'API ouverte `https://avoindata.eduskunta.fi/api/v1/tables/MemberOfParliament/rows` (≈ 2 700 fiches depuis 1907, 27 pages de 100).

- Paramètre `scope` : `parliament` (mandats de député) ou `government` (fonctions ministérielles). `monthsAfterEnd` : 12 (défaut) ou 600 pour l'historique.
- Quatre jeux, un seul plugin : `fi_pep_parliament`, `fi_pep_parliament_history`, `fi_pep_government`, `fi_pep_government_history`.
- Dates exactes de mandat ; naissance : année seulement. Décédés écartés.
- Limite : les ministres qui n'ont jamais été députés ne figurent pas dans la source (valtioneuvosto.fi est protégé par Cloudflare et n'est pas utilisé).
