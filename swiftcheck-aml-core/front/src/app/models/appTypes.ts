export interface Page<T> {
    content: T[];
    totalElements: number;
    totalPages: number;
    size: number;
    number: number;
    first: boolean;
    last: boolean;
}

// ---------------------------------------------------------------------------
// Ingestion des listes : types identiques aux DTO Java (paquet com.swiftcheck.aml.dto).
// Les dates arrivent du serveur au format texte ISO, ex. "2026-10-16T08:00:00Z".
// ---------------------------------------------------------------------------

/** Configuration globale (GET / PUT /api/config). */
export interface ApplicationConfigDTO {
    yenteUrl?: string;
    yenteUpdateToken?: string;
    yenteDataFolder?: string;
    yenteContainerDataPath?: string;
    yenteManifestPath?: string;
    archiveFolder?: string;
    workFolder?: string;
}

/** Nature d'une liste : PEP, sanctions (AML), médias, criminelle. */
export type ListType = 'AML' | 'PEP' | 'MEDIA' | 'CRIMINAL';

/** D'où viennent les données d'un parser. */
export type DataSourceType = 'HTTP_FILE' | 'LOCAL_FILE' | 'PLUGIN_MANAGED';

/** Type de source lue par un parser (T1 à T7 de la spécification). */
export type ParserKind = 'OPEN_DATA_FILE' | 'API' | 'SPARQL' | 'OFFICIAL_GAZETTE' | 'WEB_PAGE'
    | 'PDF_DOCUMENT' | 'DECLARATION_REGISTER';

// ---------------------------------------------------------------------------
// Référentiel PEP et couverture (GET /api/pep/...)
// ---------------------------------------------------------------------------

/** Pays couvert ; retentionMonths = vigilance minimale après la fin des fonctions. */
export interface CountryDTO {
    iso: string;
    name: string;
    euMember: boolean;
    legalBasis?: string;
    retentionMonths: number;
    officialListReference?: string;     // liste officielle des fonctions PEP, ex. « JO C/2023/724 »
    officialListDate?: string;
    officialListUrl?: string;
}

/** Nature d'une catégorie PEP : liste officielle, AMLR 2027, approche par les risques, UE, famille et proches. */
export type PepCategoryKind = 'OFFICIAL' | 'AMLR' | 'RISK_BASED' | 'EU' | 'RCA';

/** Catégorie de fonctions PEP d'un pays, ex. LU-b « Parlementaires et Conseil d'État ». */
export interface PepCategoryDTO {
    id: number;                         // 0 à la création
    countryIso: string;
    code: string;
    label: string;
    kind: PepCategoryKind;
    riskClass: number;                  // 1 = risque le plus élevé, 4 = le plus faible
    sortOrder: number;
    legalReference?: string;
    active: boolean;                    // désactivée = absente de la page « Couverture PEP »
    functionCount: number;              // lecture seule
}

export interface PepFunctionDTO {
    id: number;
    categoryId: number;
    categoryCode: string;
    categoryLabel: string;
    countryIso: string;
    label: string;
    institution?: string;
    expectedHolders?: number;
}

export type CoverageStatus = 'COVERED' | 'PARTIAL' | 'NONE';

/** Page « Couverture PEP » d'un pays. */
export interface CoverageDTO {
    country: CountryDTO;
    officialCategories: number;
    coveredOfficialCategories: number;
    personCount: number;
    categories: CategoryCoverage[];
}

export interface CategoryCoverage {
    code: string;
    label: string;
    kind: PepCategoryKind;
    riskClass: number;
    legalReference?: string;
    status: CoverageStatus;
    functions: FunctionCoverage[];
}

export interface FunctionCoverage {
    id: number;
    label: string;
    institution?: string;
    expectedHolders?: number;
    status: CoverageStatus;
    parsers: CoveringParser[];          // avec la complétude déclarée pour cette fonction
}

export type Completeness = 'COMPLETE' | 'PARTIAL';

/** Parser qui couvre une fonction ; personCount est le total du parser. */
export interface CoveringParser {
    id: number;
    name: string;
    datasetName: string;
    enabled: boolean;
    completeness: Completeness;         // pour cette fonction
    includesFormer: boolean;            // donne aussi les anciens titulaires
    lastStatus?: ExecutionStatus;
    lastPublishedAt?: string;
    personCount: number;
    retainedCount: number;
}

/** Entité disparue de la source, encore publiée jusqu'à expiresAt. */
export interface RetainedEntityDTO {
    id: number;
    entityId: string;
    entitySchema: string;
    caption?: string;
    removedAt: string;
    expiresAt: string;
}

// ---------------------------------------------------------------------------
// Alertes (GET /api/alerts/...)
// ---------------------------------------------------------------------------

export type AlertType = 'FAILED' | 'BLOCKED' | 'STALE';

export interface ParserAlertDTO {
    id: number;
    parserId: number;
    parserName: string;
    datasetName: string;
    type: AlertType;
    raisedAt: string;
    resolvedAt?: string;
    message?: string;
}

/** Résultat d'une exécution. */
export type ExecutionStatus = 'RUNNING' | 'SUCCESS' | 'UNCHANGED' | 'BLOCKED' | 'FAILED';

/** Une fonction PEP couverte par un parser. */
export interface ParserCoverageItem {
    functionId: number;
    completeness: Completeness;
    includesFormer: boolean;
}

