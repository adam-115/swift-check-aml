import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { Page, ParserExecutionDTO } from '../../models/appTypes';
import { ParserExecutionService } from '../../services/parser-execution.service';
import { ParserService } from '../../services/parser.service';
import { apiError } from '../../services/api-error';
import { StatusBadgeComponent } from '../shared/status-badge.component';

/**
 * Historique des exécutions.
 * - /executions               : toutes les exécutions, de la plus récente à la plus ancienne ;
 * - /parsers/:id/executions   : celles d'un seul parser.
 * Un clic sur une ligne ouvre le détail (/executions/:id).
 */
@Component({
  selector: 'app-parser-executions',
  standalone: true,
  imports: [CommonModule, RouterLink, StatusBadgeComponent],
  templateUrl: './parser-executions.component.html',
})
export class ParserExecutionsComponent implements OnInit {
  parserId?: number;
  parserName = '';
  executions: ParserExecutionDTO[] = [];
  page = 0;
  totalPages = 0;
  totalElements = 0;
  isLoading = true;
  errorMessage = '';

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private executionService: ParserExecutionService,
    private parserService: ParserService,
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.parserId = Number(id);
      this.parserService.getById(this.parserId).subscribe({ next: p => (this.parserName = p.name ?? '') });
    }
    this.load();
  }

  load(): void {
    this.isLoading = true;
    const request = this.parserId
      ? this.executionService.getByParser(this.parserId, this.page)
      : this.executionService.getAll(this.page);
    request.subscribe({
      next: (page: Page<ParserExecutionDTO>) => {
        this.executions = page.content;
        this.totalPages = page.totalPages;
        this.totalElements = page.totalElements;
        this.isLoading = false;
      },
      error: err => {
        this.errorMessage = apiError(err);
        this.isLoading = false;
      },
    });
  }

  changePage(page: number): void {
    if (page >= 0 && page < this.totalPages) {
      this.page = page;
      this.load();
    }
  }

  open(execution: ParserExecutionDTO): void {
    this.router.navigate(['/executions', execution.id]);
  }

  /** Durée lisible, ex. « 4,2 s » ou « 2 min 05 s ». */
  duration(e: ParserExecutionDTO): string {
    if (!e.startedAt || !e.finishedAt) {
      return '—';
    }
    const ms = new Date(e.finishedAt).getTime() - new Date(e.startedAt).getTime();
    if (ms < 60_000) {
      return (ms / 1000).toFixed(1).replace('.', ',') + ' s';
    }
    const s = Math.round(ms / 1000);
    return `${Math.floor(s / 60)} min ${String(s % 60).padStart(2, '0')} s`;
  }
}
