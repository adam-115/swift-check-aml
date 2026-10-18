import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/** Actions sur Yente (voir YenteController côté Java). */
@Injectable({ providedIn: 'root' })
export class YenteService {
  constructor(private http: HttpClient) {}

  /** Yente relit le manifest et réindexe les listes, en tâche de fond. */
  reindex(): Observable<{ message: string }> {
    return this.http.post<{ message: string }>('/api/yente/reindex', null);
  }
}
