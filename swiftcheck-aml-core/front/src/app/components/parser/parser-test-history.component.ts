import { Component, EventEmitter, HostListener, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';

import { ParserDTO, ParserTestDTO } from '../../models/appTypes';
import { ParserTestService } from '../../services/parser-test.service';
import { apiError } from '../../services/api-error';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { TestResultBarComponent } from '../shared/test-result-bar.component';
import { duration, fileSize, sourceFileName, sourceHost } from '../shared/format';

/**
 * Panneau latéral « Historique des tests » d'un parser : tendance du taux retrouvé, puis chaque test
 * avec son résultat, la publication et le fichier source testés (nom, date de téléchargement, taille, empreinte).
 */
@Component({
  selector: 'app-parser-test-history',
  standalone: true,
  imports: [CommonModule, RouterLink, StatusBadgeComponent, TestResultBarComponent],
  templateUrl: './parser-test-history.component.html',
})
export class ParserTestHistoryComponent implements OnChanges {
  @Input({ required: true }) parser!: ParserDTO;
  /** Change à chaque test terminé, pour recharger la liste. */
  @Input() refreshKey = 0;
  /** Un test de ce parser est en cours (bouton « Lancer un test » désactivé). */
  @Input() running = false;
  /** Faux quand la fenêtre de test est ouverte par-dessus : Échap ne ferme alors que celle-ci. */
  @Input() escapeEnabled = true;
  @Output() closed = new EventEmitter<void>();
  @Output() runTest = new EventEmitter<void>();

  tests: ParserTestDTO[] = [];
  page = 0;
  totalPages = 0;
  total = 0;
  isLoading = true;
  errorMessage = '';

  readonly fileSize = fileSize;
  readonly fileName = sourceFileName;
  readonly host = sourceHost;
  readonly duration = duration;

  constructor(private testService: ParserTestService) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['parser'] || changes['refreshKey']) {
      this.tests = [];
      this.page = 0;
      this.load();
    }
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.escapeEnabled) {
      this.close();
    }
  }

  close(): void {
    this.closed.emit();
  }

  load(): void {
    this.isLoading = true;
    this.testService.getAll(this.page, this.parser.id).subscribe({
      next: page => {
        this.tests = this.page === 0 ? page.content : [...this.tests, ...page.content];
        this.totalPages = page.totalPages;
        this.total = page.totalElements;
        this.isLoading = false;
      },
      error: err => {
        this.errorMessage = apiError(err);
        this.isLoading = false;
      },
    });
  }

  more(): void {
    if (this.page < this.totalPages - 1) {
      this.page++;
      this.load();
    }
  }

  /** Tendance : les 12 derniers tests, du plus ancien au plus récent. */
  get trend(): ParserTestDTO[] {
    return this.tests.slice(0, 12).reverse();
  }

  get passedCount(): number {
    return this.tests.filter(t => t.status === 'PASSED').length;
  }

  /** Le fichier source a-t-il changé depuis le test précédent ? (empreinte différente) */
  newSource(index: number): boolean {
    const current = this.tests[index]?.sourceFiles[0]?.sha256;
    const previous = this.tests[index + 1]?.sourceFiles[0]?.sha256;
    return !!current && !!previous && current !== previous;
  }

  copy(text: string): void {
    navigator.clipboard?.writeText(text);
  }
}
