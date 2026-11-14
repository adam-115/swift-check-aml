# Inventaire des dépendances à Wikipedia et à Wikidata

État au 12 novembre 2026. Source : lecture du code des plugins (appels à `wikipedia.org/w/api.php` et `query.wikidata.org`) et de la base `swift_check_2` (lecture seule).

**74 plugins sur 286** dépendent de Wikipedia ou de Wikidata.

| Pays | Plugins | Via Wikidata | Via Wikipedia |
|---|---|---|---|
| Belgique | 4 | 4 | 0 |
| France | 3 | 3 | 0 |
| Allemagne | 3 | 3 | 0 |
| Grèce | 11 | 4 | 7 |
| Italie | 6 | 4 | 2 |
| Luxembourg | 2 | 2 | 0 |
| Pays-Bas | 5 | 4 | 1 |
| Pologne | 9 | 2 | 7 |
| Portugal | 10 | 2 | 8 |
| Roumanie | 10 | 1 | 9 |
| Espagne | 11 | 10 | 1 |

Légende des difficultés : **Facile** = une page officielle stable ; **Moyenne** = plusieurs pages ou un fichier à traiter ; **Difficile** = beaucoup de sites ou de pages hétérogènes ; **Non remplaçable** = aucune source officielle n'existe.

Les sources officielles proposées sont des candidates : aucune n'a encore été ouverte ni vérifiée, à part celles déjà converties dans ce projet.

## Belgique

| Plugin | Fonction couverte | Personnes publiées | Dépendance actuelle | Source officielle candidate | Difficulté |
|---|---|---|---|---|---|
| `be-pep-king` | BE-a:Roi des Belges (complète) | 1 | Wikidata (SPARQL) | Site officiel de la présidence ou de la maison royale | Facile |
| `be-pep-local-executives` | BE-AMLR:Bourgmestre, échevin, président de CPAS ou dé (partielle) ; BE-AMLR:Bourgmestre ou dirigeant d'une collectivité d (complète) ; BE-AMLR:Échevin, président de CPAS ou député provinci (complète) | 670 / 44 | Wikidata (SPARQL) | Données ouvertes officielles des élections locales (ministère de l'Intérieur, bureau de statistique, commission électorale) | Moyenne |
| `be-pep-political-parties` | BE-c:Membre de la direction, du conseil politique, (complète) | 20 | Wikidata (SPARQL) | Sites officiels des partis (page « direction / bureau / comité exécutif ») ; registre ou commission électorale du pays en complément | Moyenne |
| `be-pep-rca` | BE-RCA:Membre de la famille d'une PEP (partielle) | 111 | Wikidata (SPARQL) | Aucune source officielle ne liste les proches d'une PEP | Non remplaçable |

## France

| Plugin | Fonction couverte | Personnes publiées | Dépendance actuelle | Source officielle candidate | Difficulté |
|---|---|---|---|---|---|
| `fr-pep-political-parties` | FR-c:Président ou membre de l'organe exécutif d'un (complète) | 137 | Wikidata (SPARQL) | Sites officiels des partis (page « direction / bureau / comité exécutif ») ; registre ou commission électorale du pays en complément | Moyenne |
| `fr-pep-rca` | FR-RCA:Membre de la famille d'une PEP (partielle) | 241 | Wikidata (SPARQL) | Aucune source officielle ne liste les proches d'une PEP | Non remplaçable |
| `fr-pep-wikidata-positions` | FR-EU:Français exerçant une autre fonction PEP dans (complète) | 2 | Wikidata (SPARQL) | Institutions de l'UE : déjà couvert par fr_pep_eu_institutions | Facile |

## Allemagne

| Plugin | Fonction couverte | Personnes publiées | Dépendance actuelle | Source officielle candidate | Difficulté |
|---|---|---|---|---|---|
| `de-pep-local-executives` | DE-AMLR:Maire ou premier magistrat d'une commune de 5 (complète) ; DE-AMLR:Président de conseil d'arrondissement d'au mo (complète) | 206 | Wikidata (SPARQL) | Données ouvertes officielles des élections locales (ministère de l'Intérieur, bureau de statistique, commission électorale) | Moyenne |
| `de-pep-parties` | DE-c:Membre de la direction fédérale d'un parti po (complète) | 220 | Wikidata (SPARQL) | Sites officiels des partis (page « direction / bureau / comité exécutif ») ; registre ou commission électorale du pays en complément | Moyenne |
| `de-pep-rca` | DE-RCA:Membre de la famille d'une PEP allemande (partielle) | 202 | Wikidata (SPARQL) | Aucune source officielle ne liste les proches d'une PEP | Non remplaçable |

