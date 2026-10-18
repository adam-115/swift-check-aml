import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { CountryDTO, CoverageDTO, PepFunctionDTO, PepSourceDTO } from '../models/appTypes';

/** Référentiel PEP, sources et couverture (voir PepReferenceController et PepSourceController côté Java). */
@Injectable({ providedIn: 'root' })
export class PepReferenceService {
  private apiUrl = '/api/pep';

  constructor(private http: HttpClient) {}

  countries(): Observable<CountryDTO[]> {
    return this.http.get<CountryDTO[]>(`${this.apiUrl}/countries`);
  }

  /** Fonctions PEP d'un pays, ou de tous les pays sans paramètre. */
  functions(countryIso?: string): Observable<PepFunctionDTO[]> {
    const params = countryIso ? new HttpParams().set('country', countryIso) : new HttpParams();
    return this.http.get<PepFunctionDTO[]>(`${this.apiUrl}/functions`, { params });
  }

  coverage(countryIso: string): Observable<CoverageDTO> {
    return this.http.get<CoverageDTO>(`${this.apiUrl}/countries/${countryIso}/coverage`);
  }

  /** Sources PEP d'un pays, ou de tous les pays sans paramètre. */
  sources(countryIso?: string): Observable<PepSourceDTO[]> {
    const params = countryIso ? new HttpParams().set('country', countryIso) : new HttpParams();
    return this.http.get<PepSourceDTO[]>(`${this.apiUrl}/sources`, { params });
  }

  /** Création si pas d'id, sinon modification (les fonctions couvertes sont remplacées). */
  saveSource(source: PepSourceDTO): Observable<PepSourceDTO> {
    return source.id
      ? this.http.put<PepSourceDTO>(`${this.apiUrl}/sources/${source.id}`, source)
      : this.http.post<PepSourceDTO>(`${this.apiUrl}/sources`, source);
  }

  deleteSource(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/sources/${id}`);
  }
}
