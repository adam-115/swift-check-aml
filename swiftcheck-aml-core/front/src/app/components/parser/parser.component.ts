import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AbstractControl, FormBuilder, FormGroup, FormsModule, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';

import {
  Completeness, DataSourceType, ListType, ParserDTO, ParserExecutionDTO, ParserKind, ParserTestDTO, PepFunctionDTO, PluginCheck,
  PluginInfo,
} from '../../models/appTypes';
import { ParserService } from '../../services/parser.service';
import { PepReferenceService } from '../../services/pep-reference.service';
import { PluginService } from '../../services/plugin.service';
import { YenteService } from '../../services/yente.service';
import { apiError } from '../../services/api-error';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { TestRunDialogComponent } from './test-run-dialog.component';
import { ParserTestHistoryComponent } from './parser-test-history.component';
import { TechnicalDescriptionDialogComponent } from './technical-description-dialog.component';
import { BulkItem, BulkRun, BulkRunDialogComponent } from './bulk-run-dialog.component';
import { ParserDetailDialogComponent } from './parser-detail-dialog.component';

/** Libellés des types de source, affichés dans la liste et le formulaire. */
export const SOURCE_TYPES: { value: DataSourceType; label: string; help: string }[] = [
  { value: 'HTTP_FILE', label: 'Fichier à télécharger', help: 'URL http(s) du fichier officiel (XML, CSV, JSON…)' },
  { value: 'LOCAL_FILE', label: 'Fichier sur le serveur', help: 'Chemin complet du fichier sur le serveur' },
  { value: 'PLUGIN_MANAGED', label: 'Géré par le plugin', help: 'Le plugin récupère lui-même ses données (SPARQL, API, SQL…)' },
];

/** Types de source T1 à T7 de la spécification : information sur la façon dont la source est publiée. */
export const PARSER_KINDS: { value: ParserKind; label: string }[] = [
  { value: 'OPEN_DATA_FILE', label: 'T1 – Fichier open data (CSV, XML, JSON)' },
  { value: 'API', label: 'T2 – API REST ou OData' },
  { value: 'SPARQL', label: 'T3 – Point d\'accès SPARQL' },
  { value: 'OFFICIAL_GAZETTE', label: 'T4 – Journal officiel (nominations)' },
  { value: 'WEB_PAGE', label: 'T5 – Page web officielle' },
  { value: 'PDF_DOCUMENT', label: 'T6 – Document PDF' },
  { value: 'DECLARATION_REGISTER', label: 'T7 – Liste PEP ou registre de déclarations' },
];

/** Types de liste : un parser PEP couvre des fonctions, une liste de sanctions aucune. */
export const LIST_TYPES: { value: ListType; label: string }[] = [
  { value: 'PEP', label: 'PEP' },
  { value: 'AML', label: 'Sanctions' },
  { value: 'MEDIA', label: 'Médias' },
  { value: 'CRIMINAL', label: 'Criminelle' },
];

/** Une fonction cochée : complétude et anciens titulaires, modifiables dans le formulaire. */
interface CoverageChoice {
  completeness: Completeness;
  includesFormer: boolean;
}

/** Fonctions PEP d'une catégorie, pour les cases à cocher du formulaire. */
interface FunctionGroup {
  code: string;
  label: string;
  functions: PepFunctionDTO[];
}

/**
 * Écran « Parsers » : liste des parsers, lancement d'une exécution, formulaire de configuration
 * et gestion des plugins PF4J.
 */
@Component({
  selector: 'app-parser',
  standalone: true,
  imports: [CommonModule, FormsModule, ReactiveFormsModule, RouterLink, StatusBadgeComponent, TestRunDialogComponent,
    ParserTestHistoryComponent, TechnicalDescriptionDialogComponent, BulkRunDialogComponent, ParserDetailDialogComponent],
  templateUrl: './parser.component.html',
})
export class ParserComponent implements OnInit {
  readonly sourceTypes = SOURCE_TYPES;
  readonly parserKinds = PARSER_KINDS;
  readonly listTypes = LIST_TYPES;

  // Liste : tous les parsers sont chargés, la recherche et la pagination se font ici
  allParsers: ParserDTO[] = [];
  /** Parsers qui répondent aux filtres, et la page affichée. */
  filtered: ParserDTO[] = [];
  parsers: ParserDTO[] = [];
  totalElements = 0;
  page = 0;
  totalPages = 0;
  readonly pageSize = 20;

