# lt_pep_parliament – Seimas (Lituanie)

Site officiel `https://www.lrs.lt` : la page « Seimo nariai » (`/sip/portal.show?p_r=35299&p_k=1`) liste les membres de la législature 2024-2028 avec leur groupe ; la fiche de chacun (`p_asm_id`) donne la naissance.

- Paramètre `details` : `true` (défaut, une requête par membre, naissance et lieu) ou `false` (une seule requête, sans naissance).
- **Le `robots.txt` impose 20 secondes entre deux requêtes** et interdit `/pls/` : plancher de 20 s imposé dans le code, seules les pages `/sip/portal.show` sont lues. Avec les 140 fiches : environ 47 minutes, à ne pas raccourcir.
- La date de naissance est écrite « 1972 m. vasario 13 d. » (mois au génitif lituanien) : convertie en ISO.
- Pas de dates de mandat ; pas d'anciens membres.
- Gouvernement, présidence, Banque de Lituanie, Cour suprême, Cour constitutionnelle, Commission électorale : HTTP 403 (Cloudflare), non contournés. `apps.lrs.lt` (données ouvertes) : connexion impossible depuis notre poste.