## Grèce

| Plugin | Fonction couverte | Personnes publiées | Dépendance actuelle | Source officielle candidate | Difficulté |
|---|---|---|---|---|---|
| `gr-pep-armed-forces` | GR-f:Officier général des forces armées (partielle) | jamais exécuté | Wikipedia el | Site du ministère de la Défense / de l'état-major (page « commandement ») | Facile |
| `gr-pep-central-bank` | GR-e:Membre du conseil de la Banque de Grèce (partielle) | jamais exécuté | Wikidata (SPARQL) | Site de la banque centrale (page « organes / conseil ») | Facile |
| `gr-pep-diplomats` | GR-f:Ambassadeur (partielle) | jamais exécuté | Wikidata (SPARQL) | Annuaire des représentations du ministère des Affaires étrangères | Moyenne |
| `gr-pep-government` | GR-a:Premier ministre, ministres et vice-ministres (partielle) | jamais exécuté | Wikipedia en : Second_cabinet_of_Kyriakos_Mitsotakis | Site officiel du gouvernement (composition) ; pour les régions, site de chaque gouvernement régional | Facile à moyenne |
| `gr-pep-international-orgs` | GR-h:Dirigeant d'une organisation internationale (partielle) | jamais exécuté | Wikipedia en | Site de chaque organisation (page « direction ») | Difficile |
| `gr-pep-parliament` | GR-b:Ancien député du Parlement hellénique (partielle) ; GR-b:Député du Parlement hellénique (partielle) | jamais exécuté | Wikipedia en | Site officiel du Parlement (annuaire des élus) | Facile |
| `gr-pep-political-parties` | GR-c:Membre d'un organe dirigeant d'un parti (partielle) | jamais exécuté | Wikipedia en | Sites officiels des partis (page « direction / bureau / comité exécutif ») ; registre ou commission électorale du pays en complément | Moyenne |
| `gr-pep-president` | GR-a:Président de la République (partielle) | jamais exécuté | Wikidata (SPARQL) | Site officiel de la présidence ou de la maison royale | Facile |
| `gr-pep-rca` | GR-RCA:Membre de la famille d'une PEP grecque (partielle) ; GR-RCA:Proche associé d'une PEP grecque (partielle) | jamais exécuté | Wikidata (SPARQL) | Aucune source officielle ne liste les proches d'une PEP | Non remplaçable |
| `gr-pep-state-enterprises` | GR-g:Dirigeant d'une entreprise détenue par l'État (partielle) | jamais exécuté | Wikipedia en | Registre de l'actionnaire d'État (ministère des Finances ou agence de participations) puis site de chaque entreprise | Difficile |
| `gr-pep-supreme-courts` | GR-d:Juge de la Cour de cassation ou du Conseil d' (partielle) | jamais exécuté | Wikipedia en | Site officiel de la juridiction (page « composition / juges ») | Facile |

## Italie

| Plugin | Fonction couverte | Personnes publiées | Dépendance actuelle | Source officielle candidate | Difficulté |
|---|---|---|---|---|---|
| `it-pep-constitutional-court` | IT-1.4:Juge de la Cour constitutionnelle (complète) | 16 | Wikidata (SPARQL) | Site officiel de la juridiction (page « composition / juges ») | Facile |
| `it-pep-diplomats` | IT-1.6:Ambassadeur ou chargé d'affaires (partielle) | 20 | Wikipedia it | Annuaire des représentations du ministère des Affaires étrangères | Moyenne |
| `it-pep-health-directors` | IT-1.8:Directeur général d'ASL, d'hôpital ou d'entit (partielle) | 30 | Wikipedia it | Sites des agences régionales de santé et des hôpitaux | Difficile |
| `it-pep-high-courts` | IT-1.4:Magistrat de la Cour de cassation, de la Cour (partielle) | 13 | Wikidata (SPARQL) | Site officiel de la juridiction (page « composition / juges ») | Facile |
| `it-pep-political-parties` | IT-1.3:Membre d'un organe central de direction d'un  (complète) | 60 | Wikidata (SPARQL) | Sites officiels des partis (page « direction / bureau / comité exécutif ») ; registre ou commission électorale du pays en complément | Moyenne |
| `it-pep-state-enterprises` | IT-1.7:Organe d'une société contrôlée par l'État, un (partielle) | 25 | Wikidata (SPARQL) | Registre de l'actionnaire d'État (ministère des Finances ou agence de participations) puis site de chaque entreprise | Difficile |