  // Recherche et filtres
  search = '';
  filterCountry = '';
  filterListType: ListType | '' = '';
  filterEnabled: '' | 'enabled' | 'disabled' = '';
  /** Dernière exécution : un statut, NEVER (jamais exécuté) ou PROBLEM (échec ou bloquée). */
  filterLastStatus = '';
  /** Pays couverts par chaque parser (son pays et celui de ses fonctions), et liste des pays pour le filtre. */
  private parserCountries = new Map<number, Set<string>>();
  countryOptions: string[] = [];

  // Lot « Exécuter tout » / « Tester tout », traité un parser à la fois
  bulk?: BulkRun;
  isBulkDialogOpen = false;

  /** Parser dont la fenêtre « Afficher » est ouverte. */
  detailParser?: ParserDTO;

  // Exécution : parsers en cours (boutons désactivés) et résultat de la dernière exécution lancée ici
  running = new Set<number>();
  lastRun?: ParserExecutionDTO;
  /** Arrêts forcés en cours, et réponse du dernier arrêt. */
  stopping = new Set<number>();
  stopMessage = '';

  // Tests : début de chaque test en cours (ms), résultat ou erreur du dernier test lancé ici, par parser.
  // Le test vit ici et non dans la fenêtre : il continue si on la ferme.
  testing = new Map<number, number>();
  testResults = new Map<number, ParserTestDTO>();
  testErrors = new Map<number, string>();
  /** Résultat d'un test terminé alors que sa fenêtre était fermée. */
  lastTest?: ParserTestDTO;
  /** Parser dont la fenêtre de test est ouverte. */
  testDialogParser?: ParserDTO;
  /** Parser dont l'historique des tests est ouvert, et compteur pour le recharger après un test. */
  historyParser?: ParserDTO;
  historyRefresh = 0;
  /** Parser dont la fenêtre « Description technique » est ouverte. */
  descriptionParser?: ParserDTO;
  errorMessage = '';

  // Formulaire (panneau latéral)
  isFormOpen = false;
  isSaving = false;
  formError = '';
  form: FormGroup;

  // Couverture PEP : toutes les fonctions du référentiel et celles cochées dans le formulaire (par id de fonction)
  allFunctions: PepFunctionDTO[] = [];
  coverage = new Map<number, CoverageChoice>();
  /** Pays des fonctions affichées dans le formulaire (vide : tous). */
  functionCountry = '';
  /** Pays du référentiel, pour le filtre des fonctions du formulaire. Voir {@link refreshFunctionGroups}. */
  functionCountries: string[] = [];
  functionGroups: FunctionGroup[] = [];

  // Bouton « Vérifier » du chemin du JAR
  isVerifying = false;
  pluginCheck?: PluginCheck;
  pluginCheckError = '';

  // Réindexation Yente
  isReindexing = false;
  reindexMessage = '';

  // Plugins PF4J
  plugins: PluginInfo[] = [];
  jarPath = '';
  pluginMessage = '';
  pluginError = '';

  constructor(
    private fb: FormBuilder,
    private parserService: ParserService,
    private pluginService: PluginService,
    private yenteService: YenteService,
    private referenceService: PepReferenceService,
  ) {
    this.form = this.fb.group({
      id: [null],
      name: ['', Validators.required],
      description: [''],
      listType: ['PEP', Validators.required],
      websiteUrl: [''],
      datasetName: ['', [Validators.required, Validators.pattern(/^[a-z0-9_]+$/)]],
      pluginUrl: [''],
      pluginId: ['', Validators.required],
      dataSourceType: ['HTTP_FILE', Validators.required],
      sourceLocation: [''],
      parameters: ['', jsonObjectValidator],
      refreshHours: [24, [Validators.required, Validators.min(0)]],
      parserKind: [null],
      retentionMonths: [0, [Validators.required, Validators.min(0)]],
      licence: [''],
      attribution: [''],
      notes: [''],
      enabled: [true],
    });
  }

  /** Un nouveau chemin doit être vérifié à nouveau. */
  private watchPluginUrl(): void {
    this.form.get('pluginUrl')!.valueChanges.subscribe(() => {
      this.pluginCheck = undefined;
      this.pluginCheckError = '';
    });
  }

