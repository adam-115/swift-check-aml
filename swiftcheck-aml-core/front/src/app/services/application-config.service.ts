import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ApplicationConfigDTO } from '../models/appTypes';

/** Configuration globale : une seule configuration, lue et remplacée en entier. */
@Injectable({ providedIn: 'root' })
export class ApplicationConfigService {
  private apiUrl = '/api/config';

  constructor(private http: HttpClient) {}

  get(): Observable<ApplicationConfigDTO> {
    return this.http.get<ApplicationConfigDTO>(this.apiUrl);
  }

  update(config: ApplicationConfigDTO): Observable<ApplicationConfigDTO> {
    return this.http.put<ApplicationConfigDTO>(this.apiUrl, config);
  }
}
