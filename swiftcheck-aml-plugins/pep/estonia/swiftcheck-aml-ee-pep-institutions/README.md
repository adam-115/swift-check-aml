# ee_pep_institutions – Président, Cour suprême, Riigikontroll, Banque d'Estonie

Cinq pages officielles, choisies par le paramètre `keys` :

- `president` : `president.ee/en/head-state/biography` (nom, naissance et lieu, prise de fonction) ;
- `supreme-court` : `riigikohus.ee/en/about-supreme-court/organisation/justices` (18 juges, chambres ; naissance du président seulement) ;
- `auditor-general` : `riigikontroll.ee/en/about-us/general/auditor-general` ;
- `eesti-pank-executive` : directoire (mois de prise de fonction, date partielle `AAAA-MM`) ;
- `eesti-pank-supervisory` : conseil de surveillance (président, membres du Riigikogu, experts).

- Chancelier de justice non repris : poste vacant (la titulaire est devenue présidente le 12/10/2026), la page ne donne que l'intérim.
- Les expressions de lecture sont ancrées ligne par ligne : le titre de la page et le fil d'Ariane contiennent aussi « Auditor General » et fausseraient une recherche sur le texte entier (bug trouvé au run réel).
- 2 secondes entre pages, User-Agent générique ; aucun des cinq sites n'impose de délai.