/** Configuration d'un parser ; les champs last* résument la dernière exécution (lecture seule). */
export interface ParserDTO {
    id?: number;
    name?: string;
    description?: string;
    datasetName?: string;
    listType?: ListType;                // PEP, AML (sanctions)...
    websiteUrl?: string;                // site de l'organisme qui publie la liste
    pluginId?: string;
    pluginUrl?: string;                 // chemin du JAR du plugin sur le serveur
    dataSourceType?: DataSourceType;
    sourceLocation?: string;
    parameters?: string;                // objet JSON sous forme de texte
    refreshHours?: number;
    parserKind?: ParserKind;
    retentionMonths?: number;           // 0 = aucune rétention (sanctions)
    licence?: string;
    attribution?: string;
    notes?: string;                     // remarques sur la source
    hasTechnicalDescription?: boolean;  // description technique renseignée (lecture seule, texte lu à part)
    coverage?: ParserCoverageItem[];    // fonctions PEP couvertes (vide pour les sanctions)
    countryIso?: string;                // pays des fonctions couvertes (lecture seule)
    enabled?: boolean;
    createdAt?: string;
    updatedAt?: string;
    lastStatus?: ExecutionStatus;
    lastRunAt?: string;
    lastMessage?: string;
    lastPersonCount?: number;           // dernière exécution (lecture seule)
    lastRetainedCount?: number;
    lastTestId?: number;                // dernier test (lecture seule)
    lastTestStatus?: TestStatus;
    lastTestAt?: string;
    lastTestFoundPercent?: number;
}

/** Une exécution de parser (lecture seule). */
export interface ParserExecutionDTO {
    id: number;
    parserId: number;
    parserName?: string;
    datasetName?: string;
    triggerType?: 'MANUAL' | 'SCHEDULED';
    status: ExecutionStatus;
    startedAt?: string;
    finishedAt?: string;
    inputSha256?: string;
    entityCount: number;
    personCount: number;                // personnes produites par le plugin
    retainedCount: number;              // entités disparues de la source, encore publiées (rétention)
    rejectedCount: number;
    addedCount: number;
    removedCount: number;
    changedCount: number;
    message?: string;
}

/** Fichier source signé (SHA-256) et archivé lors d'une exécution. */
export interface SourceFileDTO {
    id: number;
    origin: string;
    sha256: string;
    sizeBytes: number;
    archivePath: string;
    fetchedAt: string;
}

export type ChangeType = 'ADDED' | 'REMOVED' | 'MODIFIED';

/** Test d'un parser : chaque élément du fichier source est cherché dans Yente. */
export type TestStatus = 'RUNNING' | 'PASSED' | 'FAILED';

/** Pourquoi un élément de la source n'a pas été retrouvé dans Yente. */
export type TestFailureReason = 'NOT_CONVERTED' | 'NOT_INDEXED' | 'NOT_MATCHED' | 'YENTE_ERROR';

export interface ParserTestDTO {
    id: number;
    parserId: number;
    parserName: string;
    datasetName: string;
    executionId?: number;               // publication testée
    status: TestStatus;
    startedAt: string;
    finishedAt?: string;
    sourceCount: number;
    foundCount: number;
    notConvertedCount: number;
    notIndexedCount: number;
    notMatchedCount: number;
    errorCount: number;
    foundPercent: number;
    message?: string;
    publishedAt?: string;               // publication testée
    publishedEntityCount?: number;
    sourceFiles: SourceFileDTO[];       // fichier(s) source relus, avec leur date de téléchargement
}

/** Fenêtre de confirmation d'un test : ce qui sera testé, et ce qui l'empêcherait. */
export interface ParserTestPreviewDTO {
    parserId: number;
    parserName: string;
    datasetName: string;
    pluginId: string;
    pluginLoaded: boolean;
    publication?: ParserExecutionDTO;
    sourceFiles: SourceFileDTO[];
    lastTest?: ParserTestDTO;
    blocker?: string;
}

export interface ParserTestFailureDTO {
    entityId: string;
    schema: string;
    name?: string;
    sourceRef?: string;
    reason: TestFailureReason;
    detail?: string;
}

/** Entité ajoutée, retirée ou modifiée lors d'une publication. */
export interface DatasetChangeDTO {
    entityId: string;
    entitySchema: string;
    caption: string;
    changeType: ChangeType;
    changedProperties?: string;
}

/** Plugin PF4J chargé (GET /api/ftm/plugins). */
/** Résultat du bouton « Vérifier » : plugin trouvé par PF4J dans le JAR. */
export interface PluginCheck {
    pluginId: string;
    version: string;
    parserClass: string;
    path: string;
}

export interface PluginInfo {
    pluginId: string;
    version: string;
    state: string;
    path: string;
}

export interface AmlAdvancedVerificationRequestDTO {
    dataset?: string;
    companyName?: string;
    ubos?: string[];
    schema?: string;
    properties?: { [key: string]: string[] };
}

export interface AmlAdvancedVerificationResponseDTO {
    companyResult?: any; // AmlVerificationResponseDTO but we can just use any for now or define it
    uboResults?: { [key: string]: any };
    errorMessage?: string;
}

/** Description technique d'un parser (Markdown), lue et modifiée depuis l'écran « Parsers ». */
export interface TechnicalDescriptionDTO {
    markdown: string;
    updatedAt?: string;                 // dernière modification du parser (lecture seule)
}
