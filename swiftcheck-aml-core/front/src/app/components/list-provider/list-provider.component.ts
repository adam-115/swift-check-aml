import Swal from 'sweetalert2';
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { ListProviderService } from '../../services/list-provider.service';
import { CountryDTO, ListProviderDTO } from '../../models/appTypes';
import { PepReferenceService } from '../../services/pep-reference.service';

import { DomSanitizer, SafeUrl } from '@angular/platform-browser';

@Component({
  selector: 'app-list-provider',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './list-provider.component.html',
  styleUrls: ['./list-provider.component.css']
})
export class ListProviderComponent implements OnInit {
  providers: ListProviderDTO[] = [];
  /** Pays du référentiel (UE + Suisse), pour la liste déroulante. */
  countries: CountryDTO[] = [];
  
  currentPage = 0;
  pageSize = 10;
  totalPages = 0;
  totalElements = 0;

  isDrawerOpen = false;
  providerForm: FormGroup;
  isEditing = false;
  currentProviderId: number | null = null;
  
  selectedFileBase64: string | null = null;
  togglingIds = new Set<number>();

  constructor(
    private providerService: ListProviderService, 
    private fb: FormBuilder,
    private sanitizer: DomSanitizer,
    private referenceService: PepReferenceService
  ) {
    this.providerForm = this.fb.group({
      name: ['', Validators.required],
      code: ['', Validators.required],
      listType: ['AML', Validators.required],
      country: [''],
      websiteUrl: [''],
      description: [''],
      enabled: [true]
    });
  }

  ngOnInit(): void {
    this.loadProviders();
    this.referenceService.countries().subscribe({ next: countries => (this.countries = countries) });
  }

  // Safe Logo getter
  getSafeLogo(logo?: string): SafeUrl | string {
    if (!logo) return '';
    const isDataImage = logo.startsWith('data:image');
    const url = isDataImage ? logo : 'data:image/png;base64,' + logo;
    return this.sanitizer.bypassSecurityTrustUrl(url);
  }

  // KPI Helpers
  getActiveCount(): number {
    return this.providers.filter(p => p.enabled).length;
  }

  getSanctionsCount(): number {
    return this.providers.filter(p => p.listType === 'AML').length;
  }

  /** « LU » → « Luxembourg » ; « International » sans pays. */
  countryName(iso?: string): string {
    if (!iso) {
      return 'International';
    }
    return this.countries.find(c => c.iso === iso)?.name ?? iso;
  }

  getPepCount(): number {
    return this.providers.filter(p => p.listType === 'PEP').length;
  }

  getCountriesCount(): number {
    const countries = new Set(this.providers.map(p => p.country).filter(c => !!c));
    return countries.size;
  }

  isToggling(id?: number): boolean {
    return id !== undefined && this.togglingIds.has(id);
  }

  loadProviders(): void {
    this.providerService.getAll(this.currentPage, this.pageSize).subscribe({
      next: (page) => {
        this.providers = page.content;
        this.totalPages = page.totalPages;
        this.totalElements = page.totalElements;
      },
      error: (err) => console.error('Erreur de chargement', err)
    });
  }

  changePage(page: number): void {
    if (page >= 0 && page < this.totalPages) {
      this.currentPage = page;
      this.loadProviders();
    }
  }

  openDrawer(provider?: ListProviderDTO): void {
    this.isDrawerOpen = true;
    this.selectedFileBase64 = null;
    if (provider) {
      this.isEditing = true;
      this.currentProviderId = provider.id ?? null;
      this.providerForm.patchValue({
        name: provider.name,
        code: provider.code,
        listType: provider.listType || 'AML',
        country: provider.country ?? '',
        websiteUrl: provider.websiteUrl || '',
        description: provider.description,
        enabled: provider.enabled !== undefined ? provider.enabled : true
      });
      if (provider.logo) {
         this.selectedFileBase64 = provider.logo.includes('data:image') ? provider.logo : 'data:image/png;base64,' + provider.logo;
      }
    } else {
      this.isEditing = false;
      this.currentProviderId = null;
      this.providerForm.reset({ enabled: true, listType: 'AML' });
    }
  }

