# ie-pep-president

Dataset `ie_pep_president` : Président d'Irlande (catégorie IE-a).

**Source** : page du Président en fonction sur `president.ie` (ex. `https://president.ie/the-president/catherine-connolly`).
Le nom vient du titre `h1.c-hero__title`, la période du texte « 2025 - Present Day ». Une page d'ancien Président
(« 2011 - 2025 ») est refusée.

**Type** : `HTTP_FILE` (le cœur télécharge la page). Paramètre facultatif `pageUrl` (adresse publiée dans `sourceUrl`).

**À chaque nouveau Président**, remplacer l'adresse du parser par la page du Président en fonction (menu « President in
Office » du site) ; le sortant reste publié 12 mois par la rétention du cœur.

**Limites** : ni date ni lieu de naissance sur la page ; année de début seulement.
