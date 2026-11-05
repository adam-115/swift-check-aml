# pt-pep-government

Dataset `pt_pep_government` : Premier ministre, ministres et secrétaires d'État du Gouvernement portugais (catégorie PT-a).

**Source** : pages officielles du portail du Gouvernement, `portugal.gov.pt`. La page « composition » charge ses noms par
une API interne : elle n'est pas utilisée. Les pages de chaque ministre et secrétaire d'État sont rendues côté serveur ;
le plugin les trouve par le `sitemap.xml` public (autorisé par `robots.txt`), lit leur titre
(« Nom - Fonction - Domaine - XXV Governo Constitucional ») et l'historique des titulaires (identifiant officiel, dates de
début et de fin). Le Premier ministre vient de sa biographie (« X nasceu em …, a … de … de … »).

**Type** : `PLUGIN_MANAGED`. Paramètres :

```json
{"sitemapUrl": "https://portugal.gov.pt/sitemap.xml", "government": "gc25",
 "monthsAfterEnd": "12", "delayMs": "1500"}
```

`government` est facultatif : par défaut, le plus grand `gcNN` du sitemap. Environ 60 pages par exécution, 1,5 s entre deux
requêtes, User-Agent générique.

**Limites** : date de naissance seulement pour le Premier ministre, sans date de début ; le site écrit son nom sans accent
(« Luis Montenegro »). Les anciens titulaires ne sont connus que des pages de postes du gouvernement courant.