## Luxembourg

| Plugin | Fonction couverte | Personnes publiées | Dépendance actuelle | Source officielle candidate | Difficulté |
|---|---|---|---|---|---|
| `lu-pep-political-parties` | LU-c:Membre d'un organe dirigeant de parti politiq (complète) | 13 | Wikidata (SPARQL) | Sites officiels des partis (page « direction / bureau / comité exécutif ») ; registre ou commission électorale du pays en complément | Moyenne |
| `lu-pep-rca` | LU-RCA:Membre de la famille d'une PEP (partielle) | 77 | Wikidata (SPARQL) | Aucune source officielle ne liste les proches d'une PEP | Non remplaçable |

## Pays-Bas

| Plugin | Fonction couverte | Personnes publiées | Dépendance actuelle | Source officielle candidate | Difficulté |
|---|---|---|---|---|---|
| `nl-pep-central-bank` | NL-e:Membre de la direction de De Nederlandsche Ba (partielle) | 6 | Wikidata (SPARQL) | Site de la banque centrale (page « organes / conseil ») | Facile |
| `nl-pep-diplomats` | NL-f:Ambassadeur ou chargé d'affaires (partielle) | 12 | Wikipedia nl | Annuaire des représentations du ministère des Affaires étrangères | Moyenne |
| `nl-pep-political-parties` | NL-c:Membre d'un organe dirigeant d'un parti (partielle) | 12 | Wikidata (SPARQL) | Sites officiels des partis (page « direction / bureau / comité exécutif ») ; registre ou commission électorale du pays en complément | Moyenne |
| `nl-pep-rca` | NL-RCA:Membre de la famille d'une PEP néerlandaise (partielle) ; NL-RCA:Proche associé d'une PEP néerlandaise (partielle) | 8 | Wikidata (SPARQL) | Aucune source officielle ne liste les proches d'une PEP | Non remplaçable |
| `nl-pep-state-enterprises` | NL-g:Organe dirigeant d'une entreprise publique (partielle) | 10 | Wikidata (SPARQL) | Registre de l'actionnaire d'État (ministère des Finances ou agence de participations) puis site de chaque entreprise | Difficile |

## Pologne

| Plugin | Fonction couverte | Personnes publiées | Dépendance actuelle | Source officielle candidate | Difficulté |
|---|---|---|---|---|---|
| `pl-pep-central-bank` | PL-e:Membre du directoire de la Banque nationale d (partielle) | 14 | Wikipedia pl | Site de la banque centrale (page « organes / conseil ») | Facile |
| `pl-pep-constitutional-tribunal` | PL-d:Juge du Tribunal constitutionnel (partielle) | 13 | Wikipedia pl | Site officiel de la juridiction (page « composition / juges ») | Facile |
| `pl-pep-international-orgs` | PL-h:Dirigeant d'une organisation internationale (partielle) | 6 | Wikipedia en : Council_of_the_Baltic_Sea_States | Site de chaque organisation (page « direction ») | Difficile |
| `pl-pep-political-parties` | PL-c:Membre d'un organe dirigeant d'un parti (partielle) | 15 | Wikidata (SPARQL) | Sites officiels des partis (page « direction / bureau / comité exécutif ») ; registre ou commission électorale du pays en complément | Moyenne |
| `pl-pep-president` | PL-a:Président de la République (partielle) | 8 | Wikipedia pl | Site officiel de la présidence ou de la maison royale | Facile |
| `pl-pep-rca` | PL-RCA:Membre de la famille d'une PEP polonaise (partielle) ; PL-RCA:Proche associé d'une PEP polonaise (partielle) | 10 | Wikidata (SPARQL) | Aucune source officielle ne liste les proches d'une PEP | Non remplaçable |
| `pl-pep-senat` | PL-b:Sénateur (partielle) | 16 | Wikipedia pl : Senatorowie_XI_kadencji_Senatu_Rzeczypospolitej_Polskiej | Site officiel du Parlement (annuaire des élus) | Facile |
| `pl-pep-state-enterprises` | PL-g:Dirigeant d'une entreprise détenue par l'État (partielle) | 12 | Wikipedia pl | Registre de l'actionnaire d'État (ministère des Finances ou agence de participations) puis site de chaque entreprise | Difficile |
| `pl-pep-supreme-court` | PL-d:Juge de la Cour suprême (partielle) | 12 | Wikipedia pl | Site officiel de la juridiction (page « composition / juges ») | Facile |

## Portugal

