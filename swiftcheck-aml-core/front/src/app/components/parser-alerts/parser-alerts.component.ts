import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { map } from 'rxjs';

import { AlertType, ParserAlertDTO } from '../../models/appTypes';
import { AlertService } from '../../services/alert.service';
import { apiError } from '../../services/api-error';

/** Libellé, explication et couleur de chaque type d'alerte. */
const TYPES: Record<AlertType, { label: string; help: string; css: string }> = {
  FAILED:  { label: 'Échec',          help: 'Erreur technique : la liste publiée n\'est plus mise à jour.', css: 'bg-rose-50 text-rose-700 ring-rose-600/20' },
  BLOCKED: { label: 'Bloquée',        help: 'Contrôle qualité en échec : l\'ancienne version reste publiée.', css: 'bg-amber-50 text-amber-700 ring-amber-600/20' },
  STALE:   { label: 'Pas à jour',     help: 'Aucune publication depuis plus de deux fois la fréquence prévue.', css: 'bg-violet-50 text-violet-700 ring-violet-600/20' },
};

/**
 * Alertes sur les parsers (/alerts). Une alerte s'ouvre après une exécution en échec ou bloquée, ou quand un
 * parser n'a rien publié depuis trop longtemps ; elle se résout seule à la publication suivante.
 */
@Component({
  selector: 'app-parser-alerts',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './parser-alerts.component.html',
})
export class ParserAlertsComponent implements OnInit {
  showHistory = false;
  alerts: ParserAlertDTO[] = [];
  isLoading = true;
  errorMessage = '';
  readonly types = TYPES;

  constructor(private alertService: AlertService) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.isLoading = true;
    this.errorMessage = '';
    const alerts$ = this.showHistory
      ? this.alertService.history().pipe(map(page => page.content))
      : this.alertService.open();
    alerts$.subscribe({
      next: alerts => {
        this.alerts = alerts;
        this.isLoading = false;
      },
      error: err => {
        this.errorMessage = apiError(err);
        this.isLoading = false;
      },
    });
  }

  toggleHistory(): void {
    this.showHistory = !this.showHistory;
    this.load();
  }

  resolve(alert: ParserAlertDTO): void {
    this.alertService.resolve(alert.id).subscribe({
      next: () => {
        this.alertService.refreshCount();
        this.load();
      },
      error: err => (this.errorMessage = apiError(err)),
    });
  }
}