  ngOnInit(): void {
    this.watchPluginUrl();
    this.loadParsers();
    this.loadPlugins();
    this.referenceService.functions().subscribe({
      next: functions => {
        this.allFunctions = functions;
        this.refreshFunctionGroups();
        this.applyFilters(false);       // pays des fonctions couvertes, pour le filtre par pays
      },
    });
  }

  // ---------------------------------------------------------------- liste

  /** Recharge tous les parsers en gardant la page et les filtres (après une exécution, un test, un enregistrement). */
  loadParsers(): void {
    this.parserService.getAllForProviders().subscribe({
      next: page => {
        this.allParsers = page.content;
        this.totalElements = page.totalElements;
        this.applyFilters(false);
        // Les fenêtres ouvertes suivent la nouvelle version du parser
        if (this.detailParser) {
          this.detailParser = this.allParsers.find(p => p.id === this.detailParser!.id) ?? this.detailParser;
        }
      },
      error: err => (this.errorMessage = apiError(err)),
    });
  }

  /**
   * Applique la recherche et les filtres. Recherche : nom, dataset, plugin, description, source, remarques
   * et pays, sans tenir compte des accents ni de la casse ; plusieurs mots doivent tous être trouvés.
   * resetPage = false garde la page courante (rechargement), sauf si elle n'existe plus.
   */
  applyFilters(resetPage = true): void {
    this.computeCountries();
    const words = normalize(this.search).split(/\s+/).filter(w => w);
    this.filtered = this.allParsers.filter(p => {
      const countries = this.parserCountries.get(p.id!) ?? new Set<string>();
      if (this.filterCountry && !countries.has(this.filterCountry)) {
        return false;
      }
      if (this.filterListType && (p.listType ?? 'PEP') !== this.filterListType) {
        return false;
      }
      if (this.filterEnabled && (this.filterEnabled === 'enabled') !== !!p.enabled) {
        return false;
      }
      if (this.filterLastStatus) {
        const ok = this.filterLastStatus === 'NEVER' ? !p.lastStatus
          : this.filterLastStatus === 'PROBLEM' ? p.lastStatus === 'FAILED' || p.lastStatus === 'BLOCKED'
          : p.lastStatus === this.filterLastStatus;
        if (!ok) {
          return false;
        }
      }
      if (words.length) {
        const text = normalize([p.name, p.datasetName, p.pluginId, p.description, p.sourceLocation, p.notes,
          ...countries].join(' '));
        return words.every(w => text.includes(w));
      }
      return true;
    });
    this.totalPages = Math.ceil(this.filtered.length / this.pageSize);
    if (resetPage || this.page >= this.totalPages) {
      this.page = 0;
    }
    this.slicePage();
  }

  get hasFilters(): boolean {
    return !!(this.search.trim() || this.filterCountry || this.filterListType || this.filterEnabled || this.filterLastStatus);
  }

  clearFilters(): void {
    this.search = '';
    this.filterCountry = '';
    this.filterListType = '';
    this.filterEnabled = '';
    this.filterLastStatus = '';
    this.applyFilters();
  }

  /** Un parser couvre son pays et celui de chacune de ses fonctions PEP (un parser UE en couvre plusieurs). */
  private computeCountries(): void {
    const functionCountry = new Map(this.allFunctions.map(f => [f.id, f.countryIso]));
    this.parserCountries.clear();
    for (const p of this.allParsers) {
      const set = new Set<string>();
      if (p.countryIso) {
        set.add(p.countryIso);
      }
      for (const c of p.coverage ?? []) {
        const iso = functionCountry.get(c.functionId);
        if (iso) {
          set.add(iso);
        }
      }
      this.parserCountries.set(p.id!, set);
    }
    this.countryOptions = [...new Set([...this.parserCountries.values()].flatMap(s => [...s]))].sort();
  }

  private slicePage(): void {
    const start = this.page * this.pageSize;
    this.parsers = this.filtered.slice(start, start + this.pageSize);
  }

  changePage(page: number): void {
    if (page >= 0 && page < this.totalPages) {
      this.page = page;
      this.slicePage();
    }
  }

  // ---------------------------------------------------------------- exécuter tout / tester tout