| Plugin | Fonction couverte | Personnes publiées | Dépendance actuelle | Source officielle candidate | Difficulté |
|---|---|---|---|---|---|
| `pt-pep-armed-forces` | PT-e:Officier général des forces armées et de la G (partielle) | 8 | Wikipedia pt : Marinha_Portuguesa, Guarda_Nacional_Republicana | Site du ministère de la Défense / de l'état-major (page « commandement ») | Facile |
| `pt-pep-autonomous-regions` | PT-REG:Représentant de la République et membres des  (partielle) | 14 | Wikipedia pt : XVI_Governo_Regional_da_Madeira | Site officiel du gouvernement (composition) ; pour les régions, site de chaque gouvernement régional | Facile à moyenne |
| `pt-pep-bank-of-portugal` | PT-d:Organes de la Banque du Portugal (partielle) | 8 | Wikipedia pt : Banco_de_Portugal | Site de la banque centrale (page « organes / conseil ») | Facile |
| `pt-pep-diplomats` | PT-e:Chef de mission diplomatique ou de poste cons (partielle) | 14 | Wikipedia pt | Annuaire des représentations du ministère des Affaires étrangères | Moyenne |
| `pt-pep-financial-regulators` | PT-d:Organes de la CMVM, de l'ASF et de la CNPD (partielle) | 9 | Wikipedia pt | Sites des régulateurs (CMVM, ASF, CNPD pour le Portugal) | Facile |
| `pt-pep-international-orgs` | PT-h:Dirigeant d'une organisation internationale é (partielle) | 6 | Wikipedia pt | Site de chaque organisation (page « direction ») | Difficile |
| `pt-pep-mayors` | PT-LOC:Maire (Presidente de Câmara Municipal) et adj (partielle) | 20 | Wikidata (SPARQL) | Données ouvertes officielles des élections locales (ministère de l'Intérieur, bureau de statistique, commission électorale) | Moyenne |
| `pt-pep-political-parties` | PT-g:Membre de l'organe exécutif d'un parti politi (partielle) | 13 | Wikidata (SPARQL) | Sites officiels des partis (page « direction / bureau / comité exécutif ») ; registre ou commission électorale du pays en complément | Moyenne |
| `pt-pep-rca` | PT-RCA:Membre de la famille d'une PEP portugaise (partielle) ; PT-RCA:Proche associé d'une PEP portugaise (partielle) | 10 | Wikipedia pt : Marcelo_Rebelo_de_Sousa | Aucune source officielle ne liste les proches d'une PEP | Non remplaçable |
| `pt-pep-state-enterprises` | PT-f:Dirigeant d'un institut public, d'une fondati (partielle) | 12 | Wikipedia pt | Registre de l'actionnaire d'État (ministère des Finances ou agence de participations) puis site de chaque entreprise | Difficile |

## Roumanie

| Plugin | Fonction couverte | Personnes publiées | Dépendance actuelle | Source officielle candidate | Difficulté |
|---|---|---|---|---|---|
| `ro-pep-constitutional-court` | RO-d:Juge de la Cour suprême ou constitutionnelle (partielle) | 9 | Wikipedia en : Constitutional_Court_of_Romania | Site officiel de la juridiction (page « composition / juges ») | Facile |
| `ro-pep-court-of-accounts` | RO-e:Membre de la direction de la banque centrale  (partielle) | 9 | Wikipedia ro | Site officiel de la juridiction (page « composition / juges ») | Facile |
| `ro-pep-deputies` | RO-b:Parlementaire (partielle) | 20 | Wikipedia ro | Site officiel du Parlement (annuaire des élus) | Facile |
| `ro-pep-diplomats` | RO-f:Ambassadeur (partielle) | 10 | Wikidata (SPARQL) | Annuaire des représentations du ministère des Affaires étrangères | Moyenne |
| `ro-pep-international-orgs` | RO-h:Dirigeant d'une organisation internationale (partielle) | 6 | Wikipedia en | Site de chaque organisation (page « direction ») | Difficile |
| `ro-pep-political-parties` | RO-c:Membre d'un organe dirigeant d'un parti (partielle) | 14 | Wikipedia ro | Sites officiels des partis (page « direction / bureau / comité exécutif ») ; registre ou commission électorale du pays en complément | Moyenne |
| `ro-pep-president` | RO-a:Président de la République (partielle) | 8 | Wikipedia en | Site officiel de la présidence ou de la maison royale | Facile |
| `ro-pep-rca` | RO-RCA:Membre de la famille d'une PEP (partielle) ; RO-RCA:Proche associé d'une PEP (partielle) | 9 | Wikipedia ro : Klaus_Iohannis, Marcel_Ciolacu | Aucune source officielle ne liste les proches d'une PEP | Non remplaçable |
| `ro-pep-state-enterprises` | RO-g:Dirigeant d'une entreprise détenue par l'État (partielle) | 8 | Wikipedia ro | Registre de l'actionnaire d'État (ministère des Finances ou agence de participations) puis site de chaque entreprise | Difficile |
| `ro-pep-supreme-court` | RO-d:Juge de la Cour suprême ou constitutionnelle (partielle) | 8 | Wikipedia ro | Site officiel de la juridiction (page « composition / juges ») | Facile |

## Espagne

| Plugin | Fonction couverte | Personnes publiées | Dépendance actuelle | Source officielle candidate | Difficulté |
|---|---|---|---|---|---|
| `es-pep-army` | ES-e:Chef d'état-major ou officier général (généra (partielle) | 12 | Wikidata (SPARQL) | Site du ministère de la Défense / de l'état-major (page « commandement ») | Facile |
| `es-pep-diplomats` | ES-e:Ambassadeur ou chef de mission (Embajador) (partielle) | 15 | Wikipedia es | Annuaire des représentations du ministère des Affaires étrangères | Moyenne |
| `es-pep-international-orgs` | ES-i:Dirigeant d'une organisation internationale é (partielle) | 11 | Wikidata (SPARQL) | Site de chaque organisation (page « direction ») | Difficile |
| `es-pep-local-officials` | ES-LOC:Conseiller municipal d'un chef-lieu ou d'une  (complète) ; ES-LOC:Maire d'un chef-lieu ou d'une commune de plus (complète) | 8763 | Wikidata (SPARQL) | Données ouvertes officielles des élections locales (ministère de l'Intérieur, bureau de statistique, commission électorale) | Moyenne |
| `es-pep-parties-unions` | ES-h:Membre de la direction d'un parti représenté  (partielle) ; ES-SIND:Membre de la haute direction d'un syndicat ou (partielle) | 16 | Wikidata (SPARQL) | Sites officiels des partis (page « direction / bureau / comité exécutif ») ; registre ou commission électorale du pays en complément | Moyenne |
| `es-pep-political-parties` | ES-CCAA:Membre de la direction régionale d'un parti (partielle) ; ES-h:Membre de la direction d'un parti représenté  (partielle) ; ES-LOC:Membre de la direction locale d'un parti (partielle) | 20 | Wikidata (SPARQL) | Sites officiels des partis (page « direction / bureau / comité exécutif ») ; registre ou commission électorale du pays en complément | Moyenne |
| `es-pep-rca` | ES-RCA:Membre de la famille d'une PEP espagnole (complète) ; ES-RCA:Proche associé d'une PEP espagnole (complète) | 134 | Wikidata (SPARQL) | Aucune source officielle ne liste les proches d'une PEP | Non remplaçable |
| `es-pep-regional-governments` | ES-CCAA:Conseiller ou membre d'un gouvernement autono (complète) ; ES-CCAA:Haut cargo d'une communauté autonome (publié  (complète) ; ES-CCAA:Président d'une communauté autonome (complète) | 16 | Wikidata (SPARQL) | Site officiel du gouvernement (composition) ; pour les régions, site de chaque gouvernement régional | Facile à moyenne |
| `es-pep-regional-parliaments` | ES-CCAA:Député d'un parlement autonome (complète) | 395 | Wikidata (SPARQL) | Site officiel du Parlement (annuaire des élus) | Facile |
| `es-pep-royal-house` | ES-a:Roi d'Espagne (Rey) (partielle) | 8 | Wikidata (SPARQL) | Site officiel de la présidence ou de la maison royale | Facile |
| `es-pep-state-enterprises` | ES-g:Membre du conseil d'administration d'une entr (complète) | 5 | Wikidata (SPARQL) | Registre de l'actionnaire d'État (ministère des Finances ou agence de participations) puis site de chaque entreprise | Difficile |

## Notes

- Les parsers de la Grèce n'ont jamais été exécutés : leur nombre de personnes est inconnu.
- Le parser `fr_pep_wikidata_positions` (France) publie 2 personnes ; `fr_pep_eu_institutions` couvre déjà la même fonction.
- Priorité conseillée : d'abord les fonctions « Facile » (présidents, parlements, juridictions, banques centrales, armée), qui remplacent la source en une page chacune ; puis diplomates et partis ; les entreprises publiques et organisations internationales en dernier ; les RCA ne se remplacent pas.
