import { Component, EventEmitter, HostListener, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { ExecutionStatus, ParserDTO, TestStatus } from '../../models/appTypes';
import { StatusBadgeComponent } from '../shared/status-badge.component';

/** Résultat d'un parser dans un lot : statut renvoyé, ou erreur (prérequis manquant, plugin absent…). */
export interface BulkItem {
  parser: ParserDTO;
  state: 'pending' | 'running' | 'done' | 'error' | 'skipped';
  status?: ExecutionStatus | TestStatus;
  message?: string;
  /** Lien vers le détail de l'exécution ou du test. */
  link?: unknown[];
}

/**
 * Lot « Exécuter tout » ou « Tester tout » : les parsers sont traités un par un par l'écran Parsers
 * (pour ne pas surcharger le serveur ni Yente) ; cette fenêtre demande confirmation puis suit l'avancement.
 */
export interface BulkRun {
  mode: 'run' | 'test';
  items: BulkItem[];
  /** Parsers filtrés mais exclus du lot (désactivés, déjà en cours). */
  excluded: ParserDTO[];
  force: boolean;
  phase: 'confirm' | 'running' | 'finished';
  /** Arrêt demandé : le parser en cours se termine, les suivants ne sont pas lancés. */
  stopping: boolean;
}

@Component({
  selector: 'app-bulk-run-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, StatusBadgeComponent],
  templateUrl: './bulk-run-dialog.component.html',
})
export class BulkRunDialogComponent {
  @Input({ required: true }) bulk!: BulkRun;
  @Output() confirmed = new EventEmitter<void>();
  @Output() stop = new EventEmitter<void>();
  @Output() closed = new EventEmitter<void>();

  get title(): string {
    const what = this.bulk.mode === 'run' ? 'Exécuter' : 'Tester';
    switch (this.bulk.phase) {
      case 'confirm':
        return `${what} ${this.bulk.items.length} parser${this.bulk.items.length > 1 ? 's' : ''} ?`;
      case 'running':
        return this.bulk.mode === 'run' ? 'Exécution en cours…' : 'Tests en cours…';
      default:
        return this.bulk.mode === 'run' ? 'Exécution terminée' : 'Tests terminés';
    }
  }

  count(state: BulkItem['state']): number {
    return this.bulk.items.filter(i => i.state === state).length;
  }

  /** Parsers traités (terminés, en erreur ou non lancés après un arrêt). */
  get processed(): number {
    return this.bulk.items.filter(i => i.state !== 'pending' && i.state !== 'running').length;
  }

  /** Résultats « à regarder » : échecs, blocages, tests en échec et erreurs. */
  get problems(): number {
    return this.bulk.items.filter(i => i.state === 'error' || i.status === 'FAILED' || i.status === 'BLOCKED').length;
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.close();
  }

  /** Fermer pendant le lot ne l'arrête pas : il continue, et la fenêtre se rouvre depuis le bandeau. */
  close(): void {
    this.closed.emit();
  }
}