  /**
   * Prépare le lot sur les parsers affichés par les filtres (toutes pages) et ouvre la confirmation.
   * Les parsers désactivés ou déjà en cours d'exécution ou de test sont exclus.
   */
  openBulk(mode: BulkRun['mode']): void {
    if (this.bulk?.phase === 'running') {
      this.isBulkDialogOpen = true;
      return;
    }
    const busy = (p: ParserDTO) => this.isRunning(p) || this.testing.has(p.id!);
    const targets = this.filtered.filter(p => p.enabled && !busy(p));
    this.bulk = {
      mode,
      items: targets.map(parser => ({ parser, state: 'pending' })),
      excluded: this.filtered.filter(p => !p.enabled || busy(p)),
      force: false,
      phase: 'confirm',
      stopping: false,
    };
    this.isBulkDialogOpen = true;
  }

  startBulk(): void {
    if (!this.bulk || this.bulk.phase !== 'confirm') {
      return;
    }
    this.bulk.phase = 'running';
    this.errorMessage = '';
    this.nextBulkItem(this.bulk);
  }

  /** Les parsers suivants ne sont pas lancés ; une exécution en cours est arrêtée de force (un test va à son terme). */
  stopBulk(): void {
    if (this.bulk?.phase === 'running') {
      this.bulk.stopping = true;
      const current = this.bulk.items.find(i => i.state === 'running');
      if (this.bulk.mode === 'run' && current?.parser.id && !this.stopping.has(current.parser.id)) {
        this.forceStop(current.parser.id);
      }
    }
  }

  closeBulkDialog(): void {
    this.isBulkDialogOpen = false;
    if (this.bulk?.phase !== 'running') {
      this.bulk = undefined;
    }
  }

  /** Lance le parser suivant du lot ; à la fin, ou après « Arrêter », les parsers restants sont marqués non lancés. */
  private nextBulkItem(bulk: BulkRun): void {
    const item = bulk.items.find(i => i.state === 'pending');
    if (!item || bulk.stopping) {
      bulk.items.filter(i => i.state === 'pending').forEach(i => (i.state = 'skipped'));
      bulk.phase = 'finished';
      this.loadParsers();
      return;
    }
    const id = item.parser.id!;
    item.state = 'running';
    const done = (status: BulkItem['status'], message: string | undefined, link: unknown[]) => {
      item.state = 'done';
      item.status = status;
      item.message = message;
      item.link = link;
    };
    const failed = (err: unknown) => {
      item.state = 'error';
      item.message = apiError(err);
    };
    const next = () => {
      this.loadParsers();               // statut à jour dans la liste, parser par parser
      this.nextBulkItem(bulk);
    };
    if (bulk.mode === 'run') {
      this.running.add(id);
      this.parserService.run(id, bulk.force).subscribe({
        next: e => { this.running.delete(id); done(e.status, e.message, ['/executions', e.id]); next(); },
        error: err => { this.running.delete(id); failed(err); next(); },
      });
    } else {
      this.testing.set(id, Date.now());
      this.parserService.test(id).subscribe({
        next: t => { this.testing.delete(id); done(t.status, t.message, ['/tests', t.id]); next(); },
        error: err => { this.testing.delete(id); failed(err); next(); },
      });
    }
  }

  // ---------------------------------------------------------------- détail

  /** « Modifier » depuis la fenêtre de détail : elle se ferme et le formulaire s'ouvre. */
  editFromDetail(): void {
    const parser = this.detailParser;
    this.detailParser = undefined;
    this.openForm(parser);
  }

  sourceLabel(type?: DataSourceType): string {
    return SOURCE_TYPES.find(t => t.value === type)?.label ?? '—';
  }

  // ---------------------------------------------------------------- exécution

  /**
   * Lance le parser et attend le résultat (SUCCESS, UNCHANGED, BLOCKED ou FAILED).
   * force = true : republie même si la source n'a pas changé (après une mise à jour du plugin).
   */
  run(parser: ParserDTO, force: boolean): void {
    if (!parser.id || this.running.has(parser.id)) {
      return;
    }
    if (force && !confirm(`Reconvertir et republier « ${parser.name} » même si la source n'a pas changé ?`)) {
      return;
    }
    const id = parser.id;
    this.running.add(id);
    this.errorMessage = '';
    this.lastRun = undefined;
    this.parserService.run(id, force).subscribe({
      next: execution => {
        this.running.delete(id);
        this.lastRun = execution;
        this.loadParsers();             // rafraîchir le statut de la dernière exécution
      },
      error: err => {
        this.running.delete(id);
        this.errorMessage = apiError(err);
      },
    });
  }

