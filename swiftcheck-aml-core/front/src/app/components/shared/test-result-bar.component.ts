import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ParserTestDTO } from '../../models/appTypes';

/** Segments de la barre, dans l'ordre : retrouvés puis chaque raison d'échec. */
const SEGMENTS: { key: keyof ParserTestDTO; label: string; bar: string; dot: string }[] = [
  { key: 'foundCount',        label: 'Retrouvés',              bar: 'bg-emerald-500', dot: 'bg-emerald-500' },
  { key: 'notConvertedCount', label: 'Non convertis',          bar: 'bg-rose-500',    dot: 'bg-rose-500' },
  { key: 'notIndexedCount',   label: 'Non indexés',            bar: 'bg-amber-400',   dot: 'bg-amber-400' },
  { key: 'notMatchedCount',   label: 'Non retrouvés par nom',  bar: 'bg-sky-400',     dot: 'bg-sky-400' },
  { key: 'errorCount',        label: 'Erreurs Yente',          bar: 'bg-slate-400',   dot: 'bg-slate-400' },
];

/**
 * Résultat d'un test en une barre : part de chaque résultat parmi les éléments de la source,
 * avec le pourcentage retrouvé et, en option, la légende chiffrée.
 */
@Component({
  selector: 'app-test-result-bar',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div *ngIf="test">
      <div class="flex items-baseline justify-between gap-3 mb-1.5">
        <span class="text-sm font-bold" [class.text-emerald-700]="test.status === 'PASSED'" [class.text-slate-900]="test.status !== 'PASSED'">
          {{ test.foundPercent | number:'1.0-1' }} % retrouvés
        </span>
        <span class="text-xs text-slate-500 font-mono">{{ test.foundCount | number }} / {{ test.sourceCount | number }}</span>
      </div>
      <div class="flex h-2.5 w-full overflow-hidden rounded-full bg-slate-100" role="img"
           [attr.aria-label]="test.foundPercent + ' % des éléments de la source retrouvés dans Yente'">
        <div *ngFor="let s of visible" [class]="s.bar + ' h-full'" [style.width.%]="share(s.key)" [title]="s.label + ' : ' + value(s.key)"></div>
      </div>
      <div *ngIf="legend" class="mt-2 flex flex-wrap gap-x-4 gap-y-1">
        <span *ngFor="let s of segments" class="inline-flex items-center gap-1.5 text-xs"
              [class.text-slate-400]="value(s.key) === 0" [class.text-slate-700]="value(s.key) > 0">
          <span class="h-2 w-2 rounded-full" [ngClass]="value(s.key) > 0 ? s.dot : 'bg-slate-200'"></span>
          {{ s.label }} <span class="font-mono font-semibold">{{ value(s.key) | number }}</span>
        </span>
      </div>
    </div>
  `,
})
export class TestResultBarComponent {
  @Input() test?: ParserTestDTO | null;
  /** Affiche la légende chiffrée sous la barre. */
  @Input() legend = false;

  readonly segments = SEGMENTS;

  get visible() {
    return SEGMENTS.filter(s => this.value(s.key) > 0);
  }

  value(key: keyof ParserTestDTO): number {
    return (this.test?.[key] as number) ?? 0;
  }

  share(key: keyof ParserTestDTO): number {
    const total = this.test?.sourceCount ?? 0;
    return total ? (this.value(key) * 100) / total : 0;
  }
}
