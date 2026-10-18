import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { ChangeType, DatasetChangeDTO, ParserExecutionDTO, SourceFileDTO } from '../../models/appTypes';
import { ParserExecutionService } from '../../services/parser-execution.service';
import { apiError } from '../../services/api-error';
import { StatusBadgeComponent } from '../shared/status-badge.component';

/**
 * Détail d'une exécution (/executions/:id) :
 * - le bilan (statut, compteurs, message) ;
 * - les fichiers sources signés et archivés (preuve des données utilisées) ;
 * - les changements publiés, entité par entité, filtrables par type.
 */
@Component({
  selector: 'app-execution-detail',
  standalone: true,
  imports: [CommonModule, RouterLink, StatusBadgeComponent],
  templateUrl: './execution-detail.component.html',
})
export class ExecutionDetailComponent implements OnInit {
  execution?: ParserExecutionDTO;
  files: SourceFileDTO[] = [];
  errorMessage = '';

  // Changements : filtre, page courante
  readonly filters: { type: ChangeType | null; label: string }[] = [
    { type: null, label: 'Tous' },
    { type: 'ADDED', label: 'Ajouts' },
    { type: 'REMOVED', label: 'Retraits' },
    { type: 'MODIFIED', label: 'Modifications' },
  ];
  filter: ChangeType | null = null;
  changes: DatasetChangeDTO[] = [];
  changesPage = 0;
  changesTotalPages = 0;
  changesTotal = 0;

  constructor(private route: ActivatedRoute, private executionService: ParserExecutionService) {}

  ngOnInit(): void {
    const id = Number(this.route.snapshot.paramMap.get('id'));
    this.executionService.getById(id).subscribe({
      next: e => (this.execution = e),
      error: err => (this.errorMessage = apiError(err)),
    });
    this.executionService.getFiles(id).subscribe({ next: files => (this.files = files) });
    this.loadChanges(id);
  }

  setFilter(type: ChangeType | null): void {
    this.filter = type;
    this.changesPage = 0;
    this.loadChanges(this.execution!.id);
  }

  changePage(page: number): void {
    if (page >= 0 && page < this.changesTotalPages) {
      this.changesPage = page;
      this.loadChanges(this.execution!.id);
    }
  }

  private loadChanges(id: number): void {
    this.executionService.getChanges(id, this.filter, this.changesPage).subscribe({
      next: page => {
        this.changes = page.content;
        this.changesTotalPages = page.totalPages;
        this.changesTotal = page.totalElements;
      },
    });
  }

  /** Première publication : les ajouts sont comptés mais pas détaillés ligne par ligne. */
  get firstPublication(): boolean {
    const e = this.execution;
    return !!e && e.status === 'SUCCESS' && e.addedCount > 0 && e.removedCount === 0 && e.changedCount === 0
      && this.changesTotal === 0 && this.filter === null;
  }

  /** Taille lisible : 25,9 Mo, 512 Ko… */
  size(bytes: number): string {
    if (bytes >= 1_048_576) {
      return (bytes / 1_048_576).toFixed(1).replace('.', ',') + ' Mo';
    }
    if (bytes >= 1024) {
      return Math.round(bytes / 1024) + ' Ko';
    }
    return bytes + ' o';
  }

  changeLabel(type: ChangeType): string {
    return type === 'ADDED' ? 'Ajoutée' : type === 'REMOVED' ? 'Retirée' : 'Modifiée';
  }

  copy(text: string): void {
    navigator.clipboard?.writeText(text);
  }
}