  /** Exécution en cours : lancée depuis cet écran, ou vue en cours en base (autre écran, planificateur, ligne orpheline). */
  isRunning(parser: ParserDTO): boolean {
    return this.running.has(parser.id!) || parser.lastStatus === 'RUNNING';
  }

  /**
   * Bouton « Arrêter » : arrêt forcé de l'exécution en cours, sans rien publier, pour pouvoir relancer
   * le parser (plugin bloqué, source qui ne répond pas…). Le serveur attend au plus une dizaine de secondes.
   */
  stopRun(parser: ParserDTO): void {
    if (!parser.id || this.stopping.has(parser.id)
        || !confirm(`Arrêter de force l'exécution de « ${parser.name} » ?\nRien ne sera publié : Yente garde la version actuelle. Le parser pourra ensuite être relancé.`)) {
      return;
    }
    this.forceStop(parser.id);
  }

  private forceStop(id: number): void {
    this.stopping.add(id);
    this.errorMessage = '';
    this.parserService.stop(id).subscribe({
      next: res => {
        this.stopping.delete(id);
        this.running.delete(id);
        this.stopMessage = res.message;
        this.loadParsers();
      },
      error: err => {
        this.stopping.delete(id);
        this.errorMessage = apiError(err);
        this.loadParsers();
      },
    });
  }

  // ---------------------------------------------------------------- tests

  /**
   * Ouvre la fenêtre de test : elle montre d'abord ce qui sera testé (publication, fichier source) et
   * demande confirmation. Si un test de ce parser tourne déjà, elle affiche sa progression.
   */
  openTestDialog(parser: ParserDTO): void {
    if (!this.testing.has(parser.id!)) {
      this.testResults.delete(parser.id!);   // nouvelle demande : repartir de la confirmation
      this.testErrors.delete(parser.id!);
    }
    this.testDialogParser = parser;
  }

  closeTestDialog(): void {
    const id = this.testDialogParser?.id;
    this.testDialogParser = undefined;
    if (id && !this.testing.has(id)) {
      this.testResults.delete(id);
      this.testErrors.delete(id);
    }
  }

  /**
   * Lance le test confirmé : chaque élément du fichier source de la dernière publication est cherché dans Yente
   * (une à trois minutes sur une grande liste).
   */
  runTest(parser: ParserDTO): void {
    const id = parser.id!;
    if (this.testing.has(id)) {
      return;
    }
    this.testing.set(id, Date.now());
    this.lastTest = undefined;
    this.parserService.test(id).subscribe({
      next: test => {
        this.testing.delete(id);
        this.afterTest(id);
        if (this.testDialogParser?.id === id) {
          this.testResults.set(id, test);      // fenêtre ouverte : le résultat s'y affiche
        } else {
          this.lastTest = test;                // fenêtre fermée : bandeau en haut de l'écran
        }
      },
      error: err => {
        this.testing.delete(id);
        this.afterTest(id);
        if (this.testDialogParser?.id === id) {
          this.testErrors.set(id, apiError(err));
        } else {
          this.errorMessage = apiError(err);
        }
      },
    });
  }

  openTestHistory(parser: ParserDTO): void {
    this.historyParser = parser;
  }

  /** Rafraîchit la colonne « Dernier test » et l'historique ouvert. */
  private afterTest(id: number): void {
    this.loadParsers();
    if (this.historyParser?.id === id) {
      this.historyRefresh++;
    }
  }

  /** Bouton « Réindexer Yente » : Yente relit le manifest et réindexe, en tâche de fond. */
  reindexYente(): void {
    if (!confirm('Demander à Yente de réindexer les listes ? Le criblage reste disponible pendant la réindexation.')) {
      return;
    }
    this.isReindexing = true;
    this.reindexMessage = '';
    this.errorMessage = '';
    this.yenteService.reindex().subscribe({
      next: res => {
        this.isReindexing = false;
        this.reindexMessage = res.message;
      },
      error: err => {
        this.isReindexing = false;
        this.errorMessage = apiError(err);   // ex. Yente injoignable, jeton refusé
      },
    });
  }

