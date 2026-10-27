import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AmlVerificationService, AmlVerificationRequest } from '../../services/aml-verification.service';
import { ParserService } from '../../services/parser.service';
import { ParserDTO } from '../../models/appTypes';
import Swal from 'sweetalert2';
import { AdverseMediaService, AdverseMediaResult } from '../../services/adverse-media.service';

export interface ProviderItem {
  id: string; // datasetName
  name: string;
  category: 'SANCTIONS' | 'PEP' | 'WANTED' | 'REGULATORS' | 'DEVELOPMENT_BANKS' | 'OTHER';
  countryBadge?: string;
  categoryLabel: string;
}

@Component({
  selector: 'app-aml-verification',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './aml-verification.component.html',
  styleUrl: './aml-verification.component.css'
})
export class AmlVerificationComponent implements OnInit {

  providers: ProviderItem[] = [];
  selectedProviders: string[] = ['default'];
  
  // Selection mode: 'all' (consolidated default) vs 'custom' (user picked specific datasets)
  selectionMode: 'all' | 'custom' = 'all';
  
  // Provider filtering
  providerSearchTerm: string = '';
  selectedCategory: string = 'ALL';

  categories = [
    { id: 'ALL', label: 'Tous', icon: 'fas fa-layer-group' },
    { id: 'SANCTIONS', label: 'Sanctions', icon: 'fas fa-shield-alt' },
    { id: 'PEP', label: 'PEP', icon: 'fas fa-user-tie' },
    { id: 'WANTED', label: 'Recherchés', icon: 'fas fa-bullhorn' },
    { id: 'REGULATORS', label: 'Régulateurs', icon: 'fas fa-landmark' },
    { id: 'FRANCE', label: 'France', icon: 'fas fa-flag' }
  ];

  selectCategory(catId: string): void {
    this.selectedCategory = catId;
  }

  schemas = [
    { id: 'Person', name: 'Personne', icon: 'fas fa-user', desc: 'Individu physique' },
    { id: 'Organization', name: 'Société', icon: 'fas fa-building', desc: 'Entreprise, ONG' },
    { id: 'Thing', name: 'Toute entité', icon: 'fas fa-globe', desc: 'Recherche globale' },
    { id: 'Vessel', name: 'Navire', icon: 'fas fa-ship', desc: 'Bateau, cargo' },
    { id: 'Airplane', name: 'Avion', icon: 'fas fa-plane', desc: 'Aéronef' }
  ];
  selectedSchema: string = 'Person';

  formData: any = {};
  results: any[] | null = null;
  loading: boolean = false;
  lastRequest: AmlVerificationRequest | null = null;
  executionDurationMs: number = 0;
  
  mediaResults: AdverseMediaResult[] | null = null;
  mediaLoading: boolean = false;

  quickExamples = [
    { name: 'Vladimir Putin', schema: 'Person', country: 'RU', label: 'Sanctions (Russie)' },
    { name: 'Carlos Ghosn', schema: 'Person', country: 'FR', label: 'Notice Rouge' },
    { name: 'Al-Qaida', schema: 'Organization', country: '', label: 'Terrorisme ONU' },
    { name: 'BNP Paribas', schema: 'Organization', country: 'FR', label: 'Entité bancaire' }
  ];

  constructor(
    private amlVerificationService: AmlVerificationService,
    private parserService: ParserService,
    private adverseMediaService: AdverseMediaService
  ) {}

  ngOnInit(): void {
    this.loadProviders();
  }

  loadProviders(): void {
    this.parserService.getAllForProviders().subscribe({
      next: (page: any) => {
        const parsers: ParserDTO[] = page.content || [];
        const uniqueDatasets = new Map<string, ProviderItem>();

        parsers.forEach(p => {
          if (p.datasetName) {
            const countryVal = p.countryIso;
            const item = this.classifyProvider(p.datasetName, p.name || p.datasetName, countryVal);
            uniqueDatasets.set(p.datasetName, item);
          }
        });

        this.providers = Array.from(uniqueDatasets.values());
        this.selectedProviders = ['default'];
        this.selectionMode = 'all';
      },
      error: (err) => {
        console.error('Erreur lors du chargement des fournisseurs', err);
      }
    });
  }

  selectAllProviders(): void {
    this.setSelectionMode('all');
  }

