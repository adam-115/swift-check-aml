# pl_pep_parliament / pl_pep_clubs – Diète (Pologne)

API officielle `https://api.sejm.gov.pl/sejm` : `/term` (législatures), `/term{n}/MP` (députés), `/term{n}/clubs` (groupes).

- Paramètre `scope` : `parliament` (députés) ou `clubs` (présidents de groupes). `monthsAfterEnd` : 12 (défaut) ou 180 pour l'historique.
- Une personne = prénom + nom + naissance (l'identifiant d'un député change à chaque législature).
- Le décès des anciens députés n'est pas publié : seuls les décès enregistrés pendant la législature (`Zgon`) sont écartés.
- Le Sénat, la Cour suprême, la Présidence et la Banque nationale refusent les requêtes automatiques (non contournés).
