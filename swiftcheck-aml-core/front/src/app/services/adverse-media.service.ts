import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface AdverseMediaResult {
  title: string;
  content: string;
  url: string;
  engine: string;
  publishedDate: string;
  riskScore: number;
}

@Injectable({
  providedIn: 'root'
})
export class AdverseMediaService {
  private apiUrl = '/api/media';

  constructor(private http: HttpClient) {}

  verify(targetName: string): Observable<AdverseMediaResult[]> {
    return this.http.get<AdverseMediaResult[]>(`${this.apiUrl}/verify`, {
      params: { targetName }
    });
  }
}
