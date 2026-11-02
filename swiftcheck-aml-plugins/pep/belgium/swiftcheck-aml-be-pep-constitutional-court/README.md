# swiftcheck-aml-be-pep-constitutional-court

Plugin PF4J (`be-pep-constitutional-court`) pour l'extraction des **12 juges et 2 présidents de la Cour constitutionnelle de Belgique** (`be_pep_constitutional_court`).

## Base légale
- **Loi du 18 septembre 2017**, art. 4, 28°, d) et Annexe IV – *« Juge à la Cour constitutionnelle »* (catégorie `BE-d`).

## Source officielle
- Page d'organisation de la Cour constitutionnelle : `https://fr.const-court.be/court/presentation/organization`

## Périmètre extrait
- Uniquement la section `#org-judges` (les 6 juges francophones et 6 juges néerlandophones, dont les 2 présidents).
- Les référendaires, greffiers, membres du personnel administratif et membres émérites sont exclus conformément à la loi.

