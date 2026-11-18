import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/** Actions sur Yente (voir YenteController côté Java). */
@Injectable({ providedIn: 'root' })
export class YenteService {
  constructor(private http: HttpClient) {}

  /** « Tout reconstruire » : Yente reconstruit tous les index (forcé, long), en tâche de fond. Administrateur. */
  reindex(): Observable<{ message: string }> {
    return this.http.post<{ message: string }>('/api/yente/reindex', null);
  }

  /** Yente réindexe seulement ce dataset, à son prochain passage (2 minutes au plus). */
  reindexDataset(name: string): Observable<{ message: string }> {
    return this.http.post<{ message: string }>(`/api/yente/datasets/${encodeURIComponent(name)}/reindex`, null);
  }
}
