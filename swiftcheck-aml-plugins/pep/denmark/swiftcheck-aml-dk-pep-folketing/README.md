# dk_pep_* – Folketing (Danemark)

API ouverte `https://oda.ft.dk/api/Aktør` (OData, JSON), personnes de type 5 : ≈ 3 400 entités, 2 020 avec fiche biographique XML (35 pages de 100).

- Paramètre `scope` : `parliament` (députés, président du Folketing), `government` (ministres), `oversight` (Statsrevisorer). `monthsAfterEnd` : 12 (défaut) ou 600 pour l'historique.
- Cinq jeux, un seul plugin : `dk_pep_parliament`, `dk_pep_parliament_history`, `dk_pep_government`, `dk_pep_government_history`, `dk_pep_oversight`.
- Naissance complète ; dates de mandat lues dans les lignes de carrière (« Libellé, 9. august 2013 – 28. juni 2015. » ou « … fra 3. juni 2026. »).
- Limites : chefs de parti (années seulement, liste incomplète), suppléants temporaires, ministres sans fiche, hautes cours, Banque nationale.