  closeDrawer(): void {
    this.isDrawerOpen = false;
    this.providerForm.reset({ enabled: true, listType: 'AML' });
    this.selectedFileBase64 = null;
  }

  onFileChange(event: any): void {
    const file = event.target.files[0];
    if (file) {
      const reader = new FileReader();
      reader.onload = (e: any) => {
        this.selectedFileBase64 = e.target.result;
      };
      reader.readAsDataURL(file);
    }
  }

  clearLogo(): void {
    this.selectedFileBase64 = null;
  }

  saveProvider(): void {
    if (this.providerForm.valid) {
      const formValue = this.providerForm.value;
      let pureBase64 = this.selectedFileBase64;
      if (pureBase64 && pureBase64.includes(',')) {
        pureBase64 = pureBase64.split(',')[1];
      }
      
      const providerData: ListProviderDTO = {
        ...formValue,
        id: this.isEditing ? this.currentProviderId ?? undefined : undefined,
        logo: pureBase64
      };

      this.providerService.save(providerData).subscribe({
        next: () => {
          this.closeDrawer();
          this.loadProviders();
          Swal.fire({
            title: 'Enregistré !',
            text: `Le fournisseur "${providerData.name}" a été sauvegardé avec succès.`,
            icon: 'success',
            timer: 2000,
            showConfirmButton: false
          });
        },
        error: (err) => {
          Swal.fire('Erreur', err.error?.message || 'Erreur lors de la sauvegarde', 'error');
        }
      });
    }
  }

  toggleEnabled(provider: ListProviderDTO): void {
    if (!provider.id) return;
    const newStatus = !provider.enabled;
    const updatedProvider = { ...provider, enabled: newStatus };
    
    this.togglingIds.add(provider.id);
    this.providerService.save(updatedProvider).subscribe({
      next: () => {
        if (provider.id) this.togglingIds.delete(provider.id);
        provider.enabled = newStatus;
        
        const Toast = Swal.mixin({
          toast: true,
          position: 'top-end',
          showConfirmButton: false,
          timer: 2000,
          timerProgressBar: true
        });
        Toast.fire({
          icon: newStatus ? 'success' : 'info',
          title: `Source "${provider.name}" ${newStatus ? 'activée' : 'désactivée'}`
        });
      },
      error: (err) => {
        if (provider.id) this.togglingIds.delete(provider.id);
        console.error('Erreur basculement', err);
        Swal.fire('Erreur', 'Impossible de changer le statut', 'error');
      }
    });
  }

  deleteProvider(provider: ListProviderDTO): void {
    if (!provider.id) return;

    Swal.fire({
      title: 'Supprimer ce fournisseur ?',
      html: `Êtes-vous sûr de vouloir supprimer définitivement <strong>${provider.name}</strong> (${provider.code}) ?<br><span class="text-xs text-rose-500">Les parsers associés risquent d'être impactés.</span>`,
      icon: 'warning',
      showCancelButton: true,
      confirmButtonColor: '#ef4444',
      cancelButtonColor: '#64748b',
      confirmButtonText: '🗑️ Oui, supprimer',
      cancelButtonText: 'Annuler'
    }).then((result) => {
      if (result.isConfirmed && provider.id) {
        this.providerService.delete(provider.id).subscribe({
          next: () => {
            this.loadProviders();
            Swal.fire({
              title: 'Supprimé !',
              text: `Le fournisseur "${provider.name}" a été supprimé.`,
              icon: 'success',
              timer: 2000,
              showConfirmButton: false
            });
          },
          error: (err) => Swal.fire('Erreur', err.error?.message || 'Erreur lors de la suppression', 'error')
        });
      }
    });
  }
}