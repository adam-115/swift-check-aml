import { Component, HostListener, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import Swal from 'sweetalert2';
import {
  BatchVerificationService, BatchParseResult, BatchVerificationResult, BatchVerificationRow, BatchRowStatus
} from '../../services/batch-verification.service';
import { ParserService } from '../../services/parser.service';
import { ParserDTO } from '../../models/appTypes';
import { PepMediaService, PepPhoto } from '../../services/pep-media.service';

type StatusFilter = 'ALL' | BatchRowStatus;

/** Une ligne du tableau « saisi / trouvé » ; agrees vaut undefined quand on ne peut pas comparer. */
interface ComparisonLine {
  label: string;
  input?: string;
  found?: string;
  agrees?: boolean;
}

/** Correspondance ouverte dans le panneau de détail. */
interface MatchDetail {
  row: BatchVerificationRow;
  match: any;
  index: number;
  comparison: ComparisonLine[];
  /** Liens propres à la fiche (sourceUrl, site, liens cités dans les notes). */
  sources: string[];
  /** Source de données de chaque liste où figure la fiche : toujours affichée, même sans lien sur la fiche. */
  datasetSources: DatasetSource[];
  properties: { key: string; label: string; values: string[] }[];
}

/** Liste (dataset) et organisme qui la publie, d'après le référentiel des parsers. */
interface DatasetSource {
  id: string;
  name: string;
  websiteUrl?: string;
  sourceLocation?: string;
  licence?: string;
}

/** Photo chargée : ses informations et son fichier (adresse objet, la requête étant authentifiée). */
interface LoadedPhoto {
  photo: PepPhoto;
  objectUrl?: string;
}

/** Photos d'une entité : la principale (l'officielle) en premier. */
interface EntityPhotos {
  state: 'loading' | 'found' | 'none' | 'error';
  all: LoadedPhoto[];
  current?: LoadedPhoto;
}

/** Libellés français des propriétés FollowTheMoney les plus courantes, dans l'ordre d'affichage. */
const PROPERTY_LABELS: Record<string, string> = {
  name: 'Nom', alias: 'Alias', weakAlias: 'Alias faible', previousName: 'Ancien nom',
  title: 'Titre', firstName: 'Prénom', middleName: 'Deuxième prénom', lastName: 'Nom de famille',
  fatherName: 'Nom du père', motherName: 'Nom de la mère', gender: 'Sexe',
  birthDate: 'Date de naissance', birthPlace: 'Lieu de naissance', birthCountry: 'Pays de naissance', deathDate: 'Date de décès',
  nationality: 'Nationalité', citizenship: 'Citoyenneté', country: 'Pays', jurisdiction: 'Juridiction', address: 'Adresse',
  position: 'Fonction', political: 'Parti politique', education: 'Formation', religion: 'Religion',
  startDate: 'Début', endDate: 'Fin', topics: 'Thèmes', program: 'Programme de sanction', programId: 'Réf. programme',
  authority: 'Autorité', reason: 'Motif', summary: 'Résumé', description: 'Description', notes: 'Notes',
  idNumber: "N° d'identité", passportNumber: 'N° de passeport', taxNumber: 'N° fiscal', registrationNumber: "N° d'immatriculation",
  incorporationDate: 'Date de création', dissolutionDate: 'Date de dissolution', legalForm: 'Forme juridique', sector: 'Secteur',
  imoNumber: 'N° IMO', flag: 'Pavillon', email: 'E-mail', phone: 'Téléphone', website: 'Site web',
  sourceUrl: 'Source', wikidataId: 'Wikidata', wikipediaUrl: 'Wikipédia', keywords: 'Mots-clés',
  createdAt: 'Créé le', modifiedAt: 'Modifié le', retrievedAt: 'Récupéré le'
};

/** Thèmes FTM courants. */
const TOPIC_LABELS: Record<string, string> = {
  'role.pep': 'PEP', 'role.rca': 'Proche de PEP (RCA)', 'sanction': 'Sanction', 'sanction.linked': 'Lié à une sanction',
  'sanction.counter': 'Contre-mesures', 'debarment': 'Exclusion', 'crime': 'Criminalité', 'wanted': 'Recherché',
  'poi': 'Personne d’intérêt', 'gov.national': 'Gouvernement national', 'gov.state': 'Gouvernement régional',
  'gov.muni': 'Collectivité locale', 'gov.head': 'Chef d’État / de gouvernement', 'gov.legislative': 'Pouvoir législatif',
  'gov.executive': 'Pouvoir exécutif', 'gov.judicial': 'Pouvoir judiciaire', 'gov.security': 'Sécurité / défense',
  'gov.financial': 'Institution financière publique', 'gov.soe': 'Entreprise publique', 'gov.igo': 'Organisation internationale',
  'role.diplo': 'Diplomate', 'role.judge': 'Juge', 'pol.party': 'Parti politique'
};

/**
 * Vérification par lot : chargement d'un fichier CSV, aperçu des lignes reconnues, vérification de toutes
 * les entités puis rapport (à l'écran et en PDF).
 */
@Component({
  selector: 'app-aml-batch-verification',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './aml-batch-verification.component.html'
})
export class AmlBatchVerificationComponent implements OnInit, OnDestroy {

  file: File | null = null;
  dragOver = false;
  parsing = false;
  parsed: BatchParseResult | null = null;

  verifying = false;
  verifyStartedAt = 0;
  elapsedSeconds = 0;
  private timer: any = null;

  result: BatchVerificationResult | null = null;
  downloading = false;

  /** Bases Yente proposées ; vide = toutes (« default »). */
  datasets: { id: string; name: string }[] = [];
  /** Référentiel des sources par dataset (nom, site de l'organisme, licence). */
  private sourcesByDataset = new Map<string, DatasetSource>();
  selectedDatasets: string[] = [];
  datasetSearch = '';

  detail: MatchDetail | null = null;
  /** Photos par identifiant d'entité, chargées à la première ouverture du détail. */
  private photos: Record<string, EntityPhotos> = {};
  private objectUrls: string[] = [];
  private static readonly NO_PHOTO: EntityPhotos = { state: 'none', all: [] };

  statusFilter: StatusFilter = 'ALL';
  search = '';
  expandedLine: number | null = null;

  readonly statusLabels: Record<BatchRowStatus, string> = {
    HIT: 'Correspondance',
    COUNTRY_ALERT: 'Alerte pays',
    CLEAN: 'Aucun résultat',
    INVALID: 'Non vérifiée',
    ERROR: 'Erreur'
  };

  readonly statusClasses: Record<BatchRowStatus, string> = {
    HIT: 'bg-red-50 text-red-700 border-red-200',
    COUNTRY_ALERT: 'bg-amber-50 text-amber-700 border-amber-200',
    CLEAN: 'bg-emerald-50 text-emerald-700 border-emerald-200',
    INVALID: 'bg-slate-100 text-slate-600 border-slate-200',
    ERROR: 'bg-violet-50 text-violet-700 border-violet-200'
  };

  constructor(private batchService: BatchVerificationService, private parserService: ParserService,
              private pepMediaService: PepMediaService) { }

  ngOnDestroy(): void {
    this.stopTimer();
    this.objectUrls.forEach(u => URL.revokeObjectURL(u));
  }

  ngOnInit(): void {
    this.parserService.getAllForProviders().subscribe({
      next: (page: any) => {
        const parsers: ParserDTO[] = page.content || [];
        const unique = new Map<string, string>();
        parsers.forEach(p => {
          if (!p.datasetName) return;
          unique.set(p.datasetName, p.name || p.datasetName);
          this.sourcesByDataset.set(p.datasetName, {
            id: p.datasetName,
            name: p.name || p.datasetName,
            websiteUrl: this.toHref(p.websiteUrl) || undefined,
            sourceLocation: this.toHref(p.sourceLocation) || undefined,
            licence: p.licence || undefined
          });
        });
        this.datasets = Array.from(unique.entries())
          .map(([id, name]) => ({ id, name }))
          .sort((a, b) => a.name.localeCompare(b.name));
      },
      error: () => this.datasets = []
    });
  }

  // ---------------------------------------------------------------- fichier

  onDragOver(ev: DragEvent): void {
    ev.preventDefault();
    this.dragOver = true;
  }

  onDragLeave(ev: DragEvent): void {
    ev.preventDefault();
    this.dragOver = false;
  }

  onDrop(ev: DragEvent): void {
    ev.preventDefault();
    this.dragOver = false;
    const f = ev.dataTransfer?.files?.[0];
    if (f) this.selectFile(f);
  }

  onFileInput(ev: Event): void {
    const input = ev.target as HTMLInputElement;
    const f = input.files?.[0];
    if (f) this.selectFile(f);
    input.value = '';
  }

  private selectFile(f: File): void {
    if (!/\.(csv|txt)$/i.test(f.name)) {
      Swal.fire('Format non pris en charge', 'Veuillez choisir un fichier .csv.', 'warning');
      return;
    }
    if (f.size > 5 * 1024 * 1024) {
      Swal.fire('Fichier trop volumineux', 'La taille maximale est de 5 Mo.', 'warning');
      return;
    }
    this.file = f;
    this.parsed = null;
    this.result = null;
    this.parsing = true;
    this.batchService.parse(f).subscribe({
      next: res => {
        this.parsed = res;
        this.parsing = false;
      },
      error: (err: HttpErrorResponse) => {
        this.parsing = false;
        this.file = null;
        Swal.fire('Fichier illisible', this.errorMessage(err), 'error');
      }
    });
  }

  reset(): void {
    this.file = null;
    this.parsed = null;
    this.result = null;
    this.statusFilter = 'ALL';
    this.search = '';
    this.expandedLine = null;
  }

  downloadTemplate(): void {
    this.batchService.downloadTemplate().subscribe({
      next: blob => this.saveBlob(blob, 'modele_verification_lot.csv'),
      error: () => Swal.fire('Erreur', 'Impossible de télécharger le modèle.', 'error')
    });
  }

  // ---------------------------------------------------------------- bases

  get filteredDatasets() {
    const q = this.datasetSearch.trim().toLowerCase();
    return q ? this.datasets.filter(d => d.name.toLowerCase().includes(q) || d.id.toLowerCase().includes(q)) : this.datasets;
  }

  toggleDataset(id: string): void {
    const i = this.selectedDatasets.indexOf(id);
    if (i >= 0) this.selectedDatasets.splice(i, 1);
    else this.selectedDatasets.push(id);
  }

  // ---------------------------------------------------------------- vérification

  verify(): void {
    if (!this.parsed || this.parsed.validCount === 0) {
      Swal.fire('Rien à vérifier', 'Le fichier ne contient aucune ligne vérifiable.', 'warning');
      return;
    }
    const dataset = this.selectedDatasets.length ? this.selectedDatasets.join(',') : 'default';
    this.verifying = true;
    this.result = null;
    this.verifyStartedAt = Date.now();
    this.elapsedSeconds = 0;
    this.timer = setInterval(() => this.elapsedSeconds = Math.floor((Date.now() - this.verifyStartedAt) / 1000), 1000);

    this.batchService.verify(this.parsed.fileName, dataset, this.parsed.entities).subscribe({
      next: res => {
        this.stopTimer();
        this.verifying = false;
        this.result = res;
        this.statusFilter = res.hits > 0 ? 'HIT' : 'ALL';
      },
      error: (err: HttpErrorResponse) => {
        this.stopTimer();
        this.verifying = false;
        Swal.fire('Vérification impossible', this.errorMessage(err), 'error');
      }
    });
  }

  private stopTimer(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  downloadPdf(): void {
    if (!this.result) return;
    this.downloading = true;
    this.batchService.downloadReport(this.result).subscribe({
      next: blob => {
        this.downloading = false;
        const day = new Date().toISOString().substring(0, 10);
        this.saveBlob(blob, `rapport_verification_lot_${day}.pdf`);
      },
      error: () => {
        this.downloading = false;
        Swal.fire('Erreur', 'La génération du rapport PDF a échoué.', 'error');
      }
    });
  }

  // ---------------------------------------------------------------- affichage

  get filteredRows(): BatchVerificationRow[] {
    if (!this.result) return [];
    const q = this.search.trim().toLowerCase();
    return this.result.rows.filter(r =>
      (this.statusFilter === 'ALL' || r.status === this.statusFilter) &&
      (!q || (r.entity.name || '').toLowerCase().includes(q) || (r.entity.reference || '').toLowerCase().includes(q)));
  }

  countFor(status: StatusFilter): number {
    if (!this.result) return 0;
    if (status === 'ALL') return this.result.total;
    return this.result.rows.filter(r => r.status === status).length;
  }

  toggleRow(row: BatchVerificationRow): void {
    if (!row.matches?.length) return;
    this.expandedLine = this.expandedLine === row.entity.lineNumber ? null : row.entity.lineNumber;
  }

  // ---------------------------------------------------------------- détail d'une correspondance

  openDetail(row: BatchVerificationRow, match: any): void {
    const index = Math.max(0, row.matches.indexOf(match));
    const props: Record<string, string[]> = match.properties || {};

    const sources = new Set<string>();
    for (const key of ['sourceUrl', 'website', 'wikipediaUrl']) {
      (props[key] || []).map(v => this.toHref(v)).filter((h): h is string => !!h).forEach(h => sources.add(h));
    }
    // Liens de preuve cités dans les notes (« source: https://… ; extracted_at: … », « evidence: https://… »).
    for (const note of props['notes'] || []) {
      for (const url of note.match(/https?:\/\/[^\s;,()<>"]+/g) || []) sources.add(url.replace(/[.]+$/, ''));
    }
    const datasetSources: DatasetSource[] = (match.datasets || []).map((ds: string) =>
      this.sourcesByDataset.get(ds) || { id: ds, name: ds });
    this.loadPhotos(match.id);

    const known = Object.keys(PROPERTY_LABELS);
    const keys = Object.keys(props).sort((a, b) => {
      const ia = known.indexOf(a), ib = known.indexOf(b);
      return (ia < 0 ? 999 : ia) - (ib < 0 ? 999 : ib) || a.localeCompare(b);
    });
    const properties = keys
      .filter(k => (props[k] || []).length)
      .map(k => ({
        key: k,
        label: PROPERTY_LABELS[k] || k,
        values: k === 'topics' ? props[k].map(t => TOPIC_LABELS[t] ? `${TOPIC_LABELS[t]} (${t})` : t) : props[k]
      }));

    this.detail = { row, match, index, comparison: this.compare(row, props), sources: Array.from(sources), datasetSources, properties };
  }

  stepDetail(delta: number): void {
    if (!this.detail) return;
    const next = this.detail.row.matches[this.detail.index + delta];
    if (next) this.openDetail(this.detail.row, next);
  }

  closeDetail(): void {
    this.detail = null;
  }

  @HostListener('document:keydown', ['$event'])
  onKey(ev: KeyboardEvent): void {
    if (!this.detail) return;
    if (ev.key === 'Escape') this.closeDetail();
    else if (ev.key === 'ArrowRight') this.stepDetail(1);
    else if (ev.key === 'ArrowLeft') this.stepDetail(-1);
  }

  /** Adresse cliquable d'une valeur (http(s):// ou www.), sinon null. */
  toHref(v: unknown): string | null {
    if (typeof v !== 'string') return null;
    const s = v.trim();
    if (/^https?:\/\/\S+$/i.test(s)) return s;
    if (/^www\.\S+\.\S+$/i.test(s)) return 'https://' + s;
    return null;
  }

  hostOf(url: string): string {
    try {
      return new URL(url).hostname.replace(/^www\./, '');
    } catch {
      return url;
    }
  }

  /** Photos de l'entité affichée dans le dialogue. */
  get photo(): EntityPhotos {
    return (this.detail && this.photos[this.detail.match.id]) || AmlBatchVerificationComponent.NO_PHOTO;
  }

  /** Charge une seule fois les photos d'une entité (la première est la principale). */
  private loadPhotos(entityId: string): void {
    if (!entityId || this.photos[entityId] || entityId.startsWith('fatf-geo-')) return;
    const entry: EntityPhotos = { state: 'loading', all: [] };
    this.photos[entityId] = entry;
    this.pepMediaService.lookup(entityId).subscribe({
      next: photos => {
        if (!photos.length) {
          entry.state = 'none';
          return;
        }
        entry.all = photos.map(photo => ({ photo }));
        entry.current = entry.all[0];
        entry.all.forEach((p, i) => this.pepMediaService.file(p.photo.id).subscribe({
          next: blob => {
            p.objectUrl = URL.createObjectURL(blob);
            this.objectUrls.push(p.objectUrl);
            if (i === 0) entry.state = 'found';
          },
          error: () => { if (i === 0) entry.state = 'error'; }
        }));
      },
      error: () => entry.state = 'error'
    });
  }

  private compare(row: BatchVerificationRow, props: Record<string, string[]>): ComparisonLine[] {
    const e = row.entity;
    const join = (vals?: string[]) => (vals || []).join(', ');
    const lines: ComparisonLine[] = [];

    const names = [...(props['name'] || []), ...(props['alias'] || [])];
    lines.push({ label: 'Nom', input: e.name, found: join(props['name']) + (props['alias']?.length ? ` (alias : ${join(props['alias'].slice(0, 3))})` : '') });
    if (e.name && names.length) {
      lines[0].agrees = names.some(n => this.norm(n) === this.norm(e.name!)) ? true : undefined;
    }

    // Dates : concordance si la fiche contient une date compatible avec la saisie (même jour, ou même année si l'une est partielle).
    const births = props['birthDate'] || [];
    const birth: ComparisonLine = { label: 'Date de naissance', input: this.formatDate(e.birthDate), found: births.map(b => this.formatDate(b)).join(', ') };
    if (e.birthDate && births.length) {
      birth.agrees = births.some(b => b.startsWith(e.birthDate!) || e.birthDate!.startsWith(b));
    }
    lines.push(birth);

    const nats = [...(props['nationality'] || []), ...(props['citizenship'] || [])].map(n => n.toLowerCase());
    const nat: ComparisonLine = { label: 'Nationalité', input: e.nationality?.toUpperCase(), found: nats.map(n => n.toUpperCase()).join(', ') };
    if (e.nationality && nats.length) nat.agrees = nats.includes(e.nationality.toLowerCase());
    lines.push(nat);

    const countries = (props['country'] || []).map(c => c.toLowerCase());
    if (e.country || countries.length) {
      const c: ComparisonLine = { label: 'Pays', input: e.country?.toUpperCase(), found: countries.map(x => x.toUpperCase()).join(', ') };
      if (e.country && countries.length) c.agrees = countries.includes(e.country.toLowerCase());
      lines.push(c);
    }

    if (e.birthPlace || props['birthPlace']?.length) {
      lines.push({ label: 'Lieu de naissance', input: e.birthPlace, found: join(props['birthPlace']) });
    }
    return lines;
  }

  private norm(s: string): string {
    return s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim()
      .split(' ').sort().join(' ');
  }

  formatDate(iso?: string): string {
    if (!iso) return '';
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
    return m ? `${m[3]}/${m[2]}/${m[1]}` : iso;
  }

  formatDuration(ms: number): string {
    if (ms < 1000) return `${ms} ms`;
    const s = Math.round(ms / 1000);
    return s < 60 ? `${s} s` : `${Math.floor(s / 60)} min ${s % 60} s`;
  }

  scorePercent(score?: number): string {
    return score == null ? '' : `${Math.round(score * 100)} %`;
  }

  riskClass(risk?: string): string {
    switch ((risk || '').toUpperCase()) {
      case 'CRITICAL': return 'bg-red-600 text-white';
      case 'HIGH': return 'bg-orange-500 text-white';
      case 'MEDIUM': return 'bg-amber-400 text-slate-900';
      case 'LOW': return 'bg-slate-300 text-slate-800';
      default: return '';
    }
  }

  private saveBlob(blob: Blob, name: string): void {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    a.click();
    URL.revokeObjectURL(url);
  }

  private errorMessage(err: HttpErrorResponse): string {
    if (err.status === 413) return 'Fichier trop volumineux (5 Mo au maximum).';
    return err.error?.message || err.message || 'Erreur inconnue';
  }
}
