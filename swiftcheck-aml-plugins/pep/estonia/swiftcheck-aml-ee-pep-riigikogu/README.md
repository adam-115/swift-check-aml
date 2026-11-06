# ee_pep_parliament – Riigikogu (Estonie)

API officielle `https://api.riigikogu.ee/api` : `/memberships` (compositions depuis 1992), `/plenary-members?lang=et&status=ALL&membership=N` (tous les membres d'une composition : naissance, **décès**, sexe, début et fin de mandat, fonction, groupe, circonscription).

- `monthsAfterEnd` : 12 (défaut) ou 600 pour l'historique (toutes les compositions, 9 appels).
- La liste est demandée en **estonien** : en anglais, l'API masque le nom des anciens membres (453 entrées sans nom sur 1 248).
- L'`uuid` d'un membre est stable d'une composition à l'autre : une seule personne, un mandat par composition.
- Les personnes décédées ne sont pas publiées.
- Rythme : `robots.txt` = 5 s entre deux requêtes ; l'API répond 429 plus vite. Délai 6 s (plancher 5 s), trois reprises après un 429. Exécution historique : environ 70 s.
