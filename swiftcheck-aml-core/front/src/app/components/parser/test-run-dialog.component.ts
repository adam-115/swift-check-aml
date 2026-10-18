import { Component, EventEmitter, HostListener, Input, OnChanges, OnDestroy, OnInit, Output, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';

import { ParserDTO, ParserTestDTO, ParserTestPreviewDTO, SourceFileDTO } from '../../models/appTypes';
import { ParserService } from '../../services/parser.service';
import { apiError } from '../../services/api-error';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { TestResultBarComponent } from '../shared/test-result-bar.component';
import { elapsed, fileSize, sourceFileName, sourceHost } from '../shared/format';

/**
 * Fenêtre du test d'un parser, en trois temps :
 * 1. confirmation : publication et fichier source qui seront testés, étapes, dernier test, blocages éventuels ;
 * 2. test en cours : fichier testé et durée écoulée (la fenêtre peut être fermée, le test continue) ;
 * 3. résultat : barre par résultat et lien vers le détail.
 *
 * Le test lui-même est lancé par l'écran parent (événement « confirmed ») pour continuer si la fenêtre est fermée.
 */
@Component({
  selector: 'app-test-run-dialog',
  standalone: true,
  imports: [CommonModule, RouterLink, StatusBadgeComponent, TestResultBarComponent],
  templateUrl: './test-run-dialog.component.html',
})
export class TestRunDialogComponent implements OnInit, OnChanges, OnDestroy {
  @Input({ required: true }) parser!: ParserDTO;
  /** Test en cours pour ce parser, lancé à cette heure (ms). */
  @Input() runningSince?: number;
  @Input() result?: ParserTestDTO;
  @Input() error = '';
  @Output() confirmed = new EventEmitter<void>();
  @Output() closed = new EventEmitter<void>();

  preview?: ParserTestPreviewDTO;
  previewError = '';
  now = Date.now();
  private timer?: ReturnType<typeof setInterval>;

  readonly fileSize = fileSize;
  readonly fileName = sourceFileName;
  readonly host = sourceHost;

  constructor(private parserService: ParserService) {}

  ngOnInit(): void {
    this.parserService.testPreview(this.parser.id!).subscribe({
      next: preview => (this.preview = preview),
      error: err => (this.previewError = apiError(err)),
    });
    this.syncTimer();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['runningSince']) {
      this.syncTimer();
    }
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  @HostListener('document:keydown.escape')
  close(): void {
    this.closed.emit();
  }

  get state(): 'confirm' | 'running' | 'result' {
    if (this.result || this.error) {
      return 'result';
    }
    return this.runningSince ? 'running' : 'confirm';
  }

  get elapsed(): string {
    return this.runningSince ? elapsed(this.now - this.runningSince) : '';
  }

  /** Fichier principal testé : celui de la publication (ou du résultat une fois le test fini). */
  get file(): SourceFileDTO | undefined {
    return (this.result?.sourceFiles ?? this.preview?.sourceFiles ?? [])[0];
  }

  get moreFiles(): number {
    return Math.max(0, (this.preview?.sourceFiles.length ?? 0) - 1);
  }

  /** Ancienneté du fichier testé : « il y a 3 h ». Un fichier ancien mérite peut-être une nouvelle exécution. */
  get fileAge(): string {
    const fetched = this.file?.fetchedAt;
    if (!fetched) {
      return '';
    }
    const hours = (Date.now() - new Date(fetched).getTime()) / 3_600_000;
    if (hours < 1) {
      return 'il y a moins d\'une heure';
    }
    return hours < 48 ? `il y a ${Math.round(hours)} h` : `il y a ${Math.round(hours / 24)} jours`;
  }

  copy(text: string): void {
    navigator.clipboard?.writeText(text);
  }

  private syncTimer(): void {
    clearInterval(this.timer);
    if (this.runningSince) {
      this.now = Date.now();
      this.timer = setInterval(() => (this.now = Date.now()), 1000);
    }
  }
}
