import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/** Une ligne du fichier CSV, lue et normalisée par le serveur. */
export interface BatchEntity {
  lineNumber: number;
  reference?: string;
  schema?: string;
  firstName?: string;
  lastName?: string;
  name?: string;
  nationality?: string;
  birthDate?: string;
  birthPlace?: string;
  country?: string;
  warnings: string[];
  valid: boolean;
}

export interface BatchParseResult {
  fileName: string;
  separator: string;
  recognizedColumns: string[];
  ignoredColumns: string[];
  entities: BatchEntity[];
  validCount: number;
  invalidCount: number;
  warnings: string[];
}

export type BatchRowStatus = 'HIT' | 'COUNTRY_ALERT' | 'CLEAN' | 'INVALID' | 'ERROR';

export interface BatchVerificationRow {
  entity: BatchEntity;
  status: BatchRowStatus;
  matchesCount: number;
  topScore?: number;
  topMatchCaption?: string;
  highestRisk?: string;
  matches: any[];
  errorMessage?: string;
}

export interface BatchVerificationResult {
  fileName: string;
  dataset: string;
  startedAt: string;
  durationMs: number;
  total: number;
  hits: number;
  countryAlerts: number;
  clean: number;
  invalid: number;
  errors: number;
  rows: BatchVerificationRow[];
}

@Injectable({ providedIn: 'root' })
export class BatchVerificationService {

  private apiUrl = '/api/verification/batch';

  constructor(private http: HttpClient) { }

  parse(file: File): Observable<BatchParseResult> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<BatchParseResult>(this.apiUrl + '/parse', form);
  }

  verify(fileName: string, dataset: string, entities: BatchEntity[]): Observable<BatchVerificationResult> {
    return this.http.post<BatchVerificationResult>(this.apiUrl + '/verify', { fileName, dataset, entities });
  }

  downloadReport(result: BatchVerificationResult): Observable<Blob> {
    return this.http.post(this.apiUrl + '/report', result, { responseType: 'blob' });
  }

  downloadTemplate(): Observable<Blob> {
    return this.http.get(this.apiUrl + '/template', { responseType: 'blob' });
  }
}
