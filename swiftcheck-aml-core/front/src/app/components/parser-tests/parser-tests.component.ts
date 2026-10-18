import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';

import { ParserTestDTO } from '../../models/appTypes';
import { ParserTestService } from '../../services/parser-test.service';
import { YenteService } from '../../services/yente.service';
import { apiError } from '../../services/api-error';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { sourceFileName } from '../shared/format';

/**
 * Historique des tests de parser (/tests), du plus récent au plus ancien.
 * Un test se lance depuis l'écran « Parsers » (bouton « Tester ») ; un clic sur une ligne ouvre le détail.
 */
@Component({
  selector: 'app-parser-tests',
  standalone: true,
  imports: [CommonModule, RouterLink, StatusBadgeComponent],
  templateUrl: './parser-tests.component.html',
})
export class ParserTestsComponent implements OnInit {
  tests: ParserTestDTO[] = [];
  page = 0;
  totalPages = 0;
  totalElements = 0;
  isLoading = true;
  errorMessage = '';
  isReindexing = false;
  readonly fileName = sourceFileName;
  reindexMessage = '';

  constructor(private router: Router, private testService: ParserTestService, private yenteService: YenteService) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.isLoading = true;
    this.testService.getAll(this.page).subscribe({
      next: page => {
        this.tests = page.content;
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

  open(test: ParserTestDTO): void {
    this.router.navigate(['/tests', test.id]);
  }
}