  private classifyProvider(datasetName: string, displayName: string, country?: string): ProviderItem {
    const lowerId = datasetName.toLowerCase();
    const lowerName = displayName.toLowerCase();

    let category: ProviderItem['category'] = 'OTHER';
    let countryBadge: string | undefined = country || undefined;
    let categoryLabel = 'Autre';

    if (lowerId.includes('sanction') || lowerId.includes('ofac') || lowerId.includes('uksl') || lowerId.includes('seco') || lowerId.includes('dfat') || lowerId.includes('bis') || lowerId.includes('fatf')) {
      category = 'SANCTIONS';
      categoryLabel = 'Sanction';
      if (lowerId.includes('ofac') || lowerId.includes('bis')) countryBadge = 'USA';
      else if (lowerId.includes('eu_') || lowerId.includes('europol')) countryBadge = 'UE';
      else if (lowerId.includes('onu')) countryBadge = 'ONU';
      else if (lowerId.includes('dgtresor')) countryBadge = 'FR';
      else if (lowerId.includes('seco')) countryBadge = 'CH';
      else if (lowerId.includes('uksl')) countryBadge = 'UK';
      else if (lowerId.includes('dfat')) countryBadge = 'AU';
      else if (lowerId.includes('canada')) countryBadge = 'CA';
    } else if (lowerId.includes('pep')) {
      category = 'PEP';
      categoryLabel = 'PEP';
      if (lowerId.includes('datagouv')) countryBadge = 'FR';
      else countryBadge = 'INT';
    } else if (lowerId.includes('wanted') || lowerId.includes('interpol') || lowerId.includes('fugitive') || lowerId.includes('fbi') || lowerId.includes('dea')) {
      category = 'WANTED';
      categoryLabel = 'Recherché';
      if (lowerId.includes('fbi') || lowerId.includes('dea')) countryBadge = 'USA';
      else if (lowerId.includes('interpol')) countryBadge = 'INT';
      else if (lowerId.includes('europol')) countryBadge = 'UE';
    } else if (lowerId.includes('acpr') || lowerId.includes('amf') || lowerId.includes('fca') || lowerId.includes('sec_') || lowerId.includes('warning') || lowerId.includes('disqualified') || lowerId.includes('bodacc') || lowerId.includes('coh')) {
      category = 'REGULATORS';
      categoryLabel = 'Régulateur';
      if (lowerId.includes('acpr') || lowerId.includes('amf') || lowerId.includes('bodacc')) countryBadge = 'FR';
      else if (lowerId.includes('fca') || lowerId.includes('coh')) countryBadge = 'UK';
      else if (lowerId.includes('sec')) countryBadge = 'USA';
    } else if (lowerId.includes('debarred') || lowerId.includes('worldbank') || lowerId.includes('afdb') || lowerId.includes('idb')) {
      category = 'DEVELOPMENT_BANKS';
      categoryLabel = 'Bailleur';
      countryBadge = 'INT';
    }

    if (!countryBadge) {
      if (lowerName.includes('france') || lowerId.includes('_fr')) countryBadge = 'FR';
      else if (lowerName.includes('uk') || lowerId.includes('gb_')) countryBadge = 'UK';
      else if (lowerName.includes('us') || lowerName.includes('usa')) countryBadge = 'USA';
      else countryBadge = 'INT';
    }

    return {
      id: datasetName,
      name: displayName,
      category,
      countryBadge,
      categoryLabel
    };
  }

  setSelectionMode(mode: 'all' | 'custom'): void {
    this.selectionMode = mode;
    if (mode === 'all') {
      this.selectedProviders = ['default'];
    } else {
      if (this.selectedProviders.length === 1 && this.selectedProviders[0] === 'default') {
        this.selectedProviders = this.providers.map(p => p.id);
      }
    }
  }

  toggleProvider(id: string): void {
    if (id === 'default') {
      this.setSelectionMode('all');
      return;
    }

    this.selectionMode = 'custom';
    this.selectedProviders = this.selectedProviders.filter(p => p !== 'default');

    const idx = this.selectedProviders.indexOf(id);
    if (idx >= 0) {
      this.selectedProviders.splice(idx, 1);
      if (this.selectedProviders.length === 0) {
        this.setSelectionMode('all');
      }
    } else {
      this.selectedProviders.push(id);
    }
  }

  isProviderSelected(id: string): boolean {
    if (this.selectionMode === 'all') {
      return id === 'default';
    }
    return this.selectedProviders.includes(id);
  }

  selectAllCustom(): void {
    this.selectionMode = 'custom';
    this.selectedProviders = this.providers.map(p => p.id);
  }

  clearAllCustom(): void {
    this.selectionMode = 'custom';
    this.selectedProviders = [];
  }

  removeSelectedProvider(id: string): void {
    this.selectedProviders = this.selectedProviders.filter(p => p !== id);
    if (this.selectedProviders.length === 0) {
      this.setSelectionMode('all');
    }
  }

  get filteredProviders(): ProviderItem[] {
    return this.providers.filter(p => {
      // 1. Text filter
      const matchesText = !this.providerSearchTerm.trim() ||
        p.name.toLowerCase().includes(this.providerSearchTerm.toLowerCase()) ||
        p.id.toLowerCase().includes(this.providerSearchTerm.toLowerCase()) ||
        (p.countryBadge && p.countryBadge.toLowerCase().includes(this.providerSearchTerm.toLowerCase()));

      // 2. Category filter
      let matchesCat = true;
      if (this.selectedCategory === 'FRANCE') {
        matchesCat = p.countryBadge === 'FR';
      } else if (this.selectedCategory !== 'ALL') {
        matchesCat = p.category === this.selectedCategory;
      }

      return matchesText && matchesCat;
    });
  }

  getProviderName(id: string): string {
    const found = this.providers.find(p => p.id === id);
    return found ? found.name : id;
  }

