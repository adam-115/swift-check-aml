# cz_pep_* – Chambre des députés (Tchéquie)

Archive de données ouvertes `https://www.psp.cz/eknih/cdrom/opendata/poslanci.zip` (≈ 420 Ko, une requête) : fichiers `.unl` (séparateur `|`, windows-1250) `osoby`, `organy`, `funkce`, `zarazeni`.

- Paramètre `scope` : `parliament`, `government`, `president`, `oversight`. `monthsAfterEnd` : 12 (défaut) ou 600 pour l'historique.
- Six jeux, un seul plugin : `cz_pep_parliament`, `cz_pep_parliament_history`, `cz_pep_government`, `cz_pep_government_history`, `cz_pep_president`, `cz_pep_oversight`.
- Dates exactes de mandat et de fonction ; naissance complète (« 01.01.1900 » = inconnue, non publiée). Décédés écartés.
- Limites : Sénat, hautes juridictions, banque centrale, ambassadeurs, partis non couverts.
