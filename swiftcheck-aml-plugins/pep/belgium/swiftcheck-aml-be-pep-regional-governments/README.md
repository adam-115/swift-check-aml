# swiftcheck-aml-be-pep-regional-governments

Plugin PF4J (`be-pep-regional-governments`) pour l'extraction des membres des **gouvernements des Régions et des Communautés de Belgique** (`be_pep_regional_governments`).

## Base légale
- **Loi du 18 septembre 2017**, art. 4, 28°, a) et Annexe IV – *« Ministre-président, vice-ministre-président, ministre ou secrétaire d'État d'une Région ou d'une Communauté »* (catégorie `BE-a`).

## Architecture pilotée par configuration
Le fichier `src/main/resources/regional-governments.json` déclare les 5 exécutifs fédérés :
1. **Gouvernement flamand** (`https://www.vlaanderen.be/vlaamse-regering`)
2. **Gouvernement de la Fédération Wallonie-Bruxelles** (`https://gouvernement.cfwb.be/home.html`)
3. **Gouvernement de la Communauté germanophone** (`https://ostbelgienlive.be/desktopdefault.aspx/tabid-218/`)
4. **Gouvernement wallon** (`https://gouvernement.wallonie.be/home/les-ministres.html`)
5. **Gouvernement de la Région de Bruxelles-Capitale** (`https://be.brussels/fr/a-propos-de-la-region/structure-et-organisations/le-gouvernement-de-la-region-de-bruxelles-capitale`)

Lorsqu'un portail régional est temporairement injoignable (timeout réseau), il est consigné dans le message d'exécution sans bloquer l'extraction des autres exécutifs.

