import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { BehaviorSubject, Observable, tap } from 'rxjs';
import { Page, ParserAlertDTO } from '../models/appTypes';

/** Alertes sur les parsers (voir ParserAlertController côté Java). */
@Injectable({ providedIn: 'root' })
export class AlertService {
  private apiUrl = '/api/alerts';

  /** Nombre d'alertes ouvertes, affiché en badge dans le menu. */
  readonly openCount$ = new BehaviorSubject<number>(0);

  constructor(private http: HttpClient) {}

  open(): Observable<ParserAlertDTO[]> {
    return this.http.get<ParserAlertDTO[]>(`${this.apiUrl}/open`)
      .pipe(tap(alerts => this.openCount$.next(alerts.length)));
  }

  /** Met à jour le badge du menu. */
  refreshCount(): void {
    this.http.get<{ count: number }>(`${this.apiUrl}/open/count`)
      .subscribe({ next: res => this.openCount$.next(res.count), error: () => {} });
  }

  history(page = 0, size = 50): Observable<Page<ParserAlertDTO>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<Page<ParserAlertDTO>>(this.apiUrl, { params });
  }

  resolve(id: number): Observable<ParserAlertDTO> {
    return this.http.post<ParserAlertDTO>(`${this.apiUrl}/${id}/resolve`, {});
  }
}
