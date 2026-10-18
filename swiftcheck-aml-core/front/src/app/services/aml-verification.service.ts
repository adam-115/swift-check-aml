import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface AmlVerificationRequest {
  dataset: string;
  schema: string;
  properties: { [key: string]: string[] };
}

@Injectable({
  providedIn: 'root'
})
export class AmlVerificationService {

  private apiUrl = '/api/verification';

  constructor(private http: HttpClient) { }

  verifyClient(request: AmlVerificationRequest): Observable<any> {
    return this.http.post<any>(this.apiUrl + '/verify', request);
  }

  verifyAdvanced(request: import('../models/appTypes').AmlAdvancedVerificationRequestDTO): Observable<import('../models/appTypes').AmlAdvancedVerificationResponseDTO> {
    return this.http.post<import('../models/appTypes').AmlAdvancedVerificationResponseDTO>(this.apiUrl + '/verify-advanced', request);
  }

  downloadReport(request: AmlVerificationRequest, responseMatches: any[]): Observable<Blob> {
    const reportRequest = {
      request: request,
      response: {
        matches: responseMatches
      }
    };
    return this.http.post(this.apiUrl + '/report', reportRequest, { responseType: 'blob' });
  }
}
