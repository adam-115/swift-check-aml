import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AbstractControl, FormBuilder, FormGroup, FormsModule, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import {
  Completeness, DataSourceType, ListType, ParserDTO, ParserExecutionDTO, ParserKind, ParserTestDTO, PepFunctionDTO, PluginCheck,
  AvailablePlugin, PluginInfo,
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

/**
 * Une fonction cochée : complétude, anciens titulaires, ce que le parser couvre et ce qui manque, modifiables dans
 * le formulaire (affichés dans « Couverture PEP »).
 */
interface CoverageChoice {
  completeness: Completeness;
  includesFormer: boolean;
  coveredScope?: string;
  gaps?: string;
}

/** Colonnes triables de la liste. */
type SortKey = 'name' | 'country' | 'lastRun' | 'persons' | 'status';

export const SORT_OPTIONS: { value: SortKey; label: string }[] = [
  { value: 'name', label: 'Nom' },
  { value: 'country', label: 'Pays' },
  { value: 'lastRun', label: 'Dernière exécution' },
  { value: 'persons', label: 'Personnes publiées' },
  { value: 'status', label: 'État (problèmes d\'abord)' },
];

const PAGE_SIZES = [20, 50, 100];

/** Un pays de la navigation : ses parsers et l'état de leur dernière exécution. */
interface CountrySummary {
  iso: string;
  total: number;
  ok: number;
  problems: number;
  never: number;
}

/** Filtres rapides (tuiles de santé) : un seul à la fois, chacun correspond à un filtre précis. */
export type QuickKey = 'ALL' | 'OK' | 'PROBLEM' | 'LATE' | 'NEVER' | 'PLUGIN_MISSING' | 'DISABLED';

/** Indicateurs de santé de l'ensemble des parsers (avant filtres), affichés en tuiles cliquables. */
interface Kpis {
  total: number;
  ok: number;
  problems: number;
  late: number;
  never: number;
  pluginMissing: number;
  disabled: number;
  persons: number;
}

/** Un groupe de la liste : un pays, ses parsers et leur santé. */
interface ParserGroup {
  iso: string;
  label: string;
  parsers: ParserDTO[];
  ok: number;
  problems: number;
  never: number;
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
  readonly sortOptions = SORT_OPTIONS;

  // Liste : tous les parsers sont chargés, la recherche et la pagination se font ici
  allParsers: ParserDTO[] = [];
  /** Parsers qui répondent aux filtres, et la page affichée. */
  filtered: ParserDTO[] = [];
  parsers: ParserDTO[] = [];
  totalElements = 0;
  page = 0;
  totalPages = 0;
  pageSize = 20;
  readonly pageSizes = PAGE_SIZES;

  // Recherche et filtres (repris dans l'adresse de la page, voir writeUrl)
  search = '';
  filterCountry = '';
  filterListType: ListType | '' = '';
  filterEnabled: '' | 'enabled' | 'disabled' = '';
  /** Dernière exécution : un statut, NEVER (jamais exécuté) ou PROBLEM (échec ou bloquée). */
  filterLastStatus = '';
  /** Catégorie PEP couverte (ex. FR-g). */
  filterCategory = '';
  /** Nature de la source (T1 à T7). */
  filterKind: ParserKind | '' = '';
  /** Dernier test : NEVER, PASSED ou FAILED. */
  filterTest: '' | 'NEVER' | 'PASSED' | 'FAILED' = '';
  /** Santé : LATE (exécution en retard sur sa fréquence) ou PLUGIN_MISSING (plugin non chargé). */
  filterHealth: '' | 'LATE' | 'PLUGIN_MISSING' = '';
  /** Filtres repliés sur petit écran. */
  showFilters = false;

  // Tri, regroupement par pays et lignes dépliées
  sortKey: SortKey = 'name';
  sortDir: 'asc' | 'desc' = 'asc';
  /** Vue « Par pays » (accordéons, par défaut) ou « Liste » à plat paginée. */
  groupByCountry = true;
  /** Parsers affichés, regroupés par pays en vue « Par pays » (un seul groupe vide en vue « Liste »). */
  groups: ParserGroup[] = [];
  expanded = new Set<number>();
  /** Pays dépliés en vue « Par pays ». */
  openCountries = new Set<string>();
  /** Après une action de l'utilisateur sur les filtres : déplier tous les pays du résultat. */
  private autoOpen = false;
  /** Premier chargement fait : les pays à problèmes sont dépliés une seule fois. */
  private initialOpenDone = false;

  /** Onglet affiché : parsers ou plugins. */
  tab: 'parsers' | 'plugins' = 'parsers';
  /** Menu « ⋯ » ouvert (id du parser). */
  menuFor?: number;
  /** Filtres peu fréquents (source, test, catégorie, type) repliés derrière « Filtres ». */
  showAdvanced = false;
  /** Recherche dans la navigation des pays. */
  countrySearch = '';
  /** Santé de l'ensemble des parsers. */
  kpis: Kpis = { total: 0, ok: 0, problems: 0, late: 0, never: 0, pluginMissing: 0, disabled: 0, persons: 0 };

  /** Pays couverts par chaque parser (son pays et celui de ses fonctions), et liste des pays pour le filtre. */
  private parserCountries = new Map<number, Set<string>>();
  countryOptions: string[] = [];
  /** Catégories PEP couvertes par chaque parser, et liste des catégories pour le filtre. */
  private parserCategories = new Map<number, Set<string>>();
  categoryOptions: string[] = [];
  /** Nom des pays du référentiel (code ISO → nom). */
  countryNames = new Map<string, string>();
  /** Bandeau par pays : nombre de parsers, publiés, en échec ou bloqués, jamais exécutés. */
  countrySummaries: CountrySummary[] = [];

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
    private route: ActivatedRoute,
    private router: Router,
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
    this.readUrl();
    this.referenceService.countries().subscribe({
      next: countries => (this.countryNames = new Map(countries.map(c => [c.iso, c.name]))),
    });
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
    if (!this.allParsers.length) {
      // Fonctions ou plugins lus avant les parsers : garder la page et les filtres de l'adresse
      this.filtered = [];
      this.parsers = [];
      this.groups = [];
      return;
    }
    this.computeCountries();
    const words = normalize(this.search).split(/\s+/).filter(w => w);
    this.filtered = this.allParsers.filter(p => {
      const countries = this.parserCountries.get(p.id!) ?? new Set<string>();
      if (this.filterCountry && !countries.has(this.filterCountry)) {
        return false;
      }
      if (this.filterCategory && !this.parserCategories.get(p.id!)?.has(this.filterCategory)) {
        return false;
      }
      if (this.filterKind && p.parserKind !== this.filterKind) {
        return false;
      }
      if (this.filterTest) {
        const ok = this.filterTest === 'NEVER' ? !p.lastTestStatus : p.lastTestStatus === this.filterTest;
        if (!ok) {
          return false;
        }
      }
      if (this.filterHealth === 'LATE' && !this.isLate(p)) {
        return false;
      }
      if (this.filterHealth === 'PLUGIN_MISSING' && !this.isPluginMissing(p)) {
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
          : this.filterLastStatus === 'OK' ? p.lastStatus === 'SUCCESS' || p.lastStatus === 'UNCHANGED'
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
    this.sortFiltered();
    // Vue « Par pays » : tout le résultat, sans pagination ; vue « Liste » : une page à la fois
    this.totalPages = this.groupByCountry ? 1 : Math.ceil(this.filtered.length / this.pageSize);
    if (resetPage || this.page >= this.totalPages) {
      this.page = 0;
    }
    // Une recherche ou un filtre choisis par l'utilisateur déplient les pays du résultat
    this.autoOpen = resetPage && this.hasFilters;
    this.slicePage();
    this.openInitialCountries();
    this.writeUrl();
  }

  /** Premier affichage : seuls les pays à problèmes (et le pays demandé dans l'adresse) sont dépliés. */
  private openInitialCountries(): void {
    if (this.initialOpenDone || !this.countrySummaries.length) {
      return;
    }
    this.initialOpenDone = true;
    this.countrySummaries.filter(c => c.problems > 0).forEach(c => this.openCountries.add(c.iso));
    if (this.filterCountry) {
      this.openCountries.add(this.filterCountry);
    }
  }

  // ---------------------------------------------------------------- filtres rapides et navigation

  /** Filtre rapide actif : celui dont le filtre est le seul posé (hors recherche, pays et filtres de détail). */
  get quick(): QuickKey {
    if (this.filterListType || this.filterCategory || this.filterKind || this.filterTest) {
      return 'ALL';
    }
    const set = [this.filterLastStatus, this.filterHealth, this.filterEnabled].filter(f => f);
    if (set.length !== 1) {
      return 'ALL';
    }
    if (this.filterLastStatus === 'PROBLEM') { return 'PROBLEM'; }
    if (this.filterLastStatus === 'OK') { return 'OK'; }
    if (this.filterLastStatus === 'NEVER') { return 'NEVER'; }
    if (this.filterHealth === 'LATE') { return 'LATE'; }
    if (this.filterHealth === 'PLUGIN_MISSING') { return 'PLUGIN_MISSING'; }
    if (this.filterEnabled === 'disabled') { return 'DISABLED'; }
    return 'ALL';
  }


  /** Tuile de santé : pose le filtre correspondant, ou l'enlève si elle est déjà active. */
  setQuick(key: QuickKey): void {
    const current = this.quick === key && key !== 'ALL';
    this.filterLastStatus = '';
    this.filterHealth = '';
    this.filterEnabled = '';
    if (!current) {
      switch (key) {
        case 'PROBLEM': this.filterLastStatus = 'PROBLEM'; break;
        case 'OK': this.filterLastStatus = 'OK'; break;
        case 'NEVER': this.filterLastStatus = 'NEVER'; break;
        case 'LATE': this.filterHealth = 'LATE'; break;
        case 'PLUGIN_MISSING': this.filterHealth = 'PLUGIN_MISSING'; break;
        case 'DISABLED': this.filterEnabled = 'disabled'; break;
        default: break;
      }
    }
    this.applyFilters();
  }

  /** Vue « Par pays » ou « Liste ». */
  setGrouping(byCountry: boolean): void {
    this.groupByCountry = byCountry;
    this.applyFilters(true);
  }

  /** Pays de la navigation, filtrés par la recherche de pays. */
  get visibleCountries(): CountrySummary[] {
    const q = normalize(this.countrySearch.trim());
    return q ? this.countrySummaries.filter(c => normalize(this.countryName(c.iso) + ' ' + c.iso).includes(q))
      : this.countrySummaries;
  }

  isCountryOpen(iso: string): boolean {
    return this.openCountries.has(iso);
  }

  toggleCountry(iso: string): void {
    if (!this.openCountries.delete(iso)) {
      this.openCountries.add(iso);
    }
  }

  /** « Tout déplier » / « Tout replier » pour les pays affichés. */
  setAllCountries(open: boolean): void {
    this.openCountries = open ? new Set(this.groups.map(g => g.iso)) : new Set<string>();
  }

  get allCountriesOpen(): boolean {
    return this.groups.length > 0 && this.groups.every(g => this.openCountries.has(g.iso));
  }

  // ---------------------------------------------------------------- menu et libellés

  trackParser = (_: number, p: ParserDTO) => p.id;
  trackParserGroup = (_: number, g: ParserGroup) => g.iso;
  trackChip = (_: number, chip: { key: string }) => chip.key;

  /** Phrase de l'info-bulle de la pastille de santé d'une ligne. */
  healthLabel(p: ParserDTO): string {
    switch (this.health(p)) {
      case 'ok': return 'Publié ou inchangé, à jour';
      case 'problem': return p.lastStatus === 'BLOCKED' ? 'Dernière exécution bloquée par le contrôle qualité' : 'Dernière exécution en échec';
      case 'late': return 'En retard sur sa fréquence de mise à jour';
      case 'never': return 'Jamais exécuté';
      default: return 'Désactivé';
    }
  }

  toggleMenu(p: ParserDTO): void {
    this.menuFor = this.menuFor === p.id ? undefined : p.id;
  }

  closeMenu(): void {
    this.menuFor = undefined;
  }

  /** « il y a 3 h », « hier », « il y a 12 j » ; la date exacte est dans l'info-bulle. */
  relativeTime(iso?: string): string {
    if (!iso) {
      return '';
    }
    const minutes = Math.round((Date.now() - new Date(iso).getTime()) / 60_000);
    if (minutes < 1) { return 'à l\'instant'; }
    if (minutes < 60) { return `il y a ${minutes} min`; }
    const hours = Math.round(minutes / 60);
    if (hours < 24) { return `il y a ${hours} h`; }
    const days = Math.round(hours / 24);
    if (days === 1) { return 'hier'; }
    if (days < 60) { return `il y a ${days} j`; }
    return `il y a ${Math.round(days / 30)} mois`;
  }

  /** Libellé court de la nature de la source (« T2 »), l'intitulé complet est en info-bulle. */
  kindShort(kind?: ParserKind): string {
    const i = PARSER_KINDS.findIndex(k => k.value === kind);
    return i >= 0 ? `T${i + 1}` : '';
  }

  /** Santé résumée d'un parser, pour la pastille de la ligne : problème, en retard, jamais exécuté ou sain. */
  health(p: ParserDTO): 'problem' | 'late' | 'never' | 'ok' | 'disabled' {
    if (!p.enabled) { return 'disabled'; }
    if (p.lastStatus === 'FAILED' || p.lastStatus === 'BLOCKED') { return 'problem'; }
    if (!p.lastStatus) { return 'never'; }
    return this.isLate(p) ? 'late' : 'ok';
  }

  /** Nombre de filtres actifs (hors recherche), affiché sur le bouton « Filtres » des petits écrans. */
  get activeFilterCount(): number {
    return [this.filterCountry, this.filterListType, this.filterEnabled, this.filterLastStatus, this.filterCategory,
      this.filterKind, this.filterTest, this.filterHealth].filter(f => f).length;
  }

  get hasFilters(): boolean {
    return !!this.search.trim() || this.activeFilterCount > 0;
  }

  clearFilters(): void {
    this.search = '';
    this.filterCountry = '';
    this.filterListType = '';
    this.filterEnabled = '';
    this.filterLastStatus = '';
    this.filterCategory = '';
    this.filterKind = '';
    this.filterTest = '';
    this.filterHealth = '';
    this.applyFilters();
  }

  /** Clic sur un pays du bandeau : filtre sur ce pays, ou retire le filtre s'il est déjà actif. */
  selectCountry(iso: string): void {
    this.filterCountry = this.filterCountry === iso ? '' : iso;
    this.filterCategory = '';           // les catégories dépendent du pays
    this.applyFilters();
  }

  /** Clic sur l'en-tête d'une colonne : trie sur elle, ou inverse l'ordre si elle est déjà triée. */
  sortBy(key: SortKey): void {
    if (this.sortKey === key) {
      this.sortDir = this.sortDir === 'asc' ? 'desc' : 'asc';
    } else {
      this.sortKey = key;
      this.sortDir = key === 'lastRun' || key === 'persons' ? 'desc' : 'asc';
    }
    this.applyFilters(false);
  }

  setPageSize(size: number): void {
    this.pageSize = size;
    this.applyFilters();
  }

  toggleExpanded(p: ParserDTO): void {
    if (this.expanded.has(p.id!)) {
      this.expanded.delete(p.id!);
    } else {
      this.expanded.add(p.id!);
    }
  }

  /** Pays principal d'un parser : le sien, sinon le premier de ses fonctions. */
  primaryCountry(p: ParserDTO): string {
    return p.countryIso ?? [...(this.parserCountries.get(p.id!) ?? [])][0] ?? '';
  }

  countryName(iso: string): string {
    return this.countryNames.get(iso) ?? iso;
  }

  /**
   * En retard : parser actif et planifié dont la dernière exécution date de plus d'une fois et demie sa fréquence
   * (marge pour une exécution lente), ou qui n'a jamais été exécuté.
   */
  isLate(p: ParserDTO): boolean {
    if (!p.enabled || !p.refreshHours) {
      return false;
    }
    if (!p.lastRunAt) {
      return true;
    }
    return Date.now() - new Date(p.lastRunAt).getTime() > p.refreshHours * 1.5 * 3_600_000;
  }

  /** Plugin non chargé : aucun plugin démarré ne porte ce Plugin-Id (liste des plugins pas encore lue : non). */
  isPluginMissing(p: ParserDTO): boolean {
    return this.plugins.length > 0 && !this.plugins.some(pl => pl.pluginId === p.pluginId && pl.state === 'STARTED');
  }

  private sortFiltered(): void {
    const rank = (p: ParserDTO) => p.lastStatus === 'FAILED' || p.lastStatus === 'BLOCKED' ? 0 : !p.lastStatus ? 1
      : this.isLate(p) ? 2 : 3;
    const value = (p: ParserDTO): string | number => {
      switch (this.sortKey) {
        case 'country': return this.primaryCountry(p) + ' ' + normalize(p.name ?? '');
        case 'lastRun': return p.lastRunAt ? new Date(p.lastRunAt).getTime() : 0;
        case 'persons': return p.lastPersonCount ?? 0;
        case 'status': return rank(p);
        default: return normalize(p.name ?? '');
      }
    };
    const dir = this.sortDir === 'asc' ? 1 : -1;
    const byKey = (a: ParserDTO, b: ParserDTO) => {
      const va = value(a);
      const vb = value(b);
      return (va < vb ? -1 : va > vb ? 1 : 0) * dir || normalize(a.name ?? '').localeCompare(normalize(b.name ?? ''));
    };
    // Regroupé par pays : les pays d'abord, dans l'ordre alphabétique, puis le tri choisi dans chaque pays
    this.filtered.sort(this.groupByCountry
      ? (a, b) => this.primaryCountry(a).localeCompare(this.primaryCountry(b)) || byKey(a, b)
      : byKey);
  }

  /** Filtres, tri et page lus dans l'adresse (/parsers?pays=FR&execution=PROBLEM…). */
  private readUrl(): void {
    const q = this.route.snapshot.queryParamMap;
    this.search = q.get('q') ?? '';
    this.filterCountry = q.get('pays') ?? '';
    this.filterListType = (q.get('type') ?? '') as ListType | '';
    this.filterEnabled = (q.get('etat') ?? '') as '' | 'enabled' | 'disabled';
    this.filterLastStatus = q.get('execution') ?? '';
    this.filterCategory = q.get('categorie') ?? '';
    this.filterKind = (q.get('source') ?? '') as ParserKind | '';
    this.filterTest = (q.get('test') ?? '') as '' | 'NEVER' | 'PASSED' | 'FAILED';
    this.filterHealth = (q.get('sante') ?? '') as '' | 'LATE' | 'PLUGIN_MISSING';
    this.sortKey = (SORT_OPTIONS.some(o => o.value === q.get('tri')) ? q.get('tri') : 'name') as SortKey;
    this.sortDir = q.get('ordre') === 'desc' ? 'desc' : 'asc';
    this.groupByCountry = q.get('vue') !== 'liste';
    this.tab = q.get('onglet') === 'plugins' ? 'plugins' : 'parsers';
    const size = Number(q.get('taille'));
    this.pageSize = PAGE_SIZES.includes(size) ? size : 20;
    this.page = Math.max(0, Number(q.get('page') ?? 1) - 1) || 0;
  }

  /** Écrit les filtres dans l'adresse, sans nouvelle entrée d'historique : un rafraîchissement ou un lien les garde. */
  private writeUrl(): void {
    const params: Record<string, string | null> = {
      q: this.search.trim() || null,
      pays: this.filterCountry || null,
      type: this.filterListType || null,
      etat: this.filterEnabled || null,
      execution: this.filterLastStatus || null,
      categorie: this.filterCategory || null,
      source: this.filterKind || null,
      test: this.filterTest || null,
      sante: this.filterHealth || null,
      tri: this.sortKey !== 'name' ? this.sortKey : null,
      ordre: this.sortDir === 'desc' ? 'desc' : null,
      vue: this.groupByCountry ? null : 'liste',
      onglet: this.tab === 'plugins' ? 'plugins' : null,
      taille: this.pageSize !== 20 ? String(this.pageSize) : null,
      page: this.page > 0 ? String(this.page + 1) : null,
    };
    this.router.navigate([], { relativeTo: this.route, queryParams: params, replaceUrl: true });
  }

  /** Un parser couvre son pays et celui de chacune de ses fonctions PEP (un parser UE en couvre plusieurs). */
  private computeCountries(): void {
    const functionById = new Map(this.allFunctions.map(f => [f.id, f]));
    this.parserCountries.clear();
    this.parserCategories.clear();
    for (const p of this.allParsers) {
      const countries = new Set<string>();
      const categories = new Set<string>();
      if (p.countryIso) {
        countries.add(p.countryIso);
      }
      for (const c of p.coverage ?? []) {
        const f = functionById.get(c.functionId);
        if (f) {
          countries.add(f.countryIso);
          categories.add(f.categoryCode);
        }
      }
      this.parserCountries.set(p.id!, countries);
      this.parserCategories.set(p.id!, categories);
    }
    this.countryOptions = [...new Set([...this.parserCountries.values()].flatMap(s => [...s]))].sort();
    // Catégories du pays filtré (toutes sans filtre pays), dans l'ordre des codes
    this.categoryOptions = [...new Set(this.allFunctions
      .filter(f => !this.filterCountry || f.countryIso === this.filterCountry)
      .filter(f => this.allParsers.some(p => this.parserCategories.get(p.id!)?.has(f.categoryCode)))
      .map(f => f.categoryCode))].sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
    this.countrySummaries = this.countryOptions.map(iso => {
      const ofCountry = this.allParsers.filter(p => this.parserCountries.get(p.id!)?.has(iso));
      return {
        iso,
        total: ofCountry.length,
        ok: ofCountry.filter(p => p.lastStatus === 'SUCCESS' || p.lastStatus === 'UNCHANGED').length,
        problems: ofCountry.filter(p => p.lastStatus === 'FAILED' || p.lastStatus === 'BLOCKED').length,
        never: ofCountry.filter(p => !p.lastStatus).length,
      };
    });
    this.kpis = {
      total: this.allParsers.length,
      ok: this.allParsers.filter(p => p.lastStatus === 'SUCCESS' || p.lastStatus === 'UNCHANGED').length,
      problems: this.allParsers.filter(p => p.lastStatus === 'FAILED' || p.lastStatus === 'BLOCKED').length,
      late: this.allParsers.filter(p => this.isLate(p)).length,
      never: this.allParsers.filter(p => !p.lastStatus).length,
      pluginMissing: this.allParsers.filter(p => this.isPluginMissing(p)).length,
      disabled: this.allParsers.filter(p => !p.enabled).length,
      persons: this.allParsers.reduce((sum, p) => sum + (p.lastPersonCount ?? 0), 0),
    };
  }

  private slicePage(): void {
    const start = this.page * this.pageSize;
    this.parsers = this.groupByCountry ? this.filtered : this.filtered.slice(start, start + this.pageSize);
    // Regroupement par pays (les parsers sont déjà triés par pays) ; la vue « Liste » n'a qu'un groupe sans titre
    this.groups = [];
    for (const p of this.parsers) {
      const iso = this.groupByCountry ? this.primaryCountry(p) : '';
      let group = this.groups[this.groups.length - 1];
      if (!group || group.iso !== iso) {
        group = { iso, label: iso ? this.countryName(iso) : 'Sans pays', parsers: [], ok: 0, problems: 0, never: 0 };
        this.groups.push(group);
      }
      group.parsers.push(p);
      const health = this.health(p);
      if (health === 'ok' || health === 'late') {
        group.ok++;
      } else if (health === 'problem') {
        group.problems++;
      } else if (health === 'never') {
        group.never++;
      }
    }
    if (this.autoOpen) {
      this.openCountries = new Set(this.groups.map(g => g.iso));
      this.autoOpen = false;
    }
  }

  changePage(page: number): void {
    if (page >= 0 && page < this.totalPages) {
      this.page = page;
      this.slicePage();
      this.writeUrl();
    }
  }

  // ---------------------------------------------------------------- exécuter tout / tester tout

  /**
   * Prépare le lot sur les parsers affichés par les filtres (toutes pages) et ouvre la confirmation.
   * Les parsers désactivés ou déjà en cours d'exécution ou de test sont exclus.
   */
  openBulk(mode: BulkRun['mode'], subset?: ParserDTO[]): void {
    if (this.bulk?.phase === 'running') {
      this.isBulkDialogOpen = true;
      return;
    }
    const busy = (p: ParserDTO) => this.isRunning(p) || this.testing.has(p.id!);
    const scope = subset ?? this.filtered;           // un pays (en-tête de groupe) ou tout le résultat des filtres
    const targets = scope.filter(p => p.enabled && !busy(p));
    this.bulk = {
      mode,
      items: targets.map(parser => ({ parser, state: 'pending' })),
      excluded: scope.filter(p => !p.enabled || busy(p)),
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

  /** Nature de la source (« T2 – API REST ou OData »), vide si elle n'est pas renseignée. */
  kindLabel(kind?: ParserKind): string {
    return PARSER_KINDS.find(k => k.value === kind)?.label ?? '';
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
      { completeness: c.completeness, includesFormer: c.includesFormer, coveredScope: c.coveredScope, gaps: c.gaps }]));
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

  /** Recherche dans la liste des plugins. */
  pluginSearch = '';

  get filteredPlugins(): PluginInfo[] {
    const q = normalize(this.pluginSearch.trim());
    return q ? this.plugins.filter(p => normalize(p.pluginId + ' ' + (p.path ?? '')).includes(q)) : this.plugins;
  }

  /** Nombre de parsers qui utilisent ce plugin. */
  pluginUsage(pluginId: string): number {
    return this.allParsers.filter(p => p.pluginId === pluginId).length;
  }

  /** Plugin-Id demandés par des parsers mais qu'aucun plugin démarré ne porte. */
  get missingPluginIds(): string[] {
    return [...new Set(this.allParsers.filter(p => this.isPluginMissing(p)).map(p => p.pluginId!))].sort();
  }

  setTab(tab: 'parsers' | 'plugins'): void {
    this.tab = tab;
    this.menuFor = undefined;
    this.writeUrl();
  }

  /** Filtres actifs sous forme de pastilles retirables, sous la barre de recherche. */
  get activeChips(): { key: string; label: string; clear: () => void }[] {
    const chips: { key: string; label: string; clear: () => void }[] = [];
    const add = (key: string, label: string, clear: () => void) => chips.push({ key, label, clear: () => { clear(); this.applyFilters(); } });
    if (this.filterCountry) { add('pays', this.countryName(this.filterCountry), () => { this.filterCountry = ''; this.filterCategory = ''; }); }
    if (this.filterCategory) { add('cat', 'Catégorie ' + this.filterCategory, () => (this.filterCategory = '')); }
    if (this.filterListType) { add('type', 'Liste ' + this.listTypeLabel(this.filterListType), () => (this.filterListType = '')); }
    if (this.filterKind) { add('kind', this.kindShort(this.filterKind) + ' ' + (PARSER_KINDS.find(k => k.value === this.filterKind)?.label.split('–')[1]?.trim() ?? ''), () => (this.filterKind = '')); }
    if (this.filterEnabled) { add('etat', this.filterEnabled === 'enabled' ? 'Actifs' : 'Désactivés', () => (this.filterEnabled = '')); }
    if (this.filterLastStatus) {
      const labels: Record<string, string> = { PROBLEM: 'En échec ou bloqués', OK: 'Publiés ou inchangés', SUCCESS: 'Publiés', UNCHANGED: 'Inchangés',
        BLOCKED: 'Bloqués', FAILED: 'En échec', NEVER: 'Jamais exécutés' };
      add('exec', labels[this.filterLastStatus] ?? this.filterLastStatus, () => (this.filterLastStatus = ''));
    }
    if (this.filterTest) { add('test', { FAILED: 'Test en échec', PASSED: 'Test réussi', NEVER: 'Jamais testés' }[this.filterTest], () => (this.filterTest = '')); }
    if (this.filterHealth) { add('sante', this.filterHealth === 'LATE' ? 'En retard' : 'Plugin non chargé', () => (this.filterHealth = '')); }
    return chips;
  }

  /** JAR connus du serveur (dossier des plugins et parsers), chargés ou non. */
  available: AvailablePlugin[] = [];
  /** Affichage de l'onglet Plugins : tous, chargés ou non chargés. */
  pluginView: 'all' | 'loaded' | 'notLoaded' = 'all';
  /** JAR en cours de chargement (boutons désactivés). */
  busyJars = new Set<string>();

  /** JAR lisibles qui ne sont pas chargés depuis ce fichier (plugin absent, ou autre version chargée). */
  get notLoadedJars(): AvailablePlugin[] {
    const q = normalize(this.pluginSearch.trim());
    return this.available.filter(a => !a.error && !a.loaded
      && (!q || normalize((a.pluginId ?? '') + ' ' + a.path).includes(q)));
  }

  /** Fichiers qui ne sont pas des plugins lisibles ou qui n'existent plus (indiqués par un parser). */
  get brokenJars(): AvailablePlugin[] {
    return this.available.filter(a => !!a.error);
  }

  get notLoadedCount(): number {
    return this.available.filter(a => !a.error && !a.loaded).length;
  }

  loadAvailable(): void {
    this.pluginService.available().subscribe({
      next: list => (this.available = list),
      error: err => (this.pluginError = apiError(err)),
    });
  }

  /** Charge un JAR de la liste « non chargés ». */
  loadFile(jar: AvailablePlugin): void {
    if (this.busyJars.has(jar.path)) {
      return;
    }
    this.busyJars.add(jar.path);
    this.pluginMessage = '';
    this.pluginError = '';
    this.pluginService.load(jar.path).subscribe({
      next: res => {
        this.busyJars.delete(jar.path);
        this.pluginMessage = `Plugin ${res.pluginId} chargé.`;
        this.loadPlugins();
      },
      error: err => {
        this.busyJars.delete(jar.path);
        this.pluginError = `${jar.pluginId ?? jar.path} : ${apiError(err)}`;
        this.loadPlugins();
      },
    });
  }

  /** Charge un par un tous les JAR non chargés ; les erreurs sont comptées, pas bloquantes. */
  loadAllMissing(): void {
    const queue = this.available.filter(a => !a.error && !a.loaded);
    if (!queue.length || !confirm(`Charger les ${queue.length} plugin(s) non chargé(s), un par un ?`)) {
      return;
    }
    this.pluginMessage = '';
    this.pluginError = '';
    let ok = 0;
    const failures: string[] = [];
    const next = (): void => {
      const jar = queue.shift();
      if (!jar) {
        this.pluginMessage = `${ok} plugin(s) chargé(s).`;
        this.pluginError = failures.length ? `${failures.length} échec(s) : ${failures.join(' ; ')}` : '';
        this.loadPlugins();
        return;
      }
      this.busyJars.add(jar.path);
      this.pluginService.load(jar.path).subscribe({
        next: () => { this.busyJars.delete(jar.path); ok++; next(); },
        error: err => { this.busyJars.delete(jar.path); failures.push(`${jar.pluginId ?? jar.path} (${apiError(err)})`); next(); },
      });
    };
    next();
  }

  loadPlugins(): void {
    this.pluginService.list().subscribe({
      next: plugins => {
        this.plugins = plugins;
        this.applyFilters(false);       // filtre « plugin non chargé »
        this.loadAvailable();
      },
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
