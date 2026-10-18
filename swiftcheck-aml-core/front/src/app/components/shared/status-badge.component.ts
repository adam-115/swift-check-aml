import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ExecutionStatus, TestStatus } from '../../models/appTypes';

/** Libellé français et couleurs de chaque statut d'exécution ou de test. */
const STATUS: Record<ExecutionStatus | TestStatus, { label: string; css: string; title: string }> = {
  SUCCESS:   { label: 'Publiée',   css: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20', title: 'Nouvelle version publiée dans Yente' },
  UNCHANGED: { label: 'Inchangée', css: 'bg-slate-100 text-slate-600 ring-slate-500/20',      title: 'Source identique à la dernière publication' },
  BLOCKED:   { label: 'Bloquée',   css: 'bg-amber-50 text-amber-700 ring-amber-600/20',       title: 'Contrôle qualité en échec : l\'ancienne version reste utilisée' },
  FAILED:    { label: 'Échec',     css: 'bg-rose-50 text-rose-700 ring-rose-600/20',          title: 'Erreur technique : rien n\'a été publié' },
  RUNNING:   { label: 'En cours',  css: 'bg-blue-50 text-blue-700 ring-blue-600/20',          title: 'En cours' },
  PASSED:    { label: 'Réussi',    css: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20', title: 'Tous les éléments de la source sont dans Yente' },
};

/** Pastille de statut d'exécution, utilisée dans les listes et le détail. */
@Component({
  selector: 'app-status-badge',
  standalone: true,
  imports: [CommonModule],
  template: `
    <span *ngIf="status; else none" [title]="info.title"
          class="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1 text-xs font-semibold ring-1 ring-inset"
          [ngClass]="info.css">
      <span class="w-1.5 h-1.5 rounded-full bg-current"></span>{{ info.label }}
    </span>
    <ng-template #none><span class="text-xs text-slate-400 italic">Jamais exécuté</span></ng-template>
  `,
})
export class StatusBadgeComponent {
  @Input() status?: ExecutionStatus | TestStatus | null;

  get info() {
    return STATUS[this.status as ExecutionStatus | TestStatus] ?? STATUS.FAILED;
  }
}