  getCategoryCount(catId: string): number {
    if (catId === 'ALL') return this.providers.length;
    if (catId === 'FRANCE') return this.providers.filter(p => p.countryBadge === 'FR').length;
    return this.providers.filter(p => p.category === catId).length;
  }

  applyQuickExample(ex: { name: string, schema: string, country: string }): void {
    this.selectedSchema = ex.schema;
    this.formData = {
      name: ex.name,
      country: ex.country
    };
    this.results = null;
  }

  resetForm(): void {
    this.formData = {};
    this.results = null;
    this.lastRequest = null;
    this.mediaResults = null;
  }

  onSchemaChange(): void {
    this.formData = {};
    this.results = null;
    this.mediaResults = null;
  }

  verifyClient(): void {
    if (!this.formData.name || !this.formData.name.trim()) {
      Swal.fire({
        title: 'Nom requis',
        text: 'Veuillez saisir au minimum un nom pour effectuer la vérification.',
        icon: 'warning',
        confirmButtonColor: '#2563eb'
      });
      return;
    }

    this.loading = true;
    this.results = null;
    this.mediaResults = null;
    this.mediaLoading = true;
    const start = Date.now();

    const properties: { [key: string]: string[] } = {};
    for (const key in this.formData) {
      if (this.formData.hasOwnProperty(key) && this.formData[key] && this.formData[key].toString().trim() !== '') {
        properties[key] = [this.formData[key].toString().trim()];
      }
    }

    const datasetParam = this.selectionMode === 'all' || this.selectedProviders.includes('default')
      ? 'default'
      : (this.selectedProviders.length > 0 ? this.selectedProviders.join(',') : 'default');

    const request: AmlVerificationRequest = {
      dataset: datasetParam,
      schema: this.selectedSchema,
      properties: properties
    };

    this.lastRequest = request;

    this.amlVerificationService.verifyClient(request).subscribe({
      next: (res: any) => {
        this.loading = false;
        this.executionDurationMs = Date.now() - start;
        if (res && res.errorMessage) {
          Swal.fire('Erreur', res.errorMessage, 'error');
          this.results = [];
        } else if (res && res.matches) {
          this.results = res.matches;
        } else {
          this.results = [];
        }
      },
      error: (err) => {
        this.loading = false;
        this.executionDurationMs = Date.now() - start;
        console.error(err);
        Swal.fire('Erreur', 'Une erreur est survenue lors de la communication avec le moteur Yente.', 'error');
      }
    });

    // Also trigger adverse media verification
    this.adverseMediaService.verify(this.formData.name.trim()).subscribe({
      next: (mediaRes: AdverseMediaResult[]) => {
        this.mediaResults = mediaRes;
        this.mediaLoading = false;
      },
      error: (err) => {
        console.error('Erreur Media Verification', err);
        this.mediaLoading = false;
        // Don't show SweetAlert for media errors so it doesn't block the AML results
      }
    });
  }

  getScoreColor(score: number): string {
    if (score >= 0.9) return 'bg-red-500';
    if (score >= 0.7) return 'bg-orange-500';
    return 'bg-amber-500';
  }

  getScoreBadgeClass(score: number): string {
    if (score >= 0.9) return 'bg-red-50 text-red-700 border-red-200';
    if (score >= 0.7) return 'bg-orange-50 text-orange-700 border-orange-200';
    return 'bg-amber-50 text-amber-700 border-amber-200';
  }

  getMaxScore(): number {
    if (!this.results || this.results.length === 0) return 0;
    return Math.max(...this.results.map(r => r.score || 0));
  }

  viewMatchDetails(match: any): void {
    let detailsHtml = '<div class="text-left max-h-[400px] overflow-y-auto space-y-2 text-xs font-mono">';
    if (match.properties) {
      for (const [key, values] of Object.entries(match.properties)) {
        detailsHtml += `
          <div class="p-2 rounded bg-slate-50 border border-slate-200">
            <span class="font-bold text-slate-800">${key}:</span>
            <span class="text-slate-600 ml-1 break-all">${(values as string[]).join(', ')}</span>
          </div>
        `;
      }
    }
    detailsHtml += '</div>';

    Swal.fire({
      title: match.caption || match.id,
      html: detailsHtml,
      icon: 'info',
      confirmButtonText: 'Fermer',
      confirmButtonColor: '#2563eb',
      width: '650px'
    });
  }

  downloadReport(): void {
    if (!this.results || !this.lastRequest) return;

    this.loading = true;
    const request = this.lastRequest;

    this.amlVerificationService.downloadReport(request, this.results).subscribe({
      next: (blob: Blob) => {
        this.loading = false;
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `Rapport_Verification_${this.formData.name || 'AML'}_${new Date().toISOString().slice(0, 10)}.pdf`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        window.URL.revokeObjectURL(url);
        Swal.fire('Succès', 'Le rapport PDF a été généré et téléchargé.', 'success');
      },
      error: (err) => {
        this.loading = false;
        console.error(err);
        Swal.fire('Erreur', 'Impossible de générer le rapport PDF.', 'error');
      }
    });
  }
}
