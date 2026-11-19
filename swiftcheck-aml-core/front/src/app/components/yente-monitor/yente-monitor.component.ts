import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { YenteDatasetState, YenteDatasetStatusDTO, YenteEventDTO, YenteStatusDTO } from '../../models/appTypes';
import { YenteService } from '../../services/yente.service';
import { KeycloakService } from '../../services/keycloak.service';
import { apiError } from '../../services/api-error';
import { fileSize } from '../shared/format';

/** Libellé, explication et couleur de chaque état de dataset. */
const STATES: Record<YenteDatasetState, { label: string; help: string; css: string }> = {
  OK:              { label: 'À jour',             help: 'Servi par Yente dans la version publiée.', css: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20' },
  INDEXING:        { label: 'Indexation',         help: 'Yente indexe la nouvelle version.', css: 'bg-blue-50 text-blue-700 ring-blue-600/20' },
  OUTDATED:        { label: 'En attente',         help: 'Nouvelle version publiée, indexée au prochain passage de Yente (2 min) ou après l\'indexation en cours.', css: 'bg-amber-50 text-amber-700 ring-amber-600/20' },
  FAILED:          { label: 'Échec',              help: 'La dernière indexation a échoué (voir l\'historique).', css: 'bg-rose-50 text-rose-700 ring-rose-600/20' },
  NOT_SERVED:      { label: 'Non servi',          help: 'Aucun index en service : le dataset est introuvable par le criblage.', css: 'bg-rose-50 text-rose-700 ring-rose-600/20' },
  NOT_LOADED:      { label: 'Non chargé',         help: 'Dans le manifest, mais Yente ne l\'a pas encore relu.', css: 'bg-rose-50 text-rose-700 ring-rose-600/20' },
  NOT_IN_MANIFEST: { label: 'Absent du manifest', help: 'Le parser a publié, mais le dataset n\'est pas dans le manifest de Yente.', css: 'bg-rose-50 text-rose-700 ring-rose-600/20' },
  COUNT_MISMATCH:  { label: 'Nombre différent',   help: 'Servi à jour, mais le nombre d\'entités indexées diffère du fichier publié.', css: 'bg-amber-50 text-amber-700 ring-amber-600/20' },
  NOT_PUBLISHED:   { label: 'Jamais publié',      help: 'Parser actif qui n\'a encore rien publié (hors pourcentage).', css: 'bg-slate-100 text-slate-600 ring-slate-500/20' },
  ORPHAN:          { label: 'Orphelin',           help: 'Servi par Yente, sans parser actif ni entrée dans le manifest (hors pourcentage).', css: 'bg-slate-100 text-slate-600 ring-slate-500/20' },
};

const EVENTS: Record<string, string> = {
  reindex_started: 'Début',
  reindex_completed: 'Fin',
  index_alias_rollover_complete: 'Mis en service',
  reindex_failed: 'Échec',
  cleanup_index_deleted: 'Ancien index supprimé',
};

type Filter = 'ALL' | 'PROBLEMS' | 'INDEXING' | 'OK' | 'NOT_PUBLISHED' | 'ORPHAN';
const PROBLEMS: YenteDatasetState[] = ['NOT_IN_MANIFEST', 'NOT_LOADED', 'FAILED', 'NOT_SERVED', 'OUTDATED', 'COUNT_MISMATCH'];

/**
 * Écran « Yente » (/yente) : chaque dataset publié est-il chargé et indexé dans la bonne version ? Pourcentage
 * global, avancement de l'indexation en cours et historique par dataset. Rafraîchi toutes les 5 s pendant une
 * indexation, toutes les 30 s sinon.
 */
@Component({
  selector: 'app-yente-monitor',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './yente-monitor.component.html',
})
export class YenteMonitorComponent implements OnInit, OnDestroy {
  readonly states = STATES;
  readonly fileSize = fileSize;
  readonly isAdmin: boolean;
  readonly filters: { key: Filter; label: string }[] = [
    { key: 'ALL', label: 'Tous' },
    { key: 'PROBLEMS', label: 'Problèmes' },
    { key: 'INDEXING', label: 'En cours' },
    { key: 'OK', label: 'À jour' },
    { key: 'NOT_PUBLISHED', label: 'Jamais publiés' },
    { key: 'ORPHAN', label: 'Orphelins' },
  ];

  status: YenteStatusDTO | null = null;
  isLoading = true;
  errorMessage = '';
  message = '';
  filter: Filter = 'ALL';
  search = '';
  isRebuilding = false;

  selected: YenteDatasetStatusDTO | null = null;
  events: YenteEventDTO[] = [];
  eventsLoading = false;
  eventsError = '';

  private timer: ReturnType<typeof setTimeout> | null = null;
  private destroyed = false;
  private loading = false;
  /** Après une demande de réindexation : rafraîchissement rapide jusqu'à cette heure (ms), le temps du passage de Yente. */
  private fastUntil = 0;

  constructor(private yenteService: YenteService, keycloak: KeycloakService) {
    this.isAdmin = keycloak.isAdmin();
  }

  ngOnInit(): void {
    this.refresh();
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.clearTimer();
  }

  /** Relit l'état ; relance le minuteur après chaque réponse (jamais deux requêtes en même temps). */
  refresh(): void {
    if (this.loading) {
      return;
    }
    this.loading = true;
    this.clearTimer();
    this.yenteService.status().subscribe({
      next: status => {
        this.status = status;
        this.errorMessage = '';
        this.isLoading = false;
        this.loading = false;
        const fast = status.indexing || Date.now() < this.fastUntil;
        if (this.selected) {
          const previous = this.selected;
          this.selected = status.datasets.find(d => d.dataset === previous.dataset) ?? previous;
          if (fast && (this.selected.state !== previous.state || this.selected.servedVersion !== previous.servedVersion)) {
            this.loadEvents(this.selected.dataset);   // l'historique suit l'indexation en cours
          }
        }
        this.schedule(fast ? 5000 : 30000);
      },
      error: err => {
        this.errorMessage = apiError(err);   // les dernières données restent affichées
        this.isLoading = false;
        this.loading = false;
        this.schedule(30000);
      },
    });
  }

  get rows(): YenteDatasetStatusDTO[] {
    const term = this.search.trim().toLowerCase();
    return (this.status?.datasets ?? []).filter(d => this.matches(d, this.filter)
      && (!term || d.dataset.includes(term) || (d.parserName ?? '').toLowerCase().includes(term)));
  }

  count(filter: Filter): number {
    return (this.status?.datasets ?? []).filter(d => this.matches(d, filter)).length;
  }

  eventLabel(type: string | null): string {
    return type ? (EVENTS[type] ?? type) : '—';
  }

  open(row: YenteDatasetStatusDTO): void {
    this.selected = row;
    this.events = [];
    this.loadEvents(row.dataset);
  }

  private loadEvents(dataset: string): void {
    this.eventsError = '';
    this.eventsLoading = this.events.length === 0;
    this.yenteService.events(dataset).subscribe({
      next: events => {
        if (this.selected?.dataset === dataset) {   // ignore la réponse d'une ligne refermée entre-temps
          this.events = events;
        }
        this.eventsLoading = false;
      },
      error: err => {
        this.eventsError = apiError(err);
        this.eventsLoading = false;
      },
    });
  }

  close(): void {
    this.selected = null;
  }

  reindexDataset(row: YenteDatasetStatusDTO): void {
    this.message = '';
    this.errorMessage = '';
    this.yenteService.reindexDataset(row.dataset).subscribe({
      next: res => {
        this.message = res.message;
        this.fastUntil = Date.now() + 4 * 60_000;   // passage de Yente (2 min) + indexation
        this.refresh();
      },
      error: err => (this.errorMessage = apiError(err)),
    });
  }

  /** « Tout reconstruire » (administrateur) : tous les index, forcé. */
  rebuild(): void {
    if (!confirm('Reconstruire tous les index de Yente ? Tous les datasets sont réindexés, ce qui peut prendre longtemps.'
      + ' À réserver à une réparation : une publication est indexée automatiquement.')) {
      return;
    }
    this.isRebuilding = true;
    this.message = '';
    this.errorMessage = '';
    this.yenteService.reindex().subscribe({
      next: res => {
        this.isRebuilding = false;
        this.message = res.message;
        this.fastUntil = Date.now() + 4 * 60_000;
        this.refresh();
      },
      error: err => {
        this.isRebuilding = false;
        this.errorMessage = apiError(err);
      },
    });
  }

  private matches(d: YenteDatasetStatusDTO, filter: Filter): boolean {
    switch (filter) {
      case 'ALL': return true;
      case 'PROBLEMS': return PROBLEMS.includes(d.state);
      default: return d.state === filter;
    }
  }

  private schedule(ms: number): void {
    if (!this.destroyed) {
      this.timer = setTimeout(() => this.refresh(), ms);
    }
  }

  private clearTimer(): void {
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }
}
