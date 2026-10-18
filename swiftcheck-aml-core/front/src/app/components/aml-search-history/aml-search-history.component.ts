import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AmlSearchHistoryService, AmlSearchHistoryDTO } from '../../services/aml-search-history.service';

@Component({
  selector: 'app-aml-search-history',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './aml-search-history.component.html',
  styleUrl: './aml-search-history.component.css'
})
export class AmlSearchHistoryComponent implements OnInit {

  historyList: AmlSearchHistoryDTO[] = [];
  loading: boolean = true;
  error: string | null = null;
  expandedRowId: number | null = null;

  constructor(private historyService: AmlSearchHistoryService) {}

  ngOnInit(): void {
    this.loadHistory();
  }

  loadHistory(): void {
    this.loading = true;
    this.historyService.getAllHistory().subscribe({
      next: (data) => {
        this.historyList = data;
        this.loading = false;
        this.expandedRowId = null;
      },
      error: (err) => {
        this.error = "Erreur lors du chargement de l'historique.";
        this.loading = false;
        console.error(err);
      }
    });
  }

  toggleRow(id: number): void {
    if (this.expandedRowId === id) {
      this.expandedRowId = null;
    } else {
      this.expandedRowId = id;
    }
  }

  parseProperties(json: string): any {
    try {
      return JSON.parse(json);
    } catch (e) {
      return null;
    }
  }

  getPropertyKeys(json: string): string[] {
    const props = this.parseProperties(json);
    return props ? Object.keys(props) : [];
  }

  getKeys(obj: any): string[] {
    return obj ? Object.keys(obj) : [];
  }
}