  disable(parser: ParserDTO): void {
    if (!parser.id || !confirm(`Désactiver « ${parser.name} » et retirer ${parser.datasetName} de Yente ?\nL'historique est conservé.`)) {
      return;
    }
    this.parserService.disable(parser.id).subscribe({
      next: () => this.loadParsers(),
      error: err => (this.errorMessage = apiError(err)),
    });
  }

  // ---------------------------------------------------------------- formulaire

  openForm(parser?: ParserDTO): void {
    this.formError = '';
    this.form.reset({
      id: parser?.id ?? null,
      name: parser?.name ?? '',
      description: parser?.description ?? '',
      listType: parser?.listType ?? 'PEP',
      websiteUrl: parser?.websiteUrl ?? '',
      datasetName: parser?.datasetName ?? '',
      pluginUrl: parser?.pluginUrl ?? '',
      pluginId: parser?.pluginId ?? '',
      dataSourceType: parser?.dataSourceType ?? 'HTTP_FILE',
      sourceLocation: parser?.sourceLocation ?? '',
      parameters: parser?.parameters ?? '',
      refreshHours: parser?.refreshHours ?? 24,
      parserKind: parser?.parserKind ?? null,
      retentionMonths: parser?.retentionMonths ?? 0,
      licence: parser?.licence ?? '',
      attribution: parser?.attribution ?? '',
      notes: parser?.notes ?? '',
      enabled: parser?.enabled ?? true,
    });
    this.coverage = new Map((parser?.coverage ?? []).map(c => [c.functionId,
      { completeness: c.completeness, includesFormer: c.includesFormer }]));
    this.functionCountry = parser?.countryIso ?? (parser ? '' : 'LU');
    this.refreshFunctionGroups();
    this.pluginCheck = undefined;
    this.pluginCheckError = '';
    this.isFormOpen = true;
  }

  /**
   * Bouton « Vérifier » : le serveur charge le JAR avec PF4J et contrôle qu'il contient un parser.
   * En cas de succès, le Plugin-Id lu dans le JAR est recopié dans le formulaire.
   */
  verifyPlugin(): void {
    const path = (this.form.value.pluginUrl ?? '').trim();
    if (!path) {
      this.pluginCheckError = 'Indiquez le chemin complet du JAR sur le serveur.';
      return;
    }
    this.isVerifying = true;
    this.pluginCheck = undefined;
    this.pluginCheckError = '';
    this.parserService.verifyPlugin(path).subscribe({
      next: check => {
        this.isVerifying = false;
        this.pluginCheck = check;
        this.form.patchValue({ pluginId: check.pluginId });
        this.loadPlugins();               // le plugin apparaît dans « Plugins chargés »
      },
      error: err => {
        this.isVerifying = false;
        this.pluginCheckError = apiError(err);
      },
    });
  }

  /** Nom du fichier JAR, affiché dans la liste (le chemin complet est en info-bulle). */
  jarName(path?: string): string {
    return path ? path.split(/[\\/]/).pop() ?? path : '';
  }

  closeForm(): void {
    this.isFormOpen = false;
  }

  get selectedSourceType() {
    return SOURCE_TYPES.find(t => t.value === this.form.value.dataSourceType);
  }

  /** L'emplacement est obligatoire sauf si le plugin récupère lui-même ses données. */
  get locationRequired(): boolean {
    return this.form.value.dataSourceType !== 'PLUGIN_MANAGED';
  }

  save(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      return;
    }
    const v = this.form.value;
    if (this.locationRequired && !v.sourceLocation?.trim()) {
      this.formError = 'Emplacement de la source obligatoire (URL ou chemin du fichier).';
      return;
    }
    const parser: ParserDTO = {
      id: v.id ?? undefined,
      name: v.name,
      description: v.description,
      listType: v.listType,
      websiteUrl: v.websiteUrl?.trim() || undefined,
      datasetName: v.datasetName,
      pluginId: v.pluginId,
      pluginUrl: v.pluginUrl?.trim() || undefined,
      dataSourceType: v.dataSourceType,
      sourceLocation: this.locationRequired ? v.sourceLocation : '',
      parameters: v.parameters,
      refreshHours: Number(v.refreshHours),
      parserKind: v.parserKind ?? undefined,
      retentionMonths: Number(v.retentionMonths),
      licence: v.licence,
      attribution: v.attribution,
      notes: v.notes,
      // Une liste qui n'est pas PEP ne couvre aucune fonction
      coverage: v.listType !== 'PEP' ? [] : [...this.coverage].map(([functionId, c]) => ({ functionId, ...c })),
      enabled: v.enabled,
    };
    this.isSaving = true;
    this.formError = '';
    this.parserService.save(parser).subscribe({
      next: () => {
        this.isSaving = false;
        this.isFormOpen = false;
        this.loadParsers();
      },
      error: err => {
        this.isSaving = false;
        this.formError = apiError(err);   // message de validation renvoyé par le serveur
      },
    });
  }

  // ---------------------------------------------------------------- couverture PEP

  /**
   * Fonctions à proposer, rangées par catégorie : celles du pays choisi, plus celles déjà cochées
   * (pour ne jamais masquer une fonction couverte). Sans pays, toutes les fonctions du référentiel.
   *
   * <p>Calculé ici et non dans un getter : un getter rend de nouveaux objets à chaque détection de changement,
   * le *ngFor recrée alors les lignes et leurs [ngModel], qui relancent une détection… et la page se bloque.
   */
  refreshFunctionGroups(): void {
    this.functionCountries = [...new Set(this.allFunctions.map(f => f.countryIso))].sort();
    const country = this.functionCountry;
    const groups = new Map<string, FunctionGroup>();
    for (const f of this.allFunctions) {
      if (country && f.countryIso !== country && !this.coverage.has(f.id)) {
        continue;
      }
      const group = groups.get(f.categoryCode) ?? { code: f.categoryCode, label: f.categoryLabel, functions: [] };
      group.functions.push(f);
      groups.set(f.categoryCode, group);
    }
    this.functionGroups = [...groups.values()];
  }

  trackGroup = (_: number, group: FunctionGroup) => group.code;
  trackFunction = (_: number, f: PepFunctionDTO) => f.id;

  /** Cocher une fonction la déclare complète, sans les anciens titulaires ; à préciser ensuite. */
  toggleFunction(id: number): void {
    if (this.coverage.has(id)) {
      this.coverage.delete(id);
    } else {
      this.coverage.set(id, { completeness: 'COMPLETE', includesFormer: false });
    }
    this.refreshFunctionGroups();
  }

  listTypeLabel(type?: ListType): string {
    return LIST_TYPES.find(t => t.value === type)?.label ?? 'PEP';
  }

  parserKindLabel(kind?: ParserKind): string {
    return PARSER_KINDS.find(k => k.value === kind)?.label ?? '';
  }

  invalid(name: string): boolean {
    const control = this.form.get(name);
    return !!control && control.invalid && control.touched;
  }

  // ---------------------------------------------------------------- plugins

  loadPlugins(): void {
    this.pluginService.list().subscribe({
      next: plugins => (this.plugins = plugins),
      error: err => (this.pluginError = apiError(err)),
    });
  }

  loadJar(): void {
    if (!this.jarPath.trim()) {
      return;
    }
    this.pluginAction(this.pluginService.load(this.jarPath.trim()), 'chargé');
  }

  reloadPlugin(plugin: PluginInfo): void {
    this.pluginAction(this.pluginService.reload(plugin.pluginId), 'rechargé');
  }

  unloadPlugin(plugin: PluginInfo): void {
    if (confirm(`Décharger ${plugin.pluginId} ? Les parsers qui l'utilisent échoueront tant qu'il n'est pas rechargé.`)) {
      this.pluginAction(this.pluginService.unload(plugin.pluginId), 'déchargé');
    }
  }

  private pluginAction(call: ReturnType<PluginService['load']>, verb: string): void {
    this.pluginMessage = '';
    this.pluginError = '';
    call.subscribe({
      next: res => {
        this.pluginMessage = `Plugin ${res.pluginId} ${verb}.`;
        this.jarPath = '';
        this.loadPlugins();
      },
      error: err => (this.pluginError = apiError(err)),
    });
  }
}

/** Minuscules sans accents, pour la recherche. */
function normalize(text: string): string {
  return text.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
}

/** Le champ « paramètres » doit être vide ou un objet JSON, ex. {"query": "SELECT ..."}. */
function jsonObjectValidator(control: AbstractControl): ValidationErrors | null {
  const text = (control.value ?? '').trim();
  if (!text) {
    return null;
  }
  try {
    const value = JSON.parse(text);
    return value && typeof value === 'object' && !Array.isArray(value) ? null : { json: true };
  } catch {
    return { json: true };
  }
}
